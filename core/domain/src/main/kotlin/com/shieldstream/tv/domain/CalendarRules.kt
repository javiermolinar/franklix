package com.shieldstream.tv.domain

import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CalendarEpisode
import com.shieldstream.tv.model.CalendarMonth
import com.shieldstream.tv.model.CalendarRelease
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.ShowAiring
import kotlinx.coroutines.flow.Flow

enum class MinRating(val value: Float?) {
    ANY(null),
    SEVEN(7f),
    EIGHT(8f),
    NINE(9f),
}

/** The rating filter applies to releases only, never to the user's own episodes. */
data class CalendarFilters(
    val minRating: MinRating = MinRating.EIGHT,
)

enum class CalendarDot { EPISODE, WATCHED, RELEASE }

enum class CalendarTag { PREMIERE, FINALE, NEW, NEW_SERIES }

/**
 * One show's episodes on one day, shown as a single card: a season dropped at once is one show,
 * not eight identical posters.
 */
data class CalendarShowEpisodes(val show: CatalogItem, val episodes: List<CalendarEpisode>) {
    init {
        require(episodes.isNotEmpty())
    }

    val watched: Boolean get() = episodes.all { it.watched }

    /** The episode OK opens: the first unwatched one, or the first when all are watched. */
    val target: CalendarEpisode get() = episodes.firstOrNull { !it.watched } ?: episodes.first()
}

/** What one day shows for the current filters, already sorted for display. */
data class CalendarDay(
    val episodes: List<CalendarEpisode> = emptyList(),
    val releases: List<CalendarRelease> = emptyList(),
) {
    /** [episodes] grouped by show, in the same order. */
    val shows: List<CalendarShowEpisodes> = episodes.groupBy { it.show.id }.values.map { CalendarShowEpisodes(it.first().show, it) }

    val isEmpty: Boolean get() = episodes.isEmpty() && releases.isEmpty()

    /** Cards on the day: one per show plus one per release. */
    val size: Int get() = shows.size + releases.size

    companion object {
        val EMPTY = CalendarDay()
    }
}

data class ShowCalendarEpisodes(val showId: String, val episodes: List<CalendarEpisode>)

interface CalendarRepository {
    /**
     * Episodes of [showIds] that air between [start] and [end], inclusive. Emits one value per show
     * as soon as that show resolves, so the calendar fills in progressively. Shows that fail are
     * skipped. Watched state is left false; the caller applies it from watch progress.
     */
    fun showEpisodes(showIds: List<String>, start: CalendarDate, end: CalendarDate): Flow<ShowCalendarEpisodes>

    /** Rated movies and series premieres released between [start] and [end], at any rating. */
    suspend fun releases(start: CalendarDate, end: CalendarDate): List<CalendarRelease>
}

object CalendarRules {
    const val MAX_DOTS = 3

    /** Days of adjacent months included around a month so its grid edges have data. */
    private const val GRID_MARGIN_DAYS = 7L

    /** Episodes are kept loaded for the visible month and both neighbours, so month changes hit memory. */
    fun episodeMonths(month: CalendarMonth): List<CalendarMonth> = (-1..1).map { month.plusMonths(it) }

    /**
     * Shows grouped by the months of [months] they still need, in month order. Months already
     * loaded for a show are skipped; shows that need nothing are left out. After a one-month step
     * this is normally one group: every show, the one newly exposed month.
     */
    fun missingMonths(
        showIds: Collection<String>,
        months: List<CalendarMonth>,
        isLoaded: (showId: String, month: CalendarMonth) -> Boolean,
    ): Map<List<CalendarMonth>, List<String>> =
        showIds.groupBy { id -> months.filterNot { isLoaded(id, it) } }.filterKeys { it.isNotEmpty() }

    /** Releases load per month, capped at today: nothing is released in the future. Null when the range is empty. */
    fun releaseWindow(month: CalendarMonth, today: CalendarDate): Pair<CalendarDate, CalendarDate>? {
        val start = month.first.plusDays(-GRID_MARGIN_DAYS)
        val end = minOf(month.last.plusDays(GRID_MARGIN_DAYS), today)
        return if (start > end) null else start to end
    }

    fun day(
        date: CalendarDate,
        today: CalendarDate,
        episodes: List<CalendarEpisode>,
        releases: List<CalendarRelease>,
        filters: CalendarFilters,
    ): CalendarDay {
        val minimum = filters.minRating.value
        // Nothing is released in the future; upcoming episodes still show.
        val shownReleases = if (date > today) emptyList() else releases.filter { minimum == null || it.rating >= minimum }
        return CalendarDay(
            episodes = episodes.sortedWith(EPISODE_ORDER),
            releases = shownReleases.sortedWith(RELEASE_ORDER),
        )
    }

    /**
     * Up to three dots, one per show and one per release. A day with both kinds shows at most two
     * shows so at least one release fits; otherwise slots fill in display order, shows first. A
     * show counts as watched only when all of its episodes that day are.
     */
    fun dots(day: CalendarDay): List<CalendarDot> {
        val episodeDots = day.shows.map { if (it.watched) CalendarDot.WATCHED else CalendarDot.EPISODE }
        val releaseDots = day.releases.map { CalendarDot.RELEASE }
        val episodeSlots = if (releaseDots.isNotEmpty()) MAX_DOTS - 1 else MAX_DOTS
        val first = episodeDots.take(episodeSlots)
        return first + releaseDots.take(MAX_DOTS - first.size)
    }

    fun tag(episode: CalendarEpisode, today: CalendarDate): CalendarTag? = tag(CalendarShowEpisodes(episode.show, listOf(episode)), today)

    /** Premiere when the day includes an episode 1, finale when it includes the season's last one, new when it airs today. */
    fun tag(show: CalendarShowEpisodes, today: CalendarDate): CalendarTag? = when {
        show.episodes.any { it.episode.number == 1 } -> CalendarTag.PREMIERE
        show.episodes.any { it.lastInSeason } -> CalendarTag.FINALE
        show.episodes.first().date == today -> CalendarTag.NEW
        else -> null
    }

    /**
     * Season, first and last episode when a show's episodes on a day are one consecutive run of a
     * single season ("S2:E1–E8"); null for a single episode or a mixed set.
     */
    fun episodeRange(show: CalendarShowEpisodes): Triple<Int, Int, Int>? {
        if (show.episodes.size < 2) return null
        val season = show.episodes.first().episode.season
        if (show.episodes.any { it.episode.season != season }) return null
        val numbers = show.episodes.map { it.episode.number }.sorted()
        if (numbers.zipWithNext().any { (a, b) -> b != a + 1 }) return null
        return Triple(season, numbers.first(), numbers.last())
    }

    fun tag(release: CalendarRelease): CalendarTag? = if (release.isNewSeries) CalendarTag.NEW_SERIES else null

    /**
     * Seasons that can contain episodes airing between [start] and [end]. Shows whose last aired
     * episode is before the window and whose next one is after it need no season request at all.
     */
    fun seasonsToLoad(airing: ShowAiring, start: CalendarDate, end: CalendarDate): List<Int> {
        val latest = airing.latestEpisode
        val next = airing.nextEpisode
        val latestDate = CalendarDate.parse(latest?.airDate)
        val nextDate = CalendarDate.parse(next?.airDate)
        if (latest == null && next == null) return emptyList()
        val latestBefore = latest == null || (latestDate != null && latestDate < start)
        val nextAfter = next == null || nextDate == null || nextDate > end
        if (latestBefore && nextAfter) return emptyList()

        val seasons = linkedSetOf<Int>()
        if (next != null && next.season > 0 && nextDate != null && nextDate <= end) seasons += next.season
        val maxSeason = maxOf(latest?.season ?: 0, next?.season ?: 0)
        val known = airing.seasons.filter { it.number in 1..maxSeason }.sortedBy { it.number }
        if (latest != null && latest.season > 0 && (latestDate == null || latestDate >= start)) {
            // A season that premiered after the window cannot contribute to it.
            val premiere = CalendarDate.parse(known.firstOrNull { it.number == latest.season }?.airDate)
            if (premiere == null || premiere <= end) seasons += latest.season
        }
        known.forEachIndexed { index, season ->
            val premiere = CalendarDate.parse(season.airDate) ?: return@forEachIndexed
            val following = known.getOrNull(index + 1)?.let { CalendarDate.parse(it.airDate) }
            // >= : a season can end on the day the next one starts (a same-day hand-off).
            if (premiere <= end && (following == null || following >= start)) seasons += season.number
        }
        return seasons.sortedDescending().take(MAX_SEASONS)
    }

    private const val MAX_SEASONS = 3

    private val EPISODE_ORDER = compareBy<CalendarEpisode, String>(String.CASE_INSENSITIVE_ORDER) { it.show.title }
        .thenBy { it.show.id }
        .thenBy { it.episode.season }
        .thenBy { it.episode.number }

    private val RELEASE_ORDER = compareByDescending<CalendarRelease> { it.rating }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.item.title }
}
