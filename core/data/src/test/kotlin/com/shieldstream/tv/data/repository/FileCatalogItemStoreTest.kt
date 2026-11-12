package com.shieldstream.tv.data.repository

import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileCatalogItemStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var previousLanguage: AppLanguage
    private lateinit var file: File
    private var clock = 1_000L

    @Before
    fun setUp() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.ES
        file = File(folder.root, "catalog_items.json")
    }

    @After
    fun tearDown() {
        Lang.current = previousLanguage
    }

    private fun store(maxItems: Int = 800) = FileCatalogItemStore(file, writeDelayMs = 0L, maxItems = maxItems, now = { clock })

    private fun item(id: String, type: MediaType = MediaType.MOVIE, title: String = "Title $id") = CatalogItem(
        id = id,
        imdbId = "tt$id",
        type = type,
        title = title,
        overview = "Overview",
        posterUrl = "https://image.test/$id.jpg",
        backdropUrl = null,
        year = 2024,
        rating = 7.5f,
        genres = listOf("Drama"),
        releaseDate = "2024-05-01",
    )

    @Test
    fun `items survive a restart with their fetch time`() = runBlocking {
        val first = store()
        first.put(item("1"))
        first.put(item("2", MediaType.TV))
        first.flush()

        val restarted = store()
        assertEquals(item("1"), restarted.get(MediaType.MOVIE, "1")?.item)
        assertEquals(item("2", MediaType.TV), restarted.get(MediaType.TV, "2")?.item)
        assertEquals(1_000L, restarted.get(MediaType.MOVIE, "1")?.savedAt)
        assertNull(restarted.get(MediaType.TV, "1"))
    }

    @Test
    fun `entries are kept per interface language`() = runBlocking {
        val store = store()
        store.put(item("1", title = "Título"))
        Lang.current = AppLanguage.EN
        store.put(item("1", title = "Title"))
        store.flush()

        val restarted = store()
        assertEquals("Title", restarted.get(MediaType.MOVIE, "1")?.item?.title)
        Lang.current = AppLanguage.ES
        assertEquals("Título", restarted.get(MediaType.MOVIE, "1")?.item?.title)
        assertEquals(1, restarted.all().size)
    }

    @Test
    fun `oldest entries are dropped beyond the cap`() = runBlocking {
        val store = store(maxItems = 2)
        listOf("1", "2", "3").forEach { id ->
            clock += 1
            store.put(item(id))
        }
        store.flush()

        val restarted = store(maxItems = 2)
        assertNull(restarted.get(MediaType.MOVIE, "1"))
        assertEquals(listOf("2", "3"), restarted.all().map { it.item.id }.sorted())
    }

    @Test
    fun `unreadable file starts empty`() = runBlocking {
        file.writeText("not json")
        assertEquals(emptyList<CachedCatalogItem>(), store().all())
    }
}
