package com.gamehub.downloader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** A saved GameHub server the app can browse and download from. */
@Serializable
data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val token: String,
    /** Optional parent folder; systems without their own folder get a sub-folder named after their slug. */
    val baseFolderUri: String? = null,
    /** Systems hidden from the library (everything is shown by default). */
    val hiddenSystems: Set<String> = emptySet(),
) {
    fun isSystemVisible(slug: String): Boolean = slug !in hiddenSystems
}

object SystemVisibility {
    /** One-tap preset in Settings › Systems: keep only these makers' systems. */
    val PRESET_VENDORS = setOf("nintendo", "sega", "sony")
    fun inPreset(vendor: String) = vendor.trim().lowercase() in PRESET_VENDORS
}

/**
 * Where ROMs for one system on one connection are saved: a SAF tree URI the user granted,
 * plus an optional sub-folder name inside it (used when mapping systems from one base folder).
 */
@Serializable
data class FolderMapping(
    val connectionId: String,
    val systemKey: String,
    val systemName: String,
    val treeUri: String,
    val subPath: String = "",
    /** Unpack .zip / .7z downloads into the folder and delete the archive. */
    val extract: Boolean = false,
)

/**
 * Where each emulator keeps its saves on this device (SAF tree URIs the user granted).
 * PS Vita uses Vita3K's folder and Nintendo DS uses the DS download folder, so they only need a switch.
 */
@Serializable
data class SaveSyncSettings(
    /** RetroArch's "saves" folder (contains <core>/<ROM name>.srm). */
    val retroarchSavesUri: String? = null,
    /** RetroArch's "states" folder (contains <core>/<ROM name>.state, .state1…). */
    val retroarchStatesUri: String? = null,
    /** PPSSPP's "PSP" folder (contains SAVEDATA). */
    val ppssppUri: String? = null,
    /** Azahar's user folder (contains sdmc). */
    val azaharUri: String? = null,
    val vita: Boolean = true,
    val nds: Boolean = true,
    /** Sync all saves when the app is opened. */
    val syncOnOpen: Boolean = true,
    /** RetroArch core folder learned per system from existing saves, e.g. "gba" → "mGBA". */
    val retroarchCoreDirs: Map<String, String> = emptyMap(),
    /** Also sync in the background every [backgroundHours] hours (0 = off). */
    val backgroundHours: Int = 0,
    /** Background sync only on Wi-Fi / unmetered networks. */
    val backgroundUnmetered: Boolean = true,
)

/** A game started from the Play button; closed (and its time sent to GameHub) when the app comes back. */
@Serializable
data class PlaySession(
    val connectionId: String,
    val romId: Long,
    val title: String,
    /** The emulator's package, for measuring time with usage access. */
    val packageName: String,
    val startedAt: Long,
)

@Serializable
data class AppSettings(
    val connections: List<Connection> = emptyList(),
    val activeConnectionId: String? = null,
    val folders: List<FolderMapping> = emptyList(),
    /** Vita3K's storage folder (the one containing ux0), granted by the user. */
    val vita3kFolderUri: String? = null,
    val saveSync: SaveSyncSettings = SaveSyncSettings(),
    /** Emulator chosen per system for the Play button, e.g. "nes" → "retroarch:fceumm" (see [Emulators]). */
    val launchers: Map<String, String> = emptyMap(),
    /** The game launched last, until its playtime has been recorded. */
    val playSession: PlaySession? = null,
    /** Report time played on this device to GameHub. */
    val trackPlaytime: Boolean = true,
    /** RetroArch's "system" folder (BIOS files go here). */
    val retroarchSystemUri: String? = null,
    /** Folder each standalone emulator reads BIOS files from, by GameHub's emulator id ("duckstation", "citra"…). */
    val biosFolders: Map<String, String> = emptyMap(),
    /** Check GitHub for a newer version of the app when it starts. */
    val checkUpdates: Boolean = true,
    /** Release version the user chose to skip. */
    val skippedUpdate: String? = null,
    /** Show game art and download progress on a second screen (the AYN Thor's lower display). */
    val secondScreen: Boolean = true,
) {
    val activeConnection: Connection?
        get() = connections.firstOrNull { it.id == activeConnectionId } ?: connections.firstOrNull()

    fun folderFor(connectionId: String, systemKey: String): FolderMapping? =
        folders.firstOrNull { it.connectionId == connectionId && it.systemKey == systemKey }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("app_settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val now = prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
            prefs[key] = json.encodeToString(AppSettings.serializer(), transform(now))
        }
    }

    suspend fun upsertConnection(connection: Connection) = update { s ->
        val exists = s.connections.any { it.id == connection.id }
        val list = if (exists) s.connections.map { if (it.id == connection.id) connection else it }
        else s.connections + connection
        s.copy(connections = list, activeConnectionId = s.activeConnectionId ?: connection.id)
    }

    suspend fun deleteConnection(id: String) = update { s ->
        val list = s.connections.filterNot { it.id == id }
        s.copy(
            connections = list,
            activeConnectionId = if (s.activeConnectionId == id) list.firstOrNull()?.id else s.activeConnectionId,
            folders = s.folders.filterNot { it.connectionId == id },
        )
    }

    suspend fun setActiveConnection(id: String) = update { it.copy(activeConnectionId = id) }

    suspend fun setFolder(mapping: FolderMapping) = update { s ->
        val rest = s.folders.filterNot { it.connectionId == mapping.connectionId && it.systemKey == mapping.systemKey }
        s.copy(folders = rest + mapping)
    }

    suspend fun setFolders(mappings: List<FolderMapping>) = update { s ->
        val keys = mappings.map { it.connectionId to it.systemKey }.toSet()
        s.copy(folders = s.folders.filterNot { (it.connectionId to it.systemKey) in keys } + mappings)
    }

    suspend fun setBaseFolder(connectionId: String, treeUri: String?) = update { s ->
        s.copy(connections = s.connections.map { if (it.id == connectionId) it.copy(baseFolderUri = treeUri) else it })
    }

    suspend fun setExtract(connectionId: String, systemKey: String, extract: Boolean) = update { s ->
        s.copy(folders = s.folders.map {
            if (it.connectionId == connectionId && it.systemKey == systemKey) it.copy(extract = extract) else it
        })
    }

    suspend fun setVita3kFolder(treeUri: String?) = update { it.copy(vita3kFolderUri = treeUri) }

    suspend fun setLauncher(systemKey: String, launcherId: String?) = update { s ->
        s.copy(launchers = if (launcherId == null) s.launchers - systemKey else s.launchers + (systemKey to launcherId))
    }

    suspend fun setPlaySession(session: PlaySession?) = update { it.copy(playSession = session) }

    suspend fun setTrackPlaytime(on: Boolean) = update { it.copy(trackPlaytime = on) }

    suspend fun setRetroarchSystem(treeUri: String?) = update { it.copy(retroarchSystemUri = treeUri) }

    suspend fun setBiosFolder(emulatorId: String, treeUri: String?) = update { s ->
        s.copy(biosFolders = if (treeUri == null) s.biosFolders - emulatorId else s.biosFolders + (emulatorId to treeUri))
    }

    suspend fun setCheckUpdates(on: Boolean) = update { it.copy(checkUpdates = on) }

    suspend fun setSkippedUpdate(version: String?) = update { it.copy(skippedUpdate = version) }

    suspend fun setSecondScreen(on: Boolean) = update { it.copy(secondScreen = on) }

    suspend fun updateSaveSync(transform: (SaveSyncSettings) -> SaveSyncSettings) = update { it.copy(saveSync = transform(it.saveSync)) }

    /** Show or hide systems (one, a whole maker, or a preset). */
    suspend fun setSystemsVisible(connectionId: String, slugs: Collection<String>, visible: Boolean) = updateConnection(connectionId) { c ->
        c.copy(hiddenSystems = if (visible) c.hiddenSystems - slugs.toSet() else c.hiddenSystems + slugs)
    }

    /** Replace the hidden set wholesale (used by "Show all" and the Nintendo/Sega/Sony preset). */
    suspend fun setHiddenSystems(connectionId: String, hidden: Set<String>) = updateConnection(connectionId) {
        it.copy(hiddenSystems = hidden)
    }

    private suspend fun updateConnection(id: String, transform: (Connection) -> Connection) = update { s ->
        s.copy(connections = s.connections.map { if (it.id == id) transform(it) else it })
    }

    suspend fun clearFolder(connectionId: String, systemKey: String) = update { s ->
        s.copy(folders = s.folders.filterNot { it.connectionId == connectionId && it.systemKey == systemKey })
    }
}
