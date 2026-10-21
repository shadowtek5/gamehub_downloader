package com.gamehub.downloader.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.api.RomDetail
import com.gamehub.downloader.data.Connection
import com.gamehub.downloader.data.FolderEntry
import com.gamehub.downloader.data.FolderMapping
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.data.Emulators
import com.gamehub.downloader.data.InstalledIndex
import com.gamehub.downloader.data.Launcher
import com.gamehub.downloader.data.PlaySession
import com.gamehub.downloader.data.LaunchFileWriter
import com.gamehub.downloader.data.Storage
import com.gamehub.downloader.data.SystemRules
import com.gamehub.downloader.download.formatBytes
import com.gamehub.downloader.download.DownloadMode
import com.gamehub.downloader.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen should ask the user before a download can start. */
sealed interface GamePrompt {
    /** [extract] is the prompt's "extract after download" checkbox, saved with the new folder. */
    data class ChooseFolder(val mode: DownloadMode, val baseFolderLabel: String?, val extract: Boolean) : GamePrompt
    /** Files found in the system folder for this game; [selected] holds the names that will be deleted. */
    data class ConfirmDelete(val entries: List<FolderEntry>, val selected: Set<String>, val folderLabel: String) : GamePrompt

    data class ConfirmReplace(val mode: DownloadMode, val fileName: String, val folderLabel: String) : GamePrompt
}

data class GameState(
    val connection: Connection? = null,
    val rom: RomDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val folder: FolderMapping? = null,
    val folderOk: Boolean = false,
    val prompt: GamePrompt? = null,
    /** Launch file (.dpt) for this game in the system's launch-file folder, if it exists. */
    val launchFile: String? = null,
    /** PS Vita: this game's title ID (from its name, Vita3K, or the zip) and whether Vita3K has it. */
    val vitaTitleId: String? = null,
    val vita3kInstalled: Boolean = false,
    val message: String? = null,
    /** Emulators that can play this game here, and the one the Play button uses. */
    val launchers: List<Launcher> = emptyList(),
    val launcher: Launcher? = null,
    val favorite: Boolean = false,
    /** Asking which emulator to use for this system. */
    val choosingLauncher: Boolean = false,
    /** How the game runs: official lists, reports from handhelds, and your own report for [launcher]. */
    val compat: com.gamehub.downloader.api.CompatInfo? = null,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GameHubApp
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()
    val tasks = app.downloads.tasks
    val history = app.history.records
    val installed = app.installed.contents
    val vita3kApps = app.vita3k.apps
    val vita3kInstalling = app.vita3k.installing
    val vita3kFolderSet = app.settings.settings.map { it.vita3kFolderUri != null }

    private var api: GameHubApi? = null

    /** Related-game title -> rom id in this library (null = not in library). Missing key = still checking. */
    private val _related = MutableStateFlow<Map<String, Long?>>(emptyMap())
    val related: StateFlow<Map<String, Long?>> = _related.asStateFlow()
    private var loadedKey: Pair<String, Long>? = null

    fun load(connectionId: String, romId: Long, force: Boolean = false) {
        if (!force && loadedKey == connectionId to romId) return
        loadedKey = connectionId to romId
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val settings = app.settings.current()
            val conn = settings.connections.firstOrNull { it.id == connectionId }
            if (conn == null) {
                _state.update { it.copy(loading = false, error = "Connection not found") }
                return@launch
            }
            val api = app.api(conn).also { this@GameViewModel.api = it }
            runCatching { api.rom(romId) }
                .onSuccess { rom ->
                    _state.update { it.copy(connection = conn, rom = rom, loading = false, favorite = rom.favorite == 1) }
                    refreshFolder()
                    refreshLaunchers()
                    resolveRelated(api, rom)
                    // Re-check Vita3K status whenever its installed list changes.
                    if (SystemRules.supportsVita3k(rom.platformSlug)) {
                        viewModelScope.launch { app.vita3k.apps.drop(1).collect { refreshFolder() } }
                    }
                }
                .onFailure { e -> _state.update { it.copy(connection = conn, loading = false, error = e.message ?: "Couldn't load game") } }
        }
    }

    private suspend fun refreshFolder() {
        val s = _state.value
        val rom = s.rom ?: return
        val conn = s.connection ?: return
        val mapping = app.settings.current().folderFor(conn.id, rom.platformSlug)
        val ok = mapping != null && withContext(Dispatchers.IO) { FolderStorage.hasAccess(app, mapping.treeUri) }
        // PS Vita: title ID from the file name, a Vita3K title match, or the downloaded zip.
        var vitaId: String? = null
        var inVita3k = false
        if (SystemRules.supportsVita3k(rom.platformSlug)) {
            val apps = app.vita3k.apps.value
            vitaId = rom.filename?.let { LaunchFileWriter.titleIdFromName(it) }
                ?: apps.values.firstOrNull {
                    InstalledIndex.normalize(it.title, isFile = false) == InstalledIndex.normalize(rom.title, isFile = false)
                }?.titleId
            if (vitaId == null && mapping != null) {
                vitaId = withContext(Dispatchers.IO) {
                    runCatching {
                        app.installed.findGameFiles(mapping, rom.title, rom.filename)
                            .filter { it.name.endsWith(".dpt", true) || it.name.endsWith(".zip", true) }
                            .minByOrNull { if (it.name.endsWith(".dpt", true)) 0 else 1 }?.let { e ->
                            androidx.documentfile.provider.DocumentFile.fromSingleUri(app, e.uri)?.let { LaunchFileWriter(app).titleIdFor(it) }
                        }
                    }.getOrNull()
                }
            }
            inVita3k = vitaId != null && vitaId in apps
        }
        val dpt = if (mapping != null && vitaId != null && SystemRules.supportsLaunchFiles(rom.platformSlug)) {
            runCatching {
                withContext(Dispatchers.IO) { FolderStorage.resolve(app, mapping) }?.let { LaunchFileWriter(app).existingByTitleId(it)[vitaId]?.name }
            }.getOrNull()
        } else null
        _state.update { it.copy(folder = mapping, folderOk = ok, launchFile = dpt, vitaTitleId = vitaId, vita3kInstalled = inVita3k) }
    }

    fun mediaUrl(path: String?, width: Int? = null) = api?.mediaUrl(path, width)

    /** Looks each related IGDB title up in the library (a few at a time) so the page can link straight to it. */
    private fun resolveRelated(api: GameHubApi, rom: RomDetail) {
        val rel = rom.related ?: return
        val names = (rel.editions + rel.similar).map { it.name }.distinct().take(24)
        if (names.isEmpty()) return
        viewModelScope.launch {
            val gate = Semaphore(4)
            names.map { name ->
                async {
                    gate.withPermit {
                        val id = runCatching { findInLibrary(api, name, rom.platformSlug, rom.id) }.getOrNull()
                        _related.update { it + (name to id) }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun findInLibrary(api: GameHubApi, name: String, preferPlatform: String, selfId: Long): Long? {
        // Search on the main title (before any subtitle) so punctuation differences still match,
        // then require the full normalized title to be equal to avoid opening the wrong game.
        val query = name.substringBefore(':').substringBefore(" - ").trim().trimEnd('?', '!', '.')
        if (query.length < 2) return null
        val target = normalizeTitle(name)
        val matches = api.library(platform = null, query = query, offset = 0, limit = 40).rows
            .filter { it.id != selfId && normalizeTitle(it.title) == target }
        return (matches.firstOrNull { it.platformSlug == preferPlatform } ?: matches.firstOrNull())?.id
    }

    private fun normalizeTitle(t: String): String {
        var s = t.lowercase().trim()
        if (s.endsWith(", the")) s = s.removeSuffix(", the")
        s = s.removePrefix("the ")
        return s.filter { it.isLetterOrDigit() }
    }

    fun notInLibrary(name: String) = _state.update { it.copy(message = "“$name” isn't in your library") }

    fun fileNameFor(mode: DownloadMode): String {
        val rom = _state.value.rom ?: return "download"
        return FolderStorage.sanitize(
            when (mode) {
                DownloadMode.DiscsZip -> "${rom.title}.zip"
                DownloadMode.File -> rom.filename ?: rom.title
            }
        )
    }

    /** Entry point for the Download button. */
    fun requestDownload(mode: DownloadMode) = viewModelScope.launch {
        refreshFolder()
        val s = _state.value
        val conn = s.connection ?: return@launch
        if (s.folder == null || !s.folderOk) {
            val base = conn.baseFolderUri?.takeIf { withContext(Dispatchers.IO) { FolderStorage.hasAccess(app, it) } }
            val slug = s.rom?.platformSlug ?: return@launch
            val label = base?.let { FolderStorage.describe(FolderMapping(conn.id, slug, slug, it, subPath = slug)) }
            // Default the checkbox on for .7z (few emulators read it) and for multi-disc zips.
            val canExtract = SystemRules.canExtract(slug)
            val suggestExtract = canExtract && (mode == DownloadMode.DiscsZip || s.rom?.filename?.endsWith(".7z", ignoreCase = true) == true)
            _state.update { it.copy(prompt = GamePrompt.ChooseFolder(mode, label, suggestExtract)) }
            return@launch
        }
        checkExistingAndEnqueue(mode, s.folder)
    }

    private suspend fun checkExistingAndEnqueue(mode: DownloadMode, mapping: FolderMapping) {
        // Refuse up front when the game can't fit (archives that get extracted need room for both).
        val rom = _state.value.rom
        val size = rom?.sizeBytes?.takeIf { mode == DownloadMode.File } ?: 0
        val need = if (mapping.extract && rom?.isArchive == true) size * 3 else size
        val free = withContext(Dispatchers.IO) { Storage.freeBytes(mapping.treeUri) }
        if (free != null && need > 0 && need > free - Storage.RESERVE_BYTES) {
            _state.update {
                it.copy(message = "Not enough space on ${Storage.volumeLabel(mapping.treeUri)}: needs ${formatBytes(need)}, ${formatBytes(free)} free")
            }
            return
        }
        val name = fileNameFor(mode)
        val exists = withContext(Dispatchers.IO) {
            runCatching { FolderStorage.resolve(app, mapping)?.findFile(name) != null }.getOrDefault(false)
        }
        if (exists) {
            _state.update { it.copy(prompt = GamePrompt.ConfirmReplace(mode, name, FolderStorage.describe(mapping))) }
        } else {
            enqueue(mode, mapping)
        }
    }

    /** DLC / updates waiting for the system's folder to be chosen. */
    private var pendingAddons: List<com.gamehub.downloader.api.GameAddon>? = null

    /** Downloads these DLC / updates into the game's system folder (Vita: installed into Vita3K like the game). */
    fun downloadAddons(addons: List<com.gamehub.downloader.api.GameAddon>) = viewModelScope.launch {
        refreshFolder()
        val s = _state.value
        val conn = s.connection ?: return@launch
        val wanted = addons.filter { it.sizeBytes > 0 }
        if (wanted.isEmpty()) return@launch
        if (s.folder == null || !s.folderOk) {
            pendingAddons = wanted
            val base = conn.baseFolderUri?.takeIf { withContext(Dispatchers.IO) { FolderStorage.hasAccess(app, it) } }
            val slug = s.rom?.platformSlug ?: return@launch
            val label = base?.let { FolderStorage.describe(FolderMapping(conn.id, slug, slug, it, subPath = slug)) }
            _state.update { it.copy(prompt = GamePrompt.ChooseFolder(DownloadMode.File, label, false)) }
            return@launch
        }
        enqueueAddons(wanted, s.folder)
    }

    private suspend fun enqueueAddons(addons: List<com.gamehub.downloader.api.GameAddon>, mapping: FolderMapping) {
        pendingAddons = null
        val s = _state.value
        val rom = s.rom ?: return
        val conn = s.connection ?: return
        val need = addons.sumOf { it.sizeBytes }
        val free = withContext(Dispatchers.IO) { Storage.freeBytes(mapping.treeUri) }
        if (free != null && need > free - Storage.RESERVE_BYTES) {
            _state.update { it.copy(message = "Not enough space on ${Storage.volumeLabel(mapping.treeUri)}: needs ${formatBytes(need)}, ${formatBytes(free)} free") }
            return
        }
        for (a in addons) {
            app.downloads.enqueue(
                DownloadTask(
                    connectionId = conn.id,
                    romId = a.id,
                    title = "${rom.title} · " + when {
                        !a.items.isNullOrEmpty() -> "DLC pack · ${a.items.size}"
                        a.kind == "update" && a.version != null -> "Update ${a.version}"
                        else -> "${a.kindLabel}: ${a.shortName(rom.title)}"
                    },
                    systemKey = rom.platformSlug,
                    systemName = rom.platformName ?: rom.platformSlug,
                    fileName = FolderStorage.sanitize(a.filename),
                    mode = DownloadMode.File,
                    folder = mapping,
                    boxartUrl = rom.boxartUrl,
                    totalBytes = a.sizeBytes,
                    doneHint = addonInstallHint(rom.platformSlug),
                    isAddon = true,
                )
            )
        }
        _state.update { it.copy(message = if (addons.size == 1) "Added to downloads" else "Added ${addons.size} downloads") }
    }

    /** After the system's folder was chosen: the DLC / updates waiting for it ([addons], taken before the prompt closed), else the game. */
    private suspend fun afterFolderChosen(mode: DownloadMode, mapping: FolderMapping, addons: List<com.gamehub.downloader.api.GameAddon>?) {
        if (addons != null) enqueueAddons(addons, mapping) else checkExistingAndEnqueue(mode, mapping)
    }

    fun confirmReplace(mode: DownloadMode) {
        val mapping = _state.value.folder ?: return
        dismissPrompt()
        enqueue(mode, mapping)
    }

    /** User picked a dedicated folder for this system. */
    fun onFolderPicked(uri: Uri, mode: DownloadMode) = viewModelScope.launch {
        val addons = pendingAddons
        val s = _state.value
        val rom = s.rom ?: return@launch
        val conn = s.connection ?: return@launch
        val mapping = FolderMapping(conn.id, rom.platformSlug, rom.platformName ?: rom.platformSlug, uri.toString(), extract = promptExtract())
        app.settings.setFolder(mapping)
        dismissPrompt()
        refreshFolder()
        afterFolderChosen(mode, mapping, addons)
    }

    /** User picked (or already has) a base folder; this system gets a sub-folder named after its slug. */
    fun useBaseFolder(mode: DownloadMode, newBase: Uri? = null) = viewModelScope.launch {
        val addons = pendingAddons
        val s = _state.value
        val rom = s.rom ?: return@launch
        var conn = s.connection ?: return@launch
        if (newBase != null) {
            app.settings.setBaseFolder(conn.id, newBase.toString())
            conn = conn.copy(baseFolderUri = newBase.toString())
            _state.update { it.copy(connection = conn) }
        }
        val base = conn.baseFolderUri ?: return@launch
        val mapping = FolderMapping(conn.id, rom.platformSlug, rom.platformName ?: rom.platformSlug, base, subPath = rom.platformSlug, extract = promptExtract())
        val created = withContext(Dispatchers.IO) { FolderStorage.resolve(app, mapping) != null }
        if (!created) {
            _state.update { it.copy(message = "Couldn't create the ${rom.platformSlug} folder") }
            return@launch
        }
        app.settings.setFolder(mapping)
        dismissPrompt()
        refreshFolder()
        afterFolderChosen(mode, mapping, addons)
    }

    fun changeFolder(uri: Uri) = viewModelScope.launch {
        val s = _state.value
        val rom = s.rom ?: return@launch
        val conn = s.connection ?: return@launch
        val keepExtract = s.folder?.extract ?: false
        app.settings.setFolder(FolderMapping(conn.id, rom.platformSlug, rom.platformName ?: rom.platformSlug, uri.toString(), extract = keepExtract))
        refreshFolder()
    }

    private fun enqueue(mode: DownloadMode, mapping: FolderMapping) {
        val s = _state.value
        val rom = s.rom ?: return
        val conn = s.connection ?: return
        app.downloads.enqueue(
            DownloadTask(
                connectionId = conn.id,
                romId = rom.id,
                title = rom.title,
                systemKey = rom.platformSlug,
                systemName = rom.platformName ?: rom.platformSlug,
                fileName = fileNameFor(mode),
                mode = mode,
                folder = mapping,
                boxartUrl = rom.boxartUrl,
                totalBytes = if (mode == DownloadMode.File) rom.sizeBytes ?: 0 else 0,
            )
        )
        _state.update { it.copy(message = "Added to downloads") }
    }

    private fun promptExtract(): Boolean = (_state.value.prompt as? GamePrompt.ChooseFolder)?.extract ?: false

    fun togglePromptExtract() = _state.update { st ->
        val p = st.prompt as? GamePrompt.ChooseFolder ?: return@update st
        st.copy(prompt = p.copy(extract = !p.extract))
    }

    /** Per-system "extract after download", saved with the folder mapping. */
    fun setExtract(on: Boolean) = viewModelScope.launch {
        val s = _state.value
        val conn = s.connection ?: return@launch
        val rom = s.rom ?: return@launch
        app.settings.setExtract(conn.id, rom.platformSlug, on)
        refreshFolder()
    }

    /** Finds this game's files in its system folder and asks before deleting them. */
    fun requestDelete() = viewModelScope.launch {
        val s = _state.value
        val rom = s.rom ?: return@launch
        val folder = s.folder ?: return@launch
        val entries = app.installed.findGameFiles(folder, rom.title, rom.filename).let { found ->
            // A synced .dpt may be named after Vita3K's title; include it by title ID if the name didn't match.
            val id = s.vitaTitleId
            val dpt = if (id != null && SystemRules.supportsLaunchFiles(rom.platformSlug)) runCatching {
                withContext(Dispatchers.IO) { FolderStorage.resolve(app, folder) }?.let { LaunchFileWriter(app).existingByTitleId(it)[id] }
            }.getOrNull() else null
            if (dpt != null && found.none { it.uri == dpt.uri }) found + FolderEntry(dpt.name ?: "launch file", dpt.uri, false, dpt.length()) else found
        }.let { list ->
            val id = s.vitaTitleId
            val appDir = id?.let { app.vita3k.apps.value[it]?.appDir }
            if (s.vita3kInstalled && id != null && appDir != null)
                list + FolderEntry("Vita3K installation ($id) — save data is kept", appDir, true, 0, vita3kTitleId = id)
            else list
        }
        if (entries.isEmpty()) {
            _state.update { it.copy(message = "No files for this game were found in the folder") }
            app.installed.refresh(folder.connectionId)
            return@launch
        }
        _state.update {
            it.copy(prompt = GamePrompt.ConfirmDelete(entries, entries.map { e -> e.name }.toSet(), FolderStorage.describe(folder)))
        }
    }

    fun toggleDeleteEntry(name: String) = _state.update { st ->
        val p = st.prompt as? GamePrompt.ConfirmDelete ?: return@update st
        st.copy(prompt = p.copy(selected = if (name in p.selected) p.selected - name else p.selected + name))
    }

    fun confirmDelete() = viewModelScope.launch {
        val s = _state.value
        val p = s.prompt as? GamePrompt.ConfirmDelete ?: return@launch
        val rom = s.rom ?: return@launch
        val conn = s.connection ?: return@launch
        dismissPrompt()
        val chosen = p.entries.filter { it.name in p.selected }
        val vita = chosen.mapNotNull { it.vita3kTitleId }.count { app.vita3k.uninstall(it) }
        val removed = app.installed.delete(chosen.filter { it.vita3kTitleId == null }) + vita
        app.installed.refresh(conn.id)
        app.history.remove(conn.id, rom.id)
        _state.update {
            it.copy(message = if (removed > 0) "Deleted $removed item${if (removed == 1) "" else "s"}" else "Couldn't delete the files")
        }
    }

    /**
     * Writes this game's .dpt: from the staged zip if it's still there, otherwise from the title ID
     * already known (e.g. installed in Vita3K). Named after the game's zip like downloads are.
     */
    fun createLaunchFile() = viewModelScope.launch {
        val s = _state.value
        val rom = s.rom ?: return@launch
        val folder = s.folder ?: return@launch
        val dir = withContext(Dispatchers.IO) { FolderStorage.resolve(app, folder) } ?: return@launch
        val writer = LaunchFileWriter(app)
        val zip = app.installed.findGameFiles(folder, rom.title, rom.filename).firstOrNull { it.name.endsWith(".zip", true) }
        val titleId = zip?.let { androidx.documentfile.provider.DocumentFile.fromSingleUri(app, it.uri)?.let { d -> writer.titleIdFor(d) } }
            ?: s.vitaTitleId
        val msg = if (titleId == null) "Couldn't find this game's title ID"
        else runCatching {
            writer.existingByTitleId(dir)[titleId]?.delete()
            "Created ${writer.write(dir, zip?.name ?: rom.filename ?: "${rom.title}.zip", titleId)} ($titleId)"
        }.getOrElse { it.message ?: "Couldn't write the launch file" }
        _state.update { it.copy(message = msg) }
        refreshFolder()
    }

    /** Runs the Vita flow on the staged zip: .dpt, install into Vita3K, delete the zip (app-wide, survives leaving the page). */
    fun installInVita3k() {
        val s = _state.value
        val rom = s.rom ?: return
        val folder = s.folder ?: return
        app.appScope.launch {
            val zip = app.installed.findGameFiles(folder, rom.title, rom.filename)
                .firstOrNull { !it.isDir && it.name.endsWith(".zip", ignoreCase = true) }
                ?.let { androidx.documentfile.provider.DocumentFile.fromSingleUri(app, it.uri) }
            val msg = if (zip == null) "Download the game first" else app.vita3k.processDownloadedZip(zip, folder).summary()
            app.installed.refresh(folder.connectionId)
            _state.update { it.copy(message = msg) }
            refreshFolder()
        }
    }

    fun playInVita3k() {
        val s = _state.value
        val id = s.vitaTitleId ?: return
        if (!app.vita3k.play(id)) {
            _state.update { it.copy(message = "Couldn't start Vita3K") }
            return
        }
        trackSession("org.vita3k.emulator", "org.vita3k.emulator", "Vita3K")
    }

    /** Works out which emulators can start this game here, and which one Play uses. */
    fun refreshLaunchers() = viewModelScope.launch {
        val rom = _state.value.rom ?: return@launch
        val settings = app.settings.current()
        val catalog = _state.value.connection?.let { app.emulatorCatalogs.get(it) }
        val all = withContext(Dispatchers.IO) { Emulators.available(app, rom.platformSlug, catalog) }
        var pick = Emulators.preferred(all, settings.launchers[rom.platformSlug], settings.saveSync.retroarchCoreDirs[rom.platformSlug])
        // Reports for this game: if the usual emulator is rated Broken and another installed one Playable, use that one.
        val compat = api?.let { a -> runCatching { a.compat(rom.id) }.getOrNull() }
        val ratings = compat?.summary?.devices?.associate { it.emulator to it.consensus }.orEmpty()
        if (pick != null && ratings[com.gamehub.downloader.data.Compat.emulatorKey(pick)] == "broken") {
            all.firstOrNull { ratings[com.gamehub.downloader.data.Compat.emulatorKey(it)] == "playable" }?.let { pick = it }
        }
        _state.update { it.copy(launchers = all, launcher = pick) }
        refreshCompat()
    }

    /** Loads the game's compatibility, with your report for the current emulator on this device. */
    fun refreshCompat() = viewModelScope.launch {
        val rom = _state.value.rom ?: return@launch
        val api = api ?: return@launch
        val key = _state.value.launcher?.let { com.gamehub.downloader.data.Compat.emulatorKey(it) } ?: ""
        runCatching { api.compat(rom.id, key, com.gamehub.downloader.data.Compat.DEVICE) }
            .onSuccess { info -> _state.update { it.copy(compat = info) } }
    }

    /** Rates how the game runs in the current emulator on this device (playable | runs | broken). */
    fun rate(rating: String) = viewModelScope.launch {
        val s = _state.value
        val rom = s.rom ?: return@launch
        val launcher = s.launcher ?: return@launch
        val api = api ?: return@launch
        runCatching {
            api.reportCompat(rom.id, rating, null, com.gamehub.downloader.data.Compat.emulatorKey(launcher), launcher.label, com.gamehub.downloader.data.Compat.DEVICE)
        }.onSuccess { _state.update { it.copy(message = "Thanks — saved to GameHub") }; refreshCompat() }
            .onFailure { e -> _state.update { it.copy(message = e.message ?: "Couldn't save the rating") } }
    }

    fun openLauncherChoice() = _state.update { it.copy(choosingLauncher = true) }
    fun closeLauncherChoice() = _state.update { it.copy(choosingLauncher = false) }

    /** Remembers [launcher] for every game of this system. */
    fun chooseLauncher(launcher: Launcher) = viewModelScope.launch {
        val rom = _state.value.rom ?: return@launch
        app.settings.setLauncher(rom.platformSlug, launcher.id)
        _state.update { it.copy(launcher = launcher, choosingLauncher = false, message = "${rom.platformName ?: rom.platformSlug} games now open in ${launcher.label}") }
        refreshCompat()
    }

    /** Starts the downloaded game in the chosen emulator. */
    fun play() = viewModelScope.launch {
        val s = _state.value
        val rom = s.rom ?: return@launch
        val folder = s.folder ?: return@launch
        val launcher = s.launcher ?: run {
            _state.update { it.copy(message = "No emulator for ${rom.platformName ?: rom.platformSlug} is installed") }
            return@launch
        }
        val files = app.installed.findGameFiles(folder, rom.title, rom.filename)
        val target = Emulators.pickTarget(files, launcher.accept) ?: run {
            val ext = files.firstOrNull { !it.isDir }?.name?.substringAfterLast('.', "")?.lowercase()
            _state.update {
                it.copy(
                    message = if (ext.isNullOrEmpty()) "Couldn't find the game's file in ${FolderStorage.describe(folder)}"
                    else "${launcher.label} can't open .$ext files. Turn on \"Extract after download\" for this system or choose another emulator."
                )
            }
            return@launch
        }
        val tags = buildMap { s.vitaTitleId?.let { put("vita_game_id", it) } }
        val intent = Emulators.intent(app, launcher, target, tags) ?: run {
            _state.update { it.copy(message = "${launcher.label} needs the game on this device's storage") }
            return@launch
        }
        val started = runCatching { app.startActivity(intent) }
        if (started.isFailure) {
            _state.update { it.copy(message = "Couldn't start ${launcher.label}: ${started.exceptionOrNull()?.message ?: "unknown error"}") }
            return@launch
        }
        trackSession(launcher.packageName, com.gamehub.downloader.data.Compat.emulatorKey(launcher), launcher.label)
    }

    private fun trackSession(pkg: String, emulator: String, emulatorName: String) {
        val s = _state.value
        val rom = s.rom ?: return
        val conn = s.connection ?: return
        app.appScope.launch {
            app.playtime.start(PlaySession(conn.id, rom.id, rom.title, pkg, System.currentTimeMillis(), emulator, emulatorName))
        }
    }

    fun toggleFavorite() = viewModelScope.launch {
        val s = _state.value
        val rom = s.rom ?: return@launch
        val api = api ?: return@launch
        val want = !s.favorite
        _state.update { it.copy(favorite = want) }
        runCatching { api.setFavorite(rom.id, want) }
            .onSuccess { fav -> _state.update { it.copy(favorite = fav, message = if (fav) "Added to favorites" else "Removed from favorites") } }
            .onFailure { e -> _state.update { it.copy(favorite = !want, message = e.message ?: "Couldn't update favorites") } }
    }

    fun setVita3kFolder(uri: Uri) = viewModelScope.launch {
        if (!app.vita3k.isVita3kFolder(uri.toString())) {
            _state.update { it.copy(message = "That isn't Vita3K's folder — pick the one that contains ux0") }
            return@launch
        }
        app.settings.setVita3kFolder(uri.toString())
    }

    fun cancel(taskId: String) = app.downloads.cancel(taskId)
    fun dismissPrompt() {
        pendingAddons = null
        _state.update { it.copy(prompt = null) }
    }
    fun clearMessage() = _state.update { it.copy(message = null) }
}

/** How each system's emulators take DLC and updates (Vita: the app installs them into Vita3K). */
fun addonInstallHint(systemKey: String): String? = when (systemKey) {
    "switch" -> "Install it in Eden with Install files to NAND."
    "ps3" -> "Install the .pkg with the emulator's Install packages; DLC that needs a license also needs its .rap."
    "3ds" -> "Install it in Azahar with Install CIA."
    "xbox360" -> "Install it from the emulator's content menu."
    "wiiu" -> "Install it in Cemu with Install game title, update or DLC."
    else -> null
}
