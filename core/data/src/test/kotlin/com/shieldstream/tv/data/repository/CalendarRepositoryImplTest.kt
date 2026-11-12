package com.shieldstream.tv.data.repository

import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.ProfileRepository
import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.Profile
import com.shieldstream.tv.model.ShowAiring
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.dto.TmdbMovieDto
import com.shieldstream.tv.network.dto.TmdbPaginated
import com.shieldstream.tv.network.dto.TmdbTvDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class CalendarRepositoryImplTest {
    private val catalog = mockk<CatalogRepository>()
    private val tmdb = mockk<TmdbApi>()
    private val store = mockk<TokenStore> { every { tmdbKey } returns "key" }
    private val profiles = mockk<ProfileRepository> {
        every { active } returns MutableStateFlow(Profile(id = "p", name = "P", avatar = 0))
    }
    private val repository = CalendarRepositoryImpl(catalog, tmdb, store, profiles)

    private val start = CalendarDate(2026, 10, 1)
    private val end = CalendarDate(2026, 11, 30)

    private fun show(id: String) = CatalogItem(id, null, MediaType.TV, "Show $id", "", "https://image/p$id.jpg", null, null, null)

    @Test
    fun `episodes in the window are emitted per show with the finale marked`() = runTest {
        val item = show("1")
        coEvery { catalog.getShowAiring("1") } returns ShowAiring(
            item,
            latestEpisode = Episode(2, 3, "", "", null, 45, "2026-11-05"),
            nextEpisode = Episode(2, 4, "", "", null, 45, "2026-11-12"),
        )
        coEvery { catalog.getEpisodes(item, 2) } returns listOf(
            Episode(2, 1, "", "", null, 45, "2026-09-20"),
            Episode(2, 3, "", "", null, 45, "2026-11-05"),
            Episode(2, 4, "", "", null, 45, "2026-11-12"),
        )
        // An ended show: no season request at all.
        coEvery { catalog.getShowAiring("2") } returns ShowAiring(show("2"), latestEpisode = Episode(1, 8, "", "", null, 45, "2019-01-01"))
        coEvery { catalog.getShowAiring("3") } throws IOException("offline")

        val results = repository.showEpisodes(listOf("1", "2", "3"), start, end).toList().associateBy { it.showId }

        val episodes = results.getValue("1").episodes
        assertEquals(listOf(3, 4), episodes.map { it.episode.number })
        assertEquals(listOf(false, true), episodes.map { it.lastInSeason })
        assertEquals("https://image/p1.jpg", episodes.first().show.posterUrl)
        assertTrue(results.getValue("2").episodes.isEmpty())
        assertTrue("3" !in results)
        coVerify(exactly = 0) { catalog.getEpisodes(show("2"), any()) }
    }

    @Test
    fun `releases combine movies and series, round ratings and are cached`() = runTest {
        coEvery { tmdb.discoverMovieQuery("key", any()) } returns TmdbPaginated(
            results = listOf(TmdbMovieDto(id = 10, title = "Film", release_date = "2026-11-03", vote_average = 7.95)),
        )
        coEvery { tmdb.discoverTvQuery("key", any()) } returns TmdbPaginated(
            results = listOf(
                TmdbTvDto(id = 20, name = "Series", first_air_date = "2026-11-04", vote_average = 8.44),
                TmdbTvDto(id = 21, name = "Undated", first_air_date = null, vote_average = 9.0),
            ),
        )

        val first = repository.releases(start, end)
        val second = repository.releases(start, end)

        assertEquals(listOf("Film" to 8.0f, "Series" to 8.4f), first.map { it.item.title to it.rating })
        assertEquals(listOf(false, true), first.map { it.isNewSeries })
        assertEquals(first, second)
        coVerify(exactly = 1) { tmdb.discoverMovieQuery("key", any()) }
        coVerify(exactly = 1) {
            tmdb.discoverTvQuery(
                "key",
                match { it["first_air_date.gte"] == "2026-10-01" && it["first_air_date.lte"] == "2026-11-30" && it["page"] == "1" },
            )
        }
    }

    @Test
    fun `remaining pages load after the first`() = runTest {
        coEvery { tmdb.discoverMovieQuery("key", match { it["page"] == "1" }) } returns TmdbPaginated(
            results = listOf(TmdbMovieDto(id = 1, title = "A", release_date = "2026-11-01", vote_average = 9.0)),
            total_pages = 2,
        )
        coEvery { tmdb.discoverMovieQuery("key", match { it["page"] == "2" }) } returns TmdbPaginated(
            results = listOf(TmdbMovieDto(id = 2, title = "B", release_date = "2026-11-02", vote_average = 7.0)),
            total_pages = 2,
        )
        coEvery { tmdb.discoverTvQuery("key", any()) } returns TmdbPaginated()

        assertEquals(listOf("A", "B"), repository.releases(start, end).map { it.item.title })
    }

    @Test
    fun `a month with a failed request is shown but not cached`() = runTest {
        coEvery { tmdb.discoverMovieQuery("key", any()) } returns TmdbPaginated(
            results = listOf(TmdbMovieDto(id = 1, title = "A", release_date = "2026-11-01", vote_average = 9.0)),
        )
        coEvery { tmdb.discoverTvQuery("key", any()) } throws IOException("offline")

        assertEquals(listOf("A"), repository.releases(start, end).map { it.item.title })
        repository.releases(start, end)

        coVerify(exactly = 2) { tmdb.discoverMovieQuery("key", any()) }
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not swallowed`() = runTest {
        coEvery { tmdb.discoverMovieQuery("key", any()) } throws CancellationException("left the month")
        coEvery { tmdb.discoverTvQuery("key", any()) } returns TmdbPaginated()

        repository.releases(start, end)
    }

    @Test
    fun `shows and release pages share one limit of four requests in flight`() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        suspend fun <T> request(value: T): T {
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(30)
            inFlight.decrementAndGet()
            return value
        }
        coEvery { catalog.getShowAiring(any()) } coAnswers { request(ShowAiring(show(firstArg()), latestEpisode = Episode(1, 1, "", "", null, 45, "2019-01-01"))) }
        coEvery { tmdb.discoverMovieQuery("key", any()) } coAnswers { request(TmdbPaginated<TmdbMovieDto>(total_pages = 5)) }
        coEvery { tmdb.discoverTvQuery("key", any()) } coAnswers { request(TmdbPaginated<TmdbTvDto>(total_pages = 5)) }

        coroutineScope {
            launch { repository.showEpisodes((1..12).map(Int::toString), start, end).toList() }
            launch { repository.releases(start, end) }
            launch { repository.releases(CalendarDate(2026, 8, 1), CalendarDate(2026, 9, 30)) }
        }

        assertTrue("peak ${peak.get()}", peak.get() in 2..4)
    }
}
