package com.shieldstream.tv.details

import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SimilarTitlesViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val origin = item("550", "Origin")
    private val recommendation = item("551", "Similar")

    @Test
    fun `content is retained when returning to the same origin`() = runTest {
        val catalog = mockk<CatalogRepository>()
        coEvery { catalog.getItem(MediaType.MOVIE, origin.id) } returns origin
        coEvery { catalog.getRecommendations(origin) } returns listOf(recommendation)
        val myList = mockk<MyListRepository>(relaxed = true) {
            every { entries } returns MutableStateFlow(emptyList())
            every { contains(MediaType.MOVIE, recommendation.id) } returns false
        }
        val viewModel = SimilarTitlesViewModel(catalog, myList)

        viewModel.load(MediaType.MOVIE, origin.id)
        viewModel.load(MediaType.MOVIE, origin.id)

        val content = viewModel.uiState.value as SimilarTitlesUiState.Content
        assertEquals(origin, content.origin)
        assertEquals(listOf(recommendation), content.items)
        coVerify(exactly = 1) { catalog.getItem(MediaType.MOVIE, origin.id) }
        coVerify(exactly = 1) { catalog.getRecommendations(origin) }

        viewModel.toggleMyList(recommendation)
        verify { myList.add(MediaType.MOVIE, recommendation.id) }
    }

    private fun item(id: String, title: String) = CatalogItem(
        id = id,
        imdbId = null,
        type = MediaType.MOVIE,
        title = title,
        overview = "",
        posterUrl = "/$id.jpg",
        backdropUrl = null,
        year = 2024,
        rating = 8.0f,
    )
}
