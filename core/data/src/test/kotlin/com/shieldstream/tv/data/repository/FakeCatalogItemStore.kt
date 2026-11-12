package com.shieldstream.tv.data.repository

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType

/** In-memory [CatalogItemStore] for repository tests. */
class FakeCatalogItemStore(var now: Long = 0L) : CatalogItemStore {
    val items = mutableMapOf<String, CachedCatalogItem>()

    override suspend fun get(type: MediaType, id: String): CachedCatalogItem? = items["${type.name}_$id"]

    override fun put(item: CatalogItem) {
        items[item.key] = CachedCatalogItem(item, now)
    }

    override suspend fun all(): List<CachedCatalogItem> = items.values.toList()
}
