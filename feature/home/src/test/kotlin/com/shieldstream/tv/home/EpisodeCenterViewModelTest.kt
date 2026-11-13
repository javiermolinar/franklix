package com.shieldstream.tv.home

import com.shieldstream.tv.domain.EpisodeReleaseRepository
import com.shieldstream.tv.domain.EpisodeTrackingRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.domain.TraktRepository
import com.shieldstream.tv.domain.WatchProgressRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.ShowEpisodeStatus
import com.shieldstream.tv.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeCenterViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val show = CatalogItem("42", null, MediaType.TV, "Show", "", null, null, null, null)
    private val episode = Episode(2, 3, "Episode", "", null, 45, "2026-11-12")
    private val key = WatchProgress.episodeKey(show.id, episode.season, episode.number)

    @Test
    fun `marking an episode watched synchronizes Trakt and local progress`() = runTest {
        val fixture = fixture()

        fixture.viewModel.toggleEpisodeWatched(show, episode)

        verify { fixture.trakt.setWatched(listOf(key), watched = true) }
        verify { fixture.watchProgress.save(key, 2_700_000L, 2_700_000L) }
    }

    @Test
    fun `marking an episode unwatched removes synchronized progress`() = runTest {
        val fixture = fixture(WatchProgress(2_700_000L, 2_700_000L, updatedAt = 1L))

        fixture.viewModel.toggleEpisodeWatched(show, episode)

        verify { fixture.trakt.setWatched(listOf(key), watched = false) }
        verify { fixture.watchProgress.removeRemote(listOf(key)) }
    }

    @Test
    fun `my list action adds or removes the show`() = runTest {
        val added = fixture(inMyList = false)
        added.viewModel.toggleMyList(show)
        verify { added.myList.add(MediaType.TV, "42") }

        val removed = fixture(inMyList = true)
        removed.viewModel.toggleMyList(show)
        verify { removed.myList.remove(MediaType.TV, "42") }
    }

    @Test
    fun `dismiss still applies to every available episode on the card`() = runTest {
        val fixture = fixture()
        val later = episode.copy(number = 4)

        fixture.viewModel.dismiss(ShowEpisodeStatus(show, availableEpisodes = listOf(episode, later)))

        verify {
            fixture.tracking.dismissEpisodes(
                listOf(key, WatchProgress.episodeKey(show.id, later.season, later.number)),
            )
        }
    }

    private fun fixture(progress: WatchProgress? = null, inMyList: Boolean = false): Fixture {
        val watchProgress = mockk<WatchProgressRepository>(relaxed = true) {
            every { this@mockk.progress } returns MutableStateFlow(emptyMap())
            every { this@mockk.get(this@EpisodeCenterViewModelTest.key) } returns progress
        }
        val myList = mockk<MyListRepository>(relaxed = true) {
            every { entries } returns MutableStateFlow(emptyList())
            every { contains(MediaType.TV, "42") } returns inMyList
        }
        val tracking = mockk<EpisodeTrackingRepository>(relaxed = true) {
            every { followedShowIds } returns MutableStateFlow(emptySet())
            every { dismissedEpisodeKeys } returns MutableStateFlow(emptySet())
        }
        val trakt = mockk<TraktRepository>(relaxed = true)
        return Fixture(
            EpisodeCenterViewModel(mockk<EpisodeReleaseRepository>(relaxed = true), tracking, watchProgress, myList, trakt),
            watchProgress,
            myList,
            tracking,
            trakt,
        )
    }

    private data class Fixture(
        val viewModel: EpisodeCenterViewModel,
        val watchProgress: WatchProgressRepository,
        val myList: MyListRepository,
        val tracking: EpisodeTrackingRepository,
        val trakt: TraktRepository,
    )
}
