package com.shieldstream.tv.data.repository

import android.util.Log
import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.CalendarRepository
import com.shieldstream.tv.domain.CalendarRules
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.ProfileRepository
import com.shieldstream.tv.domain.ShowCalendarEpisodes
import com.shieldstream.tv.domain.mapConcurrent
import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CalendarEpisode
import com.shieldstream.tv.model.CalendarRelease
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.ShowAiring
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.dto.TmdbPaginated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Calendar data. Episodes reuse [CatalogRepository]'s six-hour airing and season caches, so the
 * Episodes tab, Home and the calendar share one download per show. Releases come from TMDB
 * discover, one request per media type and page for a whole month, cached for six hours.
 */
@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val catalog: CatalogRepository,
    private val tmdb: TmdbApi,
    private val store: TokenStore,
    private val profiles: ProfileRepository,
) : CalendarRepository {

    /**
     * One limit for all of the calendar's TMDB work: show details and seasons, discover pages and the
     * neighbour-month prefetch together never exceed four requests in flight (AGENTS.md).
     */
    private val permits = Semaphore(TMDB_CONCURRENCY)

    override fun showEpisodes(showIds: List<String>, start: CalendarDate, end: CalendarDate): Flow<ShowCalendarEpisodes> =
        channelFlow {
            showIds.distinct().forEach { showId ->
                launch {
                    val result = permits.withPermit {
                        withTimeoutOrNull(SHOW_TIMEOUT_MS) { loadShow(showId, start, end) }
                    }
                    if (result != null) send(result)
                }
            }
        }
            // Network calls suspend anyway; this keeps date parsing and mapping off the main thread.
            .flowOn(Dispatchers.Default)

    private suspend fun loadShow(showId: String, start: CalendarDate, end: CalendarDate): ShowCalendarEpisodes? {
        val airing = try {
            catalog.getShowAiring(showId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Airing failed for $showId: ${e.message}")
            null
        } ?: return null
        val seasons = CalendarRules.seasonsToLoad(airing, start, end)
        val episodes = seasons.flatMap { season -> seasonEpisodes(airing, season) }
        return ShowCalendarEpisodes(
            showId = showId,
            episodes = episodes.mapNotNull { (episode, last) ->
                val date = CalendarDate.parse(episode.airDate) ?: return@mapNotNull null
                if (date < start || date > end) return@mapNotNull null
                CalendarEpisode(show = airing.item, episode = episode, date = date, lastInSeason = last)
            },
        )
    }

    /** Episodes of one season paired with "is the season finale". Falls back to the airing episodes. */
    private suspend fun seasonEpisodes(airing: ShowAiring, season: Int): List<Pair<Episode, Boolean>> {
        val list = try {
            catalog.getEpisodes(airing.item, season)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isEmpty()) {
            return listOfNotNull(airing.latestEpisode, airing.nextEpisode)
                .filter { it.season == season }
                .distinctBy { it.number }
                .map { it to false }
        }
        val last = list.maxOf { it.number }
        return list.filter { it.season == season && it.number > 0 }.map { it to (it.number == last) }
    }

    private class CachedReleases(val at: Long, val value: List<CalendarRelease>)

    private val releaseCache = ConcurrentHashMap<String, CachedReleases>()
    private val releaseLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun releases(start: CalendarDate, end: CalendarDate): List<CalendarRelease> {
        if (start > end) return emptyList()
        val key = store.tmdbKey.orEmpty()
        if (key.isBlank()) return emptyList()
        val kids = profiles.active.value.kids
        val cacheKey = "${Lang.current.code}:$kids:$start:$end"
        // Off the main thread: up to ten pages of DTOs are mapped here.
        return withContext(Dispatchers.Default) { cachedReleases(key, kids, cacheKey, start, end) }
    }

    private suspend fun cachedReleases(key: String, kids: Boolean, cacheKey: String, start: CalendarDate, end: CalendarDate): List<CalendarRelease> {
        return releaseLocks.getOrPut(cacheKey) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            releaseCache[cacheKey]?.takeIf { now - it.at < RELEASE_CACHE_MS }?.let { return@withLock it.value }
            val (movies, series) = coroutineScope {
                val movies = async { movies(key, kids, start, end) }
                val series = async { series(key, kids, start, end) }
                movies.await() to series.await()
            }
            val loaded = movies.items + series.items
            // A failed type or page is shown but not cached, so the next visit retries it instead
            // of serving an incomplete month for six hours.
            if (movies.complete && series.complete) {
                releaseCache.entries.removeAll { now - it.value.at >= RELEASE_CACHE_MS }
                releaseCache[cacheKey] = CachedReleases(now, loaded)
            }
            loaded
        }
    }

    /** Items loaded so far; [complete] is false when any request failed. */
    private class Loaded<T>(val items: List<T>, val complete: Boolean)

    private suspend fun movies(key: String, kids: Boolean, start: CalendarDate, end: CalendarDate): Loaded<CalendarRelease> {
        val params = buildMap {
            put("primary_release_date.gte", start.iso())
            put("primary_release_date.lte", end.iso())
            put("vote_count.gte", MIN_MOVIE_VOTES.toString())
            put("sort_by", "vote_count.desc")
            put("include_adult", "false")
            if (kids) {
                put("with_genres", "16|10751")
                put("certification_country", "US")
                put("certification.lte", "PG")
            }
        }
        return pages { page -> tmdb.discoverMovieQuery(key, params + ("page" to page.toString())) }
            .map { dto ->
                val date = CalendarDate.parse(dto.release_date) ?: return@map null
                CalendarRelease(
                    dto.toCatalogItem(),
                    date,
                    roundRating(CalendarRules.adjustedReleaseRating(dto.vote_average, dto.vote_count)),
                    isNewSeries = false,
                )
            }
    }

    // Series premieres only. TMDB discover cannot tell when a later season of an arbitrary show
    // starts without one request per show; new seasons of the user's own shows appear as episodes.
    private suspend fun series(key: String, kids: Boolean, start: CalendarDate, end: CalendarDate): Loaded<CalendarRelease> {
        val params = buildMap {
            put("first_air_date.gte", start.iso())
            put("first_air_date.lte", end.iso())
            put("vote_count.gte", MIN_SERIES_VOTES.toString())
            put("sort_by", "vote_count.desc")
            put("include_adult", "false")
            if (kids) put("with_genres", "10762")
        }
        return pages { page -> tmdb.discoverTvQuery(key, params + ("page" to page.toString())) }
            .map { dto ->
                val date = CalendarDate.parse(dto.first_air_date) ?: return@map null
                CalendarRelease(
                    dto.toCatalogItem(),
                    date,
                    roundRating(CalendarRules.adjustedReleaseRating(dto.vote_average, dto.vote_count)),
                    isNewSeries = true,
                )
            }
    }

    /**
     * First page, then the remaining pages concurrently. Sorted by vote count, so a page cap drops
     * releases with the least rating evidence; [CalendarRules] applies the final adjusted-rating
     * order. Failures are reported in [Loaded.complete]; cancellation propagates.
     */
    private suspend fun <T> pages(load: suspend (Int) -> TmdbPaginated<T>): Loaded<T> {
        val first = attempt { permits.withPermit { load(1) } } ?: return Loaded(emptyList(), complete = false)
        val last = min(first.total_pages, MAX_PAGES)
        if (last <= 1) return Loaded(first.results, complete = true)
        val rest = (2..last).toList().mapConcurrent(concurrency = 2) { page -> attempt { permits.withPermit { load(page) }.results } }
        return Loaded(first.results + rest.filterNotNull().flatten(), complete = rest.none { it == null })
    }

    private inline fun <T, R> Loaded<T>.map(transform: (T) -> R?): Loaded<R> = Loaded(items.mapNotNull(transform), complete)

    /** The result, or null on failure. Never swallows cancellation. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Discover failed: ${e.message}")
        null
    }

    private fun roundRating(value: Double): Float = (value * 10).roundToInt() / 10f

    private companion object {
        const val TAG = "Calendar"
        const val TMDB_CONCURRENCY = 4
        const val SHOW_TIMEOUT_MS = 8_000L
        const val RELEASE_CACHE_MS = 6 * 60 * 60 * 1000L
        const val MAX_PAGES = 5

        // Exclude releases with almost no rating evidence. The adjusted rating handles the
        // remaining small samples, at the cost of very recent releases that have few votes yet.
        const val MIN_MOVIE_VOTES = 50
        const val MIN_SERIES_VOTES = 20
    }
}
