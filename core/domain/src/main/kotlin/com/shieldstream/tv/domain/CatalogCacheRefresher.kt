package com.shieldstream.tv.domain

/**
 * Refreshes persisted catalog metadata in the background. Playback triggers it, so the network
 * work happens while the user watches rather than while they browse.
 */
interface CatalogCacheRefresher {
    suspend fun refreshStaleItems()
}
