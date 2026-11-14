package com.shieldstream.tv.data.repository

import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.IthilAccessRepository
import com.shieldstream.tv.domain.IthilLibraryRepository
import com.shieldstream.tv.domain.ProfileRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.Profile
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.api.TorrentioApi
import com.shieldstream.tv.network.dto.TmdbExternalIdsDto
import com.shieldstream.tv.network.dto.TmdbMovieDetailDto
import com.shieldstream.tv.network.dto.TmdbMovieDto
import com.shieldstream.tv.network.dto.TmdbPaginated
import com.shieldstream.tv.network.dto.TmdbTvDetailDto
import com.shieldstream.tv.network.dto.TmdbTvDto
import com.shieldstream.tv.strings.Texts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogRepositoryItemTest {
    private val tmdb = mockk<TmdbApi>()
    private val itemStore = FakeCatalogItemStore(now = System.currentTimeMillis())
    private val repository = CatalogRepositoryImpl(
        tmdb = tmdb,
        torrentio = mockk<TorrentioApi>(),
        extras = mockk<HomeExtrasSource>(),
        store = mockk<TokenStore> { every { tmdbKey } returns "synthetic-test-key" },
        settings = mockk<SettingsRepository>(),
        profiles = mockk<ProfileRepository> {
            every { active } returns MutableStateFlow(Profile(id = "test", name = "Test", avatar = 0))
        },
        texts = mockk<Texts>(relaxed = true),
        ithil = mockk<IthilLibraryRepository>(),
        ithilAccess = mockk<IthilAccessRepository>(),
        itemStore = itemStore,
    )

    private fun movie(id: String, title: String) = CatalogItem(
        id = id,
        imdbId = "tt$id",
        type = MediaType.MOVIE,
        title = title,
        overview = "",
        posterUrl = null,
        backdropUrl = null,
        year = 2024,
        rating = null,
    )

    @Test
    fun `show item and airing share one details request`() = runTest {
        coEvery { tmdb.tvDetails(42, any(), any(), any()) } returns TmdbTvDetailDto(
            id = 42,
            name = "Show",
            first_air_date = "2020-05-01",
            external_ids = TmdbExternalIdsDto(imdb_id = "tt42"),
        )

        val item = repository.getItem(MediaType.TV, "42")
        val airing = repository.getShowAiring("42")

        assertEquals("Show", item?.title)
        assertEquals("tt42", item?.imdbId)
        assertEquals(2020, item?.year)
        assertEquals(item, airing?.item)
        coVerify(exactly = 1) { tmdb.tvDetails(42, any(), any(), any()) }
        assertEquals(item, itemStore.items["TV_42"]?.item)
    }

    @Test
    fun `persisted item is served without a request`() = runTest {
        itemStore.put(movie("7", "Cached"))

        assertEquals("Cached", repository.getItem(MediaType.MOVIE, "7")?.title)
        assertEquals("Cached", repository.cachedItem(MediaType.MOVIE, "7")?.title)
        coVerify(exactly = 0) { tmdb.movieDetails(any(), any(), any(), any()) }
    }

    @Test
    fun `recommendations append similar titles without duplicates and are cached`() = runTest {
        coEvery { tmdb.movieRecommendations(9, any()) } returns TmdbPaginated(
            results = listOf(
                TmdbMovieDto(id = 1, title = "First", poster_path = "/1.jpg"),
                TmdbMovieDto(id = 2, title = "Second", poster_path = "/2.jpg"),
            ),
        )
        coEvery { tmdb.movieSimilar(9, any()) } returns TmdbPaginated(
            results = listOf(
                TmdbMovieDto(id = 2, title = "Duplicate", poster_path = "/2.jpg"),
                TmdbMovieDto(id = 3, title = "Third", poster_path = "/3.jpg"),
            ),
        )
        val origin = movie("9", "Origin")

        val first = repository.getRecommendations(origin)
        val second = repository.getRecommendations(origin)

        assertEquals(listOf("First", "Second", "Third"), first.map { it.title })
        assertEquals(first, second)
        coVerify(exactly = 1) { tmdb.movieRecommendations(9, any()) }
        coVerify(exactly = 1) { tmdb.movieSimilar(9, any()) }
    }

    @Test
    fun `tv recommendations keep enough results without requesting similar titles`() = runTest {
        coEvery { tmdb.tvRecommendations(42, any()) } returns TmdbPaginated(
            results = List(10) { index -> TmdbTvDto(id = index + 1, name = "Show $index", poster_path = "/$index.jpg") },
        )
        val origin = CatalogItem("42", null, MediaType.TV, "Origin", "", null, null, null, null)

        val items = repository.getRecommendations(origin)

        assertEquals(10, items.size)
        coVerify(exactly = 1) { tmdb.tvRecommendations(42, any()) }
        coVerify(exactly = 0) { tmdb.tvSimilar(any(), any()) }
    }

    @Test
    fun `cached lookup never goes to the network`() = runTest {
        assertNull(repository.cachedItem(MediaType.MOVIE, "8"))
        assertNull(repository.cachedItem(MediaType.TV, "8"))
    }

    @Test
    fun `stale items are refetched and fresh ones are left alone`() = runTest {
        val now = System.currentTimeMillis()
        itemStore.now = now - 2 * 24 * 60 * 60 * 1000L
        itemStore.put(movie("1", "Old title"))
        itemStore.now = now
        itemStore.put(movie("2", "Fresh"))
        coEvery { tmdb.movieDetails(1, any(), any(), any()) } returns TmdbMovieDetailDto(id = 1, title = "New title", imdb_id = "tt1")

        repository.refreshStaleItems()

        assertEquals("New title", itemStore.items["MOVIE_1"]?.item?.title)
        assertEquals(now, itemStore.items["MOVIE_1"]?.savedAt)
        coVerify(exactly = 1) { tmdb.movieDetails(1, any(), any(), any()) }
        coVerify(exactly = 0) { tmdb.movieDetails(2, any(), any(), any()) }
    }
}
