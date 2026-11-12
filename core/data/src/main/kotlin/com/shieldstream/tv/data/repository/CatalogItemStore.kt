package com.shieldstream.tv.data.repository

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A catalog item from the persistent cache and when it was last fetched from TMDB. */
class CachedCatalogItem(val item: CatalogItem, val savedAt: Long)

/**
 * Persistent cache of catalog item metadata (title, artwork, year, rating). It lets Home and My
 * List show items on a cold start without a TMDB request per entry. Episode and airing data are not
 * stored; they stay network-fresh.
 */
interface CatalogItemStore {
    suspend fun get(type: MediaType, id: String): CachedCatalogItem?
    fun put(item: CatalogItem)
    suspend fun all(): List<CachedCatalogItem>
}

@Singleton
class FileCatalogItemStore internal constructor(
    private val file: File,
    private val writeDelayMs: Long,
    private val maxItems: Int,
    private val now: () -> Long,
) : CatalogItemStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        file = File(context.cacheDir, "catalog_items.json"),
        writeDelayMs = WRITE_DELAY_MS,
        maxItems = MAX_ITEMS,
        now = System::currentTimeMillis,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    // Read once, off the main thread, on first use. Entries from every interface language coexist;
    // lookups only return the current one because TMDB titles and overviews are localized.
    private val loaded: Deferred<Map<String, StoredCatalogItem>> =
        scope.async(start = CoroutineStart.LAZY) { read() }

    // Items fetched in this process. Puts land here synchronously and override the loaded file.
    private val fresh = ConcurrentHashMap<String, StoredCatalogItem>()

    private val writeLock = Any()
    private var pendingWrite: Job? = null
    private val fileMutex = Mutex()

    override suspend fun get(type: MediaType, id: String): CachedCatalogItem? {
        val key = keyOf(Lang.current.code, type, id)
        return (fresh[key] ?: loaded.await()[key])?.toCached()
    }

    override fun put(item: CatalogItem) {
        val stored = StoredCatalogItem.from(item, Lang.current.code, now())
        fresh[keyOf(stored.lang, item.type, item.id)] = stored
        scheduleWrite()
    }

    override suspend fun all(): List<CachedCatalogItem> {
        val lang = Lang.current.code
        return merged().values.filter { it.lang == lang }.mapNotNull { it.toCached() }
    }

    /** Writes pending changes now. For tests. */
    internal suspend fun flush() {
        synchronized(writeLock) { pendingWrite?.cancel(); pendingWrite = null }
        write()
    }

    private suspend fun merged(): Map<String, StoredCatalogItem> = loaded.await() + fresh

    // Writes at most once per delay window; the write captures every change made until it runs.
    private fun scheduleWrite() = synchronized(writeLock) {
        if (pendingWrite?.isActive == true) return@synchronized
        pendingWrite = scope.launch {
            delay(writeDelayMs)
            write()
        }
    }

    private fun read(): Map<String, StoredCatalogItem> {
        val map = HashMap<String, StoredCatalogItem>()
        if (!file.exists()) return map
        runCatching {
            val text = String(AtomicFile(file).readFully(), Charsets.UTF_8)
            val stored = json.decodeFromString(StoredCatalogItems.serializer(), text)
            if (stored.version == VERSION) stored.items.forEach { map[keyOf(it.lang, it.type, it.id)] = it }
        }.onFailure { Log.w(TAG, "Ignoring unreadable catalog cache: ${it.message}") }
        return map
    }

    private suspend fun write() = fileMutex.withLock {
        // Keep the most recently fetched entries; a dropped item is simply fetched again when needed.
        val kept = merged().values.sortedByDescending { it.savedAt }.take(maxItems)
        val bytes = json.encodeToString(StoredCatalogItems.serializer(), StoredCatalogItems(VERSION, kept))
            .toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        runCatching {
            file.parentFile?.mkdirs()
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        }.onFailure { Log.w(TAG, "Could not save catalog cache: ${it.message}") }
    }

    private companion object {
        const val TAG = "CatalogItemStore"
        const val VERSION = 1
        const val WRITE_DELAY_MS = 2_000L
        const val MAX_ITEMS = 800

        fun keyOf(lang: String, type: MediaType, id: String) = "$lang:${type.name}_$id"
        fun keyOf(lang: String, type: String, id: String) = "$lang:${type}_$id"
    }
}

@Serializable
internal data class StoredCatalogItems(val version: Int, val items: List<StoredCatalogItem>)

@Serializable
internal data class StoredCatalogItem(
    val lang: String,
    val type: String,
    val id: String,
    val savedAt: Long,
    val imdbId: String? = null,
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseDate: String? = null,
) {
    fun toCached(): CachedCatalogItem? {
        val mediaType = runCatching { MediaType.valueOf(type) }.getOrNull() ?: return null
        return CachedCatalogItem(
            item = CatalogItem(
                id = id,
                imdbId = imdbId,
                type = mediaType,
                title = title,
                overview = overview,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                year = year,
                rating = rating,
                genres = genres,
                releaseDate = releaseDate,
            ),
            savedAt = savedAt,
        )
    }

    companion object {
        fun from(item: CatalogItem, lang: String, savedAt: Long) = StoredCatalogItem(
            lang = lang,
            type = item.type.name,
            id = item.id,
            savedAt = savedAt,
            imdbId = item.imdbId,
            title = item.title,
            overview = item.overview,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            year = item.year,
            rating = item.rating,
            genres = item.genres,
            releaseDate = item.releaseDate,
        )
    }
}
