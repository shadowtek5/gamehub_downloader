package com.gamehub.downloader.data

import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.EmulatorCatalog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** GameHub's emulator catalog per connection, kept in memory for an hour. */
class EmulatorCatalogs(private val app: GameHubApp) {
    private data class Entry(val catalog: EmulatorCatalog?, val at: Long)

    private val cache = HashMap<String, Entry>()
    private val lock = Mutex()

    /** The catalog for [conn], or null when the server is older than 0.1.11 or unreachable. */
    suspend fun get(conn: Connection): EmulatorCatalog? = lock.withLock {
        val key = conn.id + "|" + conn.baseUrl
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.catalog }
        val fresh = runCatching { app.api(conn).emulators() }
        // Keep the last good copy when the server can't be reached right now.
        val catalog = fresh.getOrElse { return cache[key]?.catalog }
        cache[key] = Entry(catalog, System.currentTimeMillis())
        catalog
    }

    companion object {
        private const val TTL_MS = 60 * 60 * 1000L
    }
}
