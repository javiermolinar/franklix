package com.shieldstream.tv.details

import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.PersonDetails
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PersonViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `filmography uses the shared My List repository`() = runTest {
        val item = CatalogItem(
            id = "550",
            imdbId = null,
            type = MediaType.MOVIE,
            title = "Movie",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            year = 1999,
            rating = 8.4f,
        )
        val catalog = mockk<CatalogRepository>()
        coEvery { catalog.getPerson(31) } returns PersonDetails(
            id = 31,
            name = "Actor",
            biography = "",
            profileUrl = null,
            filmography = listOf(item),
        )
        val myList = mockk<MyListRepository>(relaxed = true)
        every { myList.entries } returns MutableStateFlow(emptyList())
        every { myList.contains(MediaType.MOVIE, "550") } returns false
        val viewModel = PersonViewModel(catalog, myList)

        viewModel.load(31)

        val content = viewModel.uiState.value as PersonUiState.Content
        assertEquals(listOf(item), content.person.filmography)
        viewModel.toggleMyList(item)
        verify(exactly = 1) { myList.add(MediaType.MOVIE, "550") }
    }
}
