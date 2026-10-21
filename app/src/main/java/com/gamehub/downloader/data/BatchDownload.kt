package com.gamehub.downloader.data

import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.BrowseRom
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.api.LibraryPage
import com.gamehub.downloader.download.DownloadMode
import com.gamehub.downloader.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What "Download all" will do, shown to the user before anything is queued. */
data class BatchPlan(
    val label: String,
    val tasks: List<DownloadTask>,
    val totalBytes: Long,
    /** Games already on this device or already queued. */
    val alreadyHave: Int,
    /** System name → games skipped because that system has no download folder yet. */
    val noFolder: Map<String, Int>,
    /** Volume label → (bytes needed, bytes free) where the games won't fit. */
    val tooBig: Map<String, Pair<Long, Long>>,
) {
    val count: Int get() = tasks.size
}

/** Queues every game in a list (favorites, a collection, a system) that isn't on the device yet. */
class BatchDownload(private val app: GameHubApp) {

    suspend fun plan(api: GameHubApi, conn: Connection, label: String, fetch: suspend (Int, Int) -> LibraryPage): BatchPlan {
        // Every game in the list (the server caps a page at 500).
        val roms = mutableListOf<BrowseRom>()
        while (roms.size < MAX_GAMES) {
            val page = fetch(roms.size, 500)
            roms += page.rows
            if (page.rows.isEmpty() || roms.size >= page.total) break
        }
        app.installed.refresh(conn.id)
        val settings = app.settings.current()
        val active = app.downloads.tasks.value.filter { it.isActive && it.connectionId == conn.id }.map { it.romId }.toSet()

        var have = 0
        val noFolder = mutableMapOf<String, Int>()
        val wanted = mutableListOf<BrowseRom>()
        for (rom in roms.distinctBy { it.id }) {
            val installed = app.installed.isInstalled(conn.id, rom.platformSlug, rom.title) ||
                (SystemRules.supportsVita3k(rom.platformSlug) && app.vita3k.apps.value.values.any {
                    InstalledIndex.normalize(it.title, isFile = false) == InstalledIndex.normalize(rom.title, isFile = false)
                })
            if (installed || rom.id in active) { have++; continue }
            wanted += rom
        }

        // Each game's folder: its system's folder, or a new sub-folder of the ROMs base folder.
        val mappings = mutableMapOf<String, FolderMapping?>()
        val tasks = coroutineScope {
            val gate = Semaphore(4)
            wanted.map { rom ->
                async {
                    gate.withPermit {
                        val detail = runCatching { api.rom(rom.id) }.getOrNull() ?: return@withPermit null
                        val mapping = synchronized(mappings) { mappings[rom.platformSlug] }
                            ?: folderFor(conn, settings, rom.platformSlug, detail.platformName ?: rom.platformSlug).also {
                                synchronized(mappings) { mappings[rom.platformSlug] = it }
                            }
                        if (mapping == null) {
                            synchronized(noFolder) { noFolder.merge(detail.platformName ?: rom.platformSlug, 1, Int::plus) }
                            return@withPermit null
                        }
                        val mode = if (detail.isMultiDisc) DownloadMode.DiscsZip else DownloadMode.File
                        DownloadTask(
                            connectionId = conn.id,
                            romId = detail.id,
                            title = detail.title,
                            systemKey = detail.platformSlug,
                            systemName = detail.platformName ?: detail.platformSlug,
                            fileName = FolderStorage.sanitize(if (mode == DownloadMode.DiscsZip) "${detail.title}.zip" else detail.filename ?: detail.title),
                            mode = mode,
                            folder = mapping,
                            boxartUrl = detail.boxartUrl,
                            totalBytes = if (mode == DownloadMode.File) detail.sizeBytes ?: 0 else 0,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Does it all fit? Grouped by the storage each folder is on.
        val tooBig = mutableMapOf<String, Pair<Long, Long>>()
        tasks.groupBy { Storage.volumeLabel(it.folder.treeUri) to it.folder.treeUri }.entries
            .groupBy({ it.key.first }, { it.key.second to it.value }).forEach { (volume, groups) ->
                val need = groups.sumOf { (_, list) -> list.sumOf { it.totalBytes } }
                val free = groups.firstNotNullOfOrNull { (uri, _) -> Storage.freeBytes(uri) }
                if (free != null && need > free - Storage.RESERVE_BYTES) tooBig[volume] = need to free
            }
        return BatchPlan(label, tasks, tasks.sumOf { it.totalBytes }, have, noFolder, tooBig)
    }

    fun enqueue(plan: BatchPlan): Int {
        plan.tasks.forEach { app.downloads.enqueue(it) }
        return plan.count
    }

    /** The system's folder if it's set up, else a sub-folder of the connection's base folder (created now). */
    private suspend fun folderFor(conn: Connection, settings: AppSettings, slug: String, name: String): FolderMapping? = withContext(Dispatchers.IO) {
        settings.folderFor(conn.id, slug)?.takeIf { FolderStorage.hasAccess(app, it.treeUri) }?.let { return@withContext it }
        val base = conn.baseFolderUri?.takeIf { FolderStorage.hasAccess(app, it) } ?: return@withContext null
        val mapping = FolderMapping(conn.id, slug, name, base, subPath = slug, extract = false)
        if (FolderStorage.resolve(app, mapping) == null) return@withContext null
        app.settings.setFolder(mapping)
        mapping
    }

    companion object {
        /** Safety cap on one "Download all". */
        const val MAX_GAMES = 2000
    }
}
