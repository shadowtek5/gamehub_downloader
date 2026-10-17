package com.gamehub.downloader.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.BrowseRom
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.api.Platform
import com.gamehub.downloader.data.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val connection: Connection? = null,
    val connections: List<Connection> = emptyList(),
    val settingsLoaded: Boolean = false,
    /** Every system on the server, and the subset the user has chosen to show. */
    val allPlatforms: List<Platform> = emptyList(),
    val platforms: List<Platform> = emptyList(),
    val platformsError: String? = null,
    val selected: String? = null,
    val query: String = "",
    val games: List<BrowseRom> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = games.size < total && !loading && error == null
    val selectedPlatform: Platform? get() = platforms.firstOrNull { it.slug == selected }
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GameHubApp
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var api: GameHubApi? = null
    private var pageJob: Job? = null
    private var searchJob: Job? = null

    /** Rom ids already downloaded for the active connection (for the ✓ badge). */
    val downloadedIds: StateFlow<Set<Long>> = combine(app.history.records, _state.map { it.connection?.id }.distinctUntilChanged()) { recs, cid ->
        recs.filter { it.connectionId == cid }.map { it.romId }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val downloads = app.downloads.tasks

    /** What's already in each system folder, for the "installed" badges and rail counts. */
    val installed = app.installed.contents

    fun rescanFolders() = viewModelScope.launch { app.installed.refresh(_state.value.connection?.id) }

    init {
        viewModelScope.launch {
            app.settings.settings.collect { s ->
                val active = s.activeConnection
                val prev = _state.value.connection
                val changed = active?.id != prev?.id || active?.baseUrl != prev?.baseUrl || active?.token != prev?.token
                val visibilityChanged = !changed && active?.hiddenSystems != prev?.hiddenSystems
                _state.update { it.copy(connections = s.connections, connection = active, settingsLoaded = true) }
                if (changed) {
                    api = active?.let { app.api(it) }
                    _state.update { it.copy(allPlatforms = emptyList(), platforms = emptyList(), selected = null, games = emptyList(), total = 0, error = null, platformsError = null) }
                    if (active != null) reload()
                } else if (visibilityChanged) {
                    applyVisibility()
                    refreshGames()
                }
            }
        }
    }

    fun reload() {
        val api = api ?: return
        // Systems first: "All games" is limited to the visible systems, so we need that list.
        viewModelScope.launch {
            runCatching { api.platforms() }
                .onSuccess { list ->
                    _state.update { it.copy(allPlatforms = list, platformsError = null) }
                    applyVisibility()
                }
                .onFailure { e -> _state.update { it.copy(platformsError = e.message ?: "Couldn't reach server") } }
            refreshGames()
        }
    }

    private fun applyVisibility() = _state.update { st ->
        val conn = st.connection ?: return@update st
        val visible = st.allPlatforms.filter { conn.isSystemVisible(it.slug) }
        st.copy(platforms = visible, selected = st.selected?.takeIf { sel -> visible.any { it.slug == sel } })
    }

    /** Platform filter for the API: the selected system, or every visible one when some are hidden. */
    private fun platformFilter(s: LibraryState): String? = when {
        s.selected != null -> s.selected
        s.allPlatforms.isEmpty() || s.platforms.size == s.allPlatforms.size -> null
        else -> s.platforms.joinToString(",") { it.slug }
    }

    fun selectPlatform(slug: String?) {
        if (slug == _state.value.selected) return
        _state.update { it.copy(selected = slug) }
        refreshGames()
    }

    /** L1/R1 cycling through "All" + each system. */
    fun cyclePlatform(step: Int) {
        val keys = listOf<String?>(null) + _state.value.platforms.map { it.slug }
        val idx = keys.indexOf(_state.value.selected).coerceAtLeast(0)
        selectPlatform(keys[(idx + step + keys.size) % keys.size])
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            refreshGames()
        }
    }

    fun switchConnection(id: String) = viewModelScope.launch { app.settings.setActiveConnection(id) }

    private fun refreshGames() {
        pageJob?.cancel()
        _state.update { it.copy(games = emptyList(), total = 0, error = null) }
        loadMore(force = true)
    }

    fun loadMore(force: Boolean = false) {
        val api = api ?: return
        val s = _state.value
        if (!force && !s.canLoadMore) return
        if (!force && pageJob?.isActive == true) return
        // Every system is hidden: nothing to show (the screen explains how to turn some back on).
        if (s.selected == null && s.allPlatforms.isNotEmpty() && s.platforms.isEmpty()) {
            _state.update { it.copy(games = emptyList(), total = 0, loading = false) }
            return
        }
        pageJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { api.library(platformFilter(_state.value), s.query.ifBlank { null }, offset = _state.value.games.size) }
                .onSuccess { page ->
                    _state.update { cur ->
                        val merged = (cur.games + page.rows).distinctBy { it.id }
                        cur.copy(games = merged, total = maxOf(page.total, merged.size).let { if (page.rows.isEmpty()) merged.size else it }, loading = false)
                    }
                }
                .onFailure { e ->
                    // A newer search/system change cancelled this page load; that's not an error.
                    if (e is CancellationException) throw e
                    _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load games") }
                }
        }
    }

    fun mediaUrl(path: String?, width: Int? = null) = api?.mediaUrl(path, width)
}
