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
import com.gamehub.downloader.download.DownloadMode
import com.gamehub.downloader.download.DownloadTask
import kotlinx.coroutines.Dispatchers
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
    val message: String? = null,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GameHubApp
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()
    val tasks = app.downloads.tasks
    val history = app.history.records
    val installed = app.installed.contents

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
                    _state.update { it.copy(connection = conn, rom = rom, loading = false) }
                    refreshFolder()
                    resolveRelated(api, rom)
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
        _state.update { it.copy(folder = mapping, folderOk = ok) }
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
            val suggestExtract = mode == DownloadMode.DiscsZip || s.rom?.filename?.endsWith(".7z", ignoreCase = true) == true
            _state.update { it.copy(prompt = GamePrompt.ChooseFolder(mode, label, suggestExtract)) }
            return@launch
        }
        checkExistingAndEnqueue(mode, s.folder)
    }

    private suspend fun checkExistingAndEnqueue(mode: DownloadMode, mapping: FolderMapping) {
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

    fun confirmReplace(mode: DownloadMode) {
        val mapping = _state.value.folder ?: return
        dismissPrompt()
        enqueue(mode, mapping)
    }

    /** User picked a dedicated folder for this system. */
    fun onFolderPicked(uri: Uri, mode: DownloadMode) = viewModelScope.launch {
        val s = _state.value
        val rom = s.rom ?: return@launch
        val conn = s.connection ?: return@launch
        val mapping = FolderMapping(conn.id, rom.platformSlug, rom.platformName ?: rom.platformSlug, uri.toString(), extract = promptExtract())
        app.settings.setFolder(mapping)
        dismissPrompt()
        refreshFolder()
        checkExistingAndEnqueue(mode, mapping)
    }

    /** User picked (or already has) a base folder; this system gets a sub-folder named after its slug. */
    fun useBaseFolder(mode: DownloadMode, newBase: Uri? = null) = viewModelScope.launch {
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
        checkExistingAndEnqueue(mode, mapping)
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
        val entries = app.installed.findGameFiles(folder, rom.title, rom.filename)
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
        val removed = app.installed.delete(p.entries.filter { it.name in p.selected })
        app.installed.refresh(conn.id)
        app.history.remove(conn.id, rom.id)
        _state.update {
            it.copy(message = if (removed > 0) "Deleted $removed item${if (removed == 1) "" else "s"}" else "Couldn't delete the files")
        }
    }

    fun cancel(taskId: String) = app.downloads.cancel(taskId)
    fun dismissPrompt() = _state.update { it.copy(prompt = null) }
    fun clearMessage() = _state.update { it.copy(message = null) }
}
