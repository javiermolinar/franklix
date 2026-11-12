package com.shieldstream.tv.data.repository

import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.EpisodeTrackingRepository
import com.shieldstream.tv.domain.WatchProgressRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.Season
import com.shieldstream.tv.model.ShowAiring
import com.shieldstream.tv.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EpisodeReleaseRepositoryImplTest {

    @Test
    fun `failed season download does not count as a fully watched season`() = runTest {
        val item = CatalogItem(
            id = "42",
            imdbId = "tt42",
            type = MediaType.TV,
            title = "Show",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            year = 2020,
            rating = null,
        )
        val watchedEpisode = Episode(1, 1, "Watched", "", null, 45, "2020-01-01")
        val catalog = mockk<CatalogRepository>()
        coEvery { catalog.getShowAiring("42") } returns ShowAiring(
            item = item,
            seasons = listOf(Season(1, "Season 1", 1), Season(2, "Season 2", 1)),
        )
        coEvery { catalog.getEpisodes(item, 1) } returns listOf(watchedEpisode)
        coEvery { catalog.getEpisodes(item, 2) } throws IOException("season unavailable")
        val watchedKey = WatchProgress.episodeKey("42", 1, 1)
        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.progress } returns MutableStateFlow(
            mapOf(watchedKey to WatchProgress(positionMs = 1, durationMs = 1, updatedAt = 1)),
        )

        val repository = EpisodeReleaseRepositoryImpl(
            catalog = catalog,
            watchProgress = watchProgress,
            tracking = mockk<EpisodeTrackingRepository>(relaxed = true),
        )

        assertFalse(repository.isComplete("42"))
    }

    private val show = CatalogItem(
        id = "7",
        imdbId = "tt7",
        type = MediaType.TV,
        title = "Show",
        overview = "",
        posterUrl = null,
        backdropUrl = null,
        year = 2020,
        rating = null,
    )

    private fun repository(catalog: CatalogRepository, watchedKeys: Set<String>) = EpisodeReleaseRepositoryImpl(
        catalog = catalog,
        watchProgress = mockk<WatchProgressRepository> {
            every { progress } returns MutableStateFlow(
                watchedKeys.associateWith { WatchProgress(positionMs = 1, durationMs = 1, updatedAt = 1) },
            )
        },
        tracking = mockk<EpisodeTrackingRepository>(relaxed = true),
    )

    @Test
    fun `unwatched latest episode is not complete without downloading seasons`() = runTest {
        val latest = Episode(2, 3, "Latest", "", null, 45, "2020-03-01")
        val catalog = mockk<CatalogRepository>()
        coEvery { catalog.getShowAiring("7") } returns ShowAiring(
            item = show,
            latestEpisode = latest,
            seasons = listOf(Season(1, "Season 1", 2), Season(2, "Season 2", 3)),
        )

        assertFalse(repository(catalog, setOf(WatchProgress.episodeKey("7", 1, 1))).isComplete("7"))
        coVerify(exactly = 0) { catalog.getEpisodes(any(), any()) }
    }

    @Test
    fun `watched latest episode still checks every aired episode`() = runTest {
        val first = Episode(1, 1, "First", "", null, 45, "2020-01-01")
        val latest = Episode(1, 2, "Latest", "", null, 45, "2020-01-08")
        val catalog = mockk<CatalogRepository>()
        coEvery { catalog.getShowAiring("7") } returns ShowAiring(
            item = show,
            latestEpisode = latest,
            seasons = listOf(Season(1, "Season 1", 2)),
        )
        coEvery { catalog.getEpisodes(show, 1) } returns listOf(first, latest)
        val latestKey = WatchProgress.episodeKey("7", 1, 2)
        val firstKey = WatchProgress.episodeKey("7", 1, 1)

        assertFalse(repository(catalog, setOf(latestKey)).isComplete("7"))
        assertTrue(repository(catalog, setOf(latestKey, firstKey)).isComplete("7"))
    }
}
