package com.shieldstream.tv.data.repository

import com.shieldstream.tv.data.security.TokenStore
import com.shieldstream.tv.domain.IthilAccessRepository
import com.shieldstream.tv.domain.IthilLibraryRepository
import com.shieldstream.tv.domain.ProfileRepository
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.Profile
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.api.TorrentioApi
import com.shieldstream.tv.network.dto.TmdbExternalIdsDto
import com.shieldstream.tv.network.dto.TmdbTvDetailDto
import com.shieldstream.tv.strings.Texts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogRepositoryItemTest {
    private val tmdb = mockk<TmdbApi>()
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
    )

    @Test
    fun `show item and airing share one details request`() = runTest {
        coEvery { tmdb.tvDetails(42, any(), append = "external_ids", imageLanguages = any()) } returns TmdbTvDetailDto(
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
    }
}
