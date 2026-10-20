package com.gamehub.downloader.data

import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.api.RemoteSaves
import com.gamehub.downloader.api.RomDetail
import com.gamehub.downloader.api.SaveConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Save sync between this device's emulators and GameHub.
 *
 * Two kinds of saves:
 *  - battery: RetroArch "<core>/<ROM name>.srm" ↔ GameHub's per-game battery save (the same slot
 *    its browser player uses), paired by ROM file name;
 *  - device: emulator save folders (PPSSPP SAVEDATA, Vita3K savedata, Azahar title data, DS .sav)
 *    ↔ GameHub device saves, keyed by system + the save's own id, uploaded as a zip.
 *
 * Each save's last synced hash is remembered per connection. A side that changed since then is
 * copied over the other; if both changed it's a conflict for the user to resolve. Local files are
 * backed up (app storage, last 5 per save) before being overwritten, and uploads are conditional so
 * a newer save from another device is never replaced.
 */
class SaveSync(private val app: GameHubApp) {

    enum class Action { UPLOADED, DOWNLOADED, CONFLICT, ERROR }

    @Serializable
    data class LogEntry(val id: String, val title: String, val source: String, val action: Action, val detail: String? = null)

    @Serializable
    data class Conflict(val id: String, val title: String, val source: String, val localModified: Long?, val remoteUpdatedAt: String?)

    @Serializable
    data class Report(
        val at: Long = 0,
        val auto: Boolean = false,
        val checked: Int = 0,
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val unchanged: Int = 0,
        val conflicts: List<Conflict> = emptyList(),
        val errors: List<String> = emptyList(),
        val log: List<LogEntry> = emptyList(),
    ) {
        fun summary(): String = buildList {
            if (uploaded > 0) add("$uploaded uploaded")
            if (downloaded > 0) add("$downloaded downloaded")
            if (conflicts.isNotEmpty()) add("${conflicts.size} need attention")
            if (errors.isNotEmpty()) add("${errors.size} failed")
            if (isEmpty()) add(if (checked == 0) "No saves found" else "All $checked saves up to date")
        }.joinToString(" • ")
    }

    @Serializable
    private data class State(
        val base: Map<String, String> = emptyMap(),
        val report: Report? = null,
        /** Folder each source was synced from; a change starts that source's history afresh. */
        val roots: Map<String, String> = emptyMap(),
        /** RetroArch save states per game (ROM id as text). */
        val states: Map<String, StateRecord> = emptyMap(),
    )

    /** What was synced for one game's save states: per slot the state uploaded, plus the last one downloaded. */
    @Serializable
    private data class StateRecord(
        val uploaded: Map<String, UploadedState> = emptyMap(),
        val downloaded: String? = null,
        /** Hashes of states this device uploaded earlier and later replaced — never "made elsewhere". */
        val retired: List<String> = emptyList(),
    )

    @Serializable
    private data class UploadedState(val sha256: String, val remoteId: Long)

    /** Sync-id prefix of each source's saves, and the folder it reads from. */
    private fun roots(s: AppSettings, connectionId: String): Map<String, String> = buildMap {
        s.saveSync.retroarchSavesUri?.let { put("battery:", it) }
        s.saveSync.retroarchStatesUri?.let { put("state:", it) }
        s.saveSync.ppssppUri?.let { put("device:psp:", it) }
        s.vita3kFolderUri?.let { put("device:vita:", it) }
        s.saveSync.azaharUri?.let { put("device:3ds:", it) }
        s.folderFor(connectionId, "nds")?.let { put("device:nds:", it.treeUri + "/" + it.subPath) }
    }

    data class Status(val running: Boolean = false, val progress: String = "", val report: Report? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // e.g. "AYN Thor" (the model often already starts with the maker's name).
    private val deviceName = Build.MODEL.let { m -> if (m.startsWith(Build.MANUFACTURER, ignoreCase = true)) m else "${Build.MANUFACTURER} $m" }.trim()

    // ---------------------------------------------------------------- state

    private fun stateFile(connectionId: String) = File(app.filesDir, "save_sync/$connectionId.json")

    private fun loadState(connectionId: String): State =
        runCatching { json.decodeFromString<State>(stateFile(connectionId).readText()) }.getOrDefault(State())

    private fun saveState(connectionId: String, state: State) {
        val f = stateFile(connectionId)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(State.serializer(), state))
    }

    /** Loads the last report for the active connection (shown in Settings › Save sync). */
    suspend fun loadLastReport() {
        val c = app.settings.current().activeConnection ?: return
        if (!_status.value.running) _status.value = Status(report = withContext(Dispatchers.IO) { loadState(c.id).report })
    }

    // ---------------------------------------------------------------- public API

    private var lastAuto = 0L

    /** Called when the app comes to the foreground: syncs if enabled, at most every 10 minutes. */
    suspend fun autoSyncIfDue(): Report? {
        val s = app.settings.current()
        if (!s.saveSync.syncOnOpen || s.activeConnection == null || !anySourceConfigured(s)) return null
        val now = System.currentTimeMillis()
        if (now - lastAuto < 10 * 60_000) return null
        lastAuto = now
        return syncAll(auto = true)
    }

    fun anySourceConfigured(s: AppSettings): Boolean = s.saveSync.retroarchSavesUri != null || s.saveSync.retroarchStatesUri != null || s.saveSync.ppssppUri != null ||
        s.saveSync.azaharUri != null || (s.saveSync.vita && s.vita3kFolderUri != null) ||
        (s.saveSync.nds && s.activeConnection?.let { s.folderFor(it.id, "nds") } != null)

    /**
     * Syncs every save from every configured emulator (optionally only one game). [notify] posts the
     * outcome as an Android notification (automatic and per-game syncs; Sync now shows it on screen).
     */
    suspend fun syncAll(auto: Boolean = false, onlyRomId: Long? = null, notify: Boolean = auto || onlyRomId != null): Report = lock.withLock {
        _status.value = _status.value.copy(running = true, progress = "Looking for saves…")
        SaveSyncNotifications.progress(app, 0, 0, "Looking for saves…")
        val report = try {
            withContext(Dispatchers.IO) { runSync(auto, onlyRomId) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Report(at = System.currentTimeMillis(), auto = auto, errors = listOf(e.message ?: e.toString()))
        } finally {
            SaveSyncNotifications.clearProgress(app)
        }
        _status.value = Status(report = report)
        if (notify) SaveSyncNotifications.result(app, report)
        report
    }

    enum class Keep { DEVICE, GAMEHUB }

    /** Resolves a conflict by keeping one side and overwriting the other (the replaced local copy is backed up). */
    suspend fun resolve(conflictId: String, keep: Keep): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val ctx = context() ?: return@withContext "Not connected to a GameHub server"
            val slot = collectSlots(ctx).firstOrNull { it.id == conflictId } ?: return@withContext "That save isn't on this device anymore"
            val state = loadState(ctx.connectionId)
            val remoteHash = ctx.remoteHash(slot)
            val msg = runCatching {
                val newHash = if (keep == Keep.DEVICE) upload(ctx, slot, remoteHash) else download(ctx, slot)
                val report = state.report?.let { r -> r.copy(conflicts = r.conflicts.filterNot { it.id == conflictId }) }
                saveState(ctx.connectionId, state.copy(base = state.base + (slot.id to newHash), report = report))
                _status.value = Status(report = report)
                if (keep == Keep.DEVICE) "Kept this device's save for ${slot.title}" else "Kept GameHub's save for ${slot.title}"
            }.getOrElse { it.message ?: "Couldn't resolve ${slot.title}" }
            msg
        }
    }

    // ---------------------------------------------------------------- engine

    private inner class Ctx(
        val api: GameHubApi,
        val connectionId: String,
        val settings: AppSettings,
        val remote: RemoteSaves,
    ) {
        private val battery = remote.battery.associateBy { it.romId }
        private val device = remote.device.associateBy { it.platform to it.key }
        val romCache = HashMap<Long, RomDetail?>()

        fun remoteHash(slot: Slot): String? = when (slot) {
            is BatterySlot -> battery[slot.romId]?.sha256
            is DeviceSlot -> device[slot.platform to slot.key]?.sha256
        }

        fun remoteUpdatedAt(slot: Slot): String? = when (slot) {
            is BatterySlot -> battery[slot.romId]?.updatedAt
            is DeviceSlot -> device[slot.platform to slot.key]?.updatedAt
        }

        suspend fun rom(id: Long): RomDetail? = romCache.getOrPut(id) { runCatching { api.rom(id) }.getOrNull() }

        /** The library game GameHub linked a device save to (it guesses from title ids and names). */
        fun linkedRom(platform: String, key: String): Long? = device[platform to key]?.romId
        fun remoteTitle(platform: String, key: String): String? = device[platform to key]?.title?.takeIf { it != key }
    }

    private suspend fun context(): Ctx? {
        val s = app.settings.current()
        val c = s.activeConnection ?: return null
        val api = app.api(c)
        return Ctx(api, c.id, s, api.saves())
    }

    private suspend fun runSync(auto: Boolean, onlyRomId: Long?): Report {
        val ctx = context() ?: return Report(at = System.currentTimeMillis(), auto = auto, errors = listOf("Not connected to a GameHub server"))
        val state = loadState(ctx.connectionId)
        val base = state.base.toMutableMap()
        // A different folder for a source isn't the copy we synced before: forget its history so
        // nothing there is mistaken for an already-synced save (it's compared afresh instead).
        val roots = roots(ctx.settings, ctx.connectionId)
        roots.forEach { (prefix, root) -> if (state.roots[prefix] != root) base.keys.removeAll { it.startsWith(prefix) } }
        val stateRecords = if (state.roots["state:"] == roots["state:"]) state.states.toMutableMap() else mutableMapOf()
        val log = mutableListOf<LogEntry>()
        val conflicts = mutableListOf<Conflict>()
        val errors = mutableListOf<String>()
        var unchanged = 0

        val slots = collectSlots(ctx, errors).let { all -> if (onlyRomId == null) all else all.filter { it.romId == onlyRomId } }
        slots.forEachIndexed { i, slot ->
            _status.value = _status.value.copy(progress = "Syncing ${i + 1}/${slots.size}: ${slot.title}")
            SaveSyncNotifications.progress(app, i, slots.size, slot.title)
            runCatching {
                val local = slot.localHash()
                val remote = ctx.remoteHash(slot)
                val last = base[slot.id]
                when {
                    local == null && remote == null -> Unit
                    local == remote -> { base[slot.id] = local!!; unchanged++ }
                    remote == null -> { base[slot.id] = upload(ctx, slot, null); log += entry(slot, Action.UPLOADED) }
                    local == null || local == last -> { base[slot.id] = download(ctx, slot); log += entry(slot, Action.DOWNLOADED) }
                    remote == last -> { base[slot.id] = upload(ctx, slot, remote); log += entry(slot, Action.UPLOADED) }
                    else -> {
                        conflicts += Conflict(slot.id, slot.title, slot.source, slot.localModified(), ctx.remoteUpdatedAt(slot))
                        log += entry(slot, Action.CONFLICT, "Changed on this device and on GameHub")
                    }
                }
            }.also { (slot as? DeviceSlot)?.release() }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is SaveConflictException) {
                    conflicts += Conflict(slot.id, slot.title, slot.source, slot.localModified(), ctx.remoteUpdatedAt(slot))
                    log += entry(slot, Action.CONFLICT, "Changed on GameHub while syncing")
                } else {
                    errors += "${slot.title}: ${e.message ?: e}"
                    log += entry(slot, Action.ERROR, e.message)
                }
            }
        }

        ctx.settings.saveSync.retroarchStatesUri?.let { uri ->
            runCatching { syncStates(ctx, uri, slots.filterIsInstance<BatterySlot>(), stateRecords, log, errors, onlyRomId) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    errors += "RetroArch save states: ${e.message ?: e}"
                }
        }

        val report = Report(
            at = System.currentTimeMillis(),
            auto = auto,
            checked = slots.size,
            uploaded = log.count { it.action == Action.UPLOADED },
            downloaded = log.count { it.action == Action.DOWNLOADED },
            unchanged = unchanged,
            // Keep unresolved conflicts from a single-game sync visible too.
            conflicts = if (onlyRomId == null) conflicts else (state.report?.conflicts.orEmpty().filterNot { c -> slots.any { it.id == c.id } } + conflicts),
            errors = errors,
            log = log,
        )
        saveState(ctx.connectionId, State(base, report, roots, stateRecords))
        return report
    }

    private fun entry(slot: Slot, action: Action, detail: String? = null) = LogEntry(slot.id, slot.title, slot.source, action, detail)

    /** Uploads the local save, only if the server still has [expected]. Returns the synced hash. */
    private suspend fun upload(ctx: Ctx, slot: Slot, expected: String?): String = when (slot) {
        is BatterySlot -> {
            val bytes = slot.read() ?: error("Save file disappeared")
            ctx.api.uploadBatterySave(slot.romId, bytes, expected)
            sha256(bytes)
        }
        is DeviceSlot -> {
            val content = slot.readEntries()
            if (content.isEmpty()) error("Save folder is empty")
            val hash = contentHash(content)
            ctx.api.uploadDeviceSave(slot.platform, slot.key, zip(content), hash, expected, slot.title, slot.romId, deviceName)
            hash
        }
    }

    /** Replaces the local save with GameHub's (backing up the local copy first). Returns the synced hash. */
    private suspend fun download(ctx: Ctx, slot: Slot): String = when (slot) {
        is BatterySlot -> {
            val bytes = ctx.api.downloadBatterySave(slot.romId)
            slot.read()?.let { backup(slot.id, "srm", it) }
            slot.write(bytes)
            sha256(bytes)
        }
        is DeviceSlot -> {
            val entries = unzip(ctx.api.downloadDeviceSave(slot.platform, slot.key))
            if (entries.isEmpty()) error("GameHub's copy is empty")
            slot.readEntries().takeIf { it.isNotEmpty() }?.let { backup(slot.id, "zip", zip(it)) }
            slot.writeEntries(entries)
            contentHash(slot.readEntries())
        }
    }

    private fun backup(id: String, ext: String, bytes: ByteArray) {
        val dir = File(app.filesDir, "save_backups/${id.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120)}")
        dir.mkdirs()
        File(dir, "${System.currentTimeMillis()}.$ext").writeBytes(bytes)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- slots

    /** One syncable save on this device (existing, or a place a GameHub save would go). */
    private sealed class Slot {
        abstract val id: String
        abstract val title: String
        abstract val source: String
        abstract val romId: Long?
        abstract suspend fun localHash(): String?
        abstract fun localModified(): Long?
    }

    /** A RetroArch .srm ↔ a GameHub battery save. */
    private inner class BatterySlot(
        override val romId: Long,
        override val title: String,
        private var file: DocumentFile?,
        private val dir: () -> DocumentFile?,
        val fileName: String,
        /** RetroArch core folder the save is in (null when saves aren't sorted by core). */
        val core: String?,
    ) : Slot() {
        override val id = "battery:$romId"
        override val source = "RetroArch"
        fun read(): ByteArray? = file?.takeIf { it.exists() }?.let { f ->
            app.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
        }
        override suspend fun localHash(): String? = read()?.let { sha256(it) }
        override fun localModified(): Long? = file?.lastModified()?.takeIf { it > 0 }
        fun write(bytes: ByteArray) {
            val target = file?.takeIf { it.exists() }
                ?: (dir() ?: error("RetroArch saves folder unavailable")).let { d ->
                    d.findFile(fileName) ?: d.createFile("application/octet-stream", fileName)
                }
                ?: error("Couldn't create $fileName")
            app.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) } ?: error("Couldn't write $fileName")
            file = target
        }
    }

    /**
     * An emulator save folder ↔ a GameHub device save. [names] are the top-level entries (folders or
     * files) under [parent] that make up the save; after a download they're whatever the zip holds.
     */
    private inner class DeviceSlot(
        val platform: String,
        val key: String,
        override val title: String,
        override val source: String,
        override val romId: Long?,
        private val parent: () -> DocumentFile?,
        private var names: List<String>,
        /** Which top-level entries of a downloaded zip belong to this save (others are ignored). */
        private val accepts: (String) -> Boolean,
    ) : Slot() {
        override val id = "device:$platform:$key"
        private var cached: Map<String, ByteArray>? = null

        fun readEntries(): Map<String, ByteArray> = cached ?: run {
            val p = parent() ?: return emptyMap()
            val files = names.mapNotNull { n -> p.findFile(n)?.let { n to it } }
            // Saves are small; anything this big is game data (installs, caches) and would exhaust memory.
            val total = files.sumOf { (_, f) -> sizeOf(f) }
            if (total > MAX_DEVICE_SAVE) error("too large to sync (${total / (1024 * 1024)} MB) — that's more than save data")
            val out = sortedMapOf<String, ByteArray>()
            files.forEach { (n, f) -> collect(f, n, out) }
            out.also { cached = it }
        }

        /** Drops the in-memory copy once this save has been synced. */
        fun release() { cached = null }

        private fun sizeOf(f: DocumentFile): Long = if (f.isDirectory) f.listFiles().sumOf { sizeOf(it) } else f.length()

        private fun collect(f: DocumentFile, rel: String, out: MutableMap<String, ByteArray>) {
            if (f.isDirectory) f.listFiles().forEach { c -> collect(c, "$rel/${c.name}", out) }
            else if (f.isFile) app.contentResolver.openInputStream(f.uri)?.use { out[rel] = it.readBytes() }
        }

        override suspend fun localHash(): String? = readEntries().takeIf { it.isNotEmpty() }?.let { contentHash(it) }
        override fun localModified(): Long? = parent()?.let { p -> names.mapNotNull { p.findFile(it)?.lastModified() }.maxOrNull() }

        fun writeEntries(all: Map<String, ByteArray>) {
            val entries = all.filterKeys { accepts(it.substringBefore('/')) }
            if (entries.isEmpty()) error("GameHub's copy doesn't contain a $source save for $key")
            val p = parent() ?: error("$source folder unavailable")
            // Replace the save wholesale: remove what was there, then write GameHub's copy.
            val top = entries.keys.map { it.substringBefore('/') }.distinct()
            (names + top).distinct().forEach { p.findFile(it)?.delete() }
            entries.forEach { (rel, bytes) ->
                val segs = rel.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                if (segs.isEmpty()) return@forEach
                var dir = p
                segs.dropLast(1).forEach { s -> dir = dir.findFile(s)?.takeIf { it.isDirectory } ?: dir.createDirectory(s) ?: error("Couldn't create $s") }
                val f = dir.findFile(segs.last()) ?: dir.createFile("application/octet-stream", segs.last()) ?: error("Couldn't create ${segs.last()}")
                app.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes) } ?: error("Couldn't write $rel")
            }
            names = top
            cached = entries
        }
    }

    private suspend fun collectSlots(ctx: Ctx, errors: MutableList<String> = mutableListOf()): List<Slot> {
        val s = ctx.settings.saveSync
        val out = mutableListOf<Slot>()
        suspend fun safely(name: String, block: suspend () -> List<Slot>) {
            runCatching { out += block() }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                errors += "$name: ${e.message ?: e}"
            }
        }
        s.retroarchSavesUri?.let { uri -> safely("RetroArch") { retroarchSlots(ctx, uri) } }
        s.ppssppUri?.let { uri -> safely("PPSSPP") { ppssppSlots(ctx, uri) } }
        if (s.vita) ctx.settings.vita3kFolderUri?.let { uri -> safely("Vita3K") { vitaSlots(ctx, uri) } }
        s.azaharUri?.let { uri -> safely("Azahar") { azaharSlots(ctx, uri) } }
        if (s.nds) ctx.settings.folderFor(ctx.connectionId, "nds")?.let { m -> safely("Nintendo DS") { ndsSlots(ctx, m) } }
        return out
    }

    private fun tree(uri: String): DocumentFile =
        DocumentFile.fromTreeUri(app, Uri.parse(uri))?.takeIf { it.isDirectory } ?: error("folder unavailable — choose it again in Settings › Save sync")

    // RetroArch: <saves>/<core>/<ROM name>.srm (or <saves>/<ROM name>.srm when not sorted by core).
    private suspend fun retroarchSlots(ctx: Ctx, uri: String): List<Slot> {
        val root = tree(uri)
        data class Local(val file: DocumentFile, val core: String?, val base: String)
        val locals = mutableListOf<Local>()
        val coreDirs = HashMap<String, DocumentFile>()
        root.listFiles().forEach { f ->
            if (f.isDirectory) {
                coreDirs[f.name.orEmpty()] = f
                f.listFiles().filter { it.isFile && it.name.orEmpty().endsWith(".srm", true) }
                    .forEach { locals += Local(it, f.name, it.name!!.dropLast(4)) }
            } else if (f.isFile && f.name.orEmpty().endsWith(".srm", true)) {
                locals += Local(f, null, f.name!!.dropLast(4))
            }
        }
        val matches = ctx.api.matchSaves(locals.map { it.base }.distinct())
        val learned = ctx.settings.saveSync.retroarchCoreDirs.toMutableMap()
        val slots = mutableListOf<Slot>()
        val seen = HashSet<Long>()
        for (l in locals) {
            val candidates = matches[l.base].orEmpty()
            val systems = l.core?.let { RetroArchCores.systemsFor(it) }.orEmpty()
            val pick = candidates.firstOrNull { it.platform in systems } ?: candidates.singleOrNull() ?: continue
            if (!seen.add(pick.id)) continue
            if (l.core != null && pick.platform !in learned) learned[pick.platform] = l.core
            val core = l.core
            slots += BatterySlot(pick.id, pick.title.ifBlank { l.base }, l.file, { if (core == null) root else coreDirs[core] }, l.file.name!!, core)
        }
        if (learned != ctx.settings.saveSync.retroarchCoreDirs) app.settings.updateSaveSync { it.copy(retroarchCoreDirs = learned) }

        // GameHub saves for games on this device that RetroArch has no save for yet.
        val sortedByCore = coreDirs.isNotEmpty()
        for (r in ctx.remote.battery) {
            if (r.romId in seen) continue
            val rom = ctx.rom(r.romId) ?: continue
            val fileName = rom.filename ?: continue
            val mapping = ctx.settings.folderFor(ctx.connectionId, rom.platformSlug) ?: continue
            if (app.installed.findGameFiles(mapping, rom.title, fileName).isEmpty()) continue
            val core = learned[rom.platformSlug] ?: RetroArchCores.defaultFor(rom.platformSlug)
            if (sortedByCore && core == null) continue
            val dir: () -> DocumentFile? = if (!sortedByCore) ({ root }) else ({ coreDirs[core] ?: root.findFile(core!!) ?: root.createDirectory(core!!) })
            seen += r.romId
            slots += BatterySlot(r.romId, rom.title, null, dir, fileName.substringBeforeLast('.') + ".srm", if (sortedByCore) core else null)
        }
        return slots
    }

    // PPSSPP: PSP/SAVEDATA/<GAMEID><suffix>/…, grouped per 9-character game id (e.g. ULUS10336).
    private fun ppssppSlots(ctx: Ctx, uri: String): List<Slot> {
        val root = tree(uri)
        val savedataFn: () -> DocumentFile? = {
            root.findFile("SAVEDATA") ?: root.findFile("PSP")?.findFile("SAVEDATA")
                ?: (root.findFile("PSP") ?: root).createDirectory("SAVEDATA")
        }
        val savedata = root.findFile("SAVEDATA") ?: root.findFile("PSP")?.findFile("SAVEDATA")
        val groups = savedata?.listFiles().orEmpty()
            .filter { it.isDirectory && PSP_ID.matches(it.name.orEmpty().take(9)) && !isPspInstallData(it.name.orEmpty()) }
            .groupBy { it.name!!.take(9).uppercase() }
        val slots = groups.map { (id, dirs) ->
            val title = dirs.firstNotNullOfOrNull { d ->
                d.findFile("PARAM.SFO")?.let { f -> app.contentResolver.openInputStream(f.uri)?.use { LaunchFileWriter.sfoString(it.readBytes(), "TITLE") } }
            } ?: ctx.remoteTitle("psp", id) ?: id
            DeviceSlot("psp", id, title, "PPSSPP", ctx.linkedRom("psp", id), savedataFn, dirs.map { it.name!! }, pspAccepts(id))
        }
        val have = groups.keys
        return slots + ctx.remote.device.filter { it.platform == "psp" && it.key !in have }.map { r ->
            DeviceSlot("psp", r.key, r.title ?: r.key, "PPSSPP", r.romId, savedataFn, emptyList(), pspAccepts(r.key))
        }
    }

    // Vita3K: ux0/user/00/savedata/<TITLE_ID>/…  (restored only for games installed in Vita3K).
    private fun vitaSlots(ctx: Ctx, uri: String): List<Slot> {
        val root = tree(uri)
        val savedataFn: () -> DocumentFile? = {
            var d = root.findFile("ux0") ?: error("ux0 not found in the Vita3K folder")
            for (seg in listOf("user", "00", "savedata")) d = d.findFile(seg) ?: d.createDirectory(seg) ?: error("Couldn't create $seg")
            d
        }
        val savedata = root.findFile("ux0")?.findFile("user")?.findFile("00")?.findFile("savedata")
        val apps = app.vita3k.apps.value
        val local = savedata?.listFiles().orEmpty().filter { it.isDirectory && VITA_ID.matches(it.name.orEmpty()) }.map { it.name!! }
        val slots = local.map { id -> DeviceSlot("vita", id, apps[id]?.title ?: id, "Vita3K", ctx.linkedRom("vita", id), savedataFn, listOf(id)) { it == id } }
        return slots + ctx.remote.device.filter { it.platform == "vita" && it.key !in local && it.key in apps }.map { r ->
            DeviceSlot("vita", r.key, apps[r.key]?.title ?: r.title ?: r.key, "Vita3K", r.romId, savedataFn, emptyList()) { it == r.key }
        }
    }

    // Azahar: sdmc/Nintendo 3DS/<id0>/<id1>/title/<high>/<low>/data/… (key = 16-hex title id).
    private fun azaharSlots(ctx: Ctx, uri: String): List<Slot> {
        val root = tree(uri)
        val id1 = root.findFile("sdmc")?.findFile("Nintendo 3DS")?.listFiles()?.firstOrNull { it.isDirectory && HEX32.matches(it.name.orEmpty()) }
            ?.listFiles()?.firstOrNull { it.isDirectory && HEX32.matches(it.name.orEmpty()) }
        val title = id1?.findFile("title")
        val slots = mutableListOf<Slot>()
        val have = HashSet<String>()
        title?.listFiles().orEmpty().filter { it.isDirectory && HEX8.matches(it.name.orEmpty()) }.forEach { high ->
            high.listFiles().filter { it.isDirectory && HEX8.matches(it.name.orEmpty()) }.forEach { low ->
                if (low.findFile("data")?.listFiles()?.isNotEmpty() == true) {
                    val key = (high.name + low.name).uppercase()
                    have += key
                    slots += DeviceSlot("3ds", key, "3DS title $key", "Azahar", ctx.linkedRom("3ds", key), { low }, listOf("data")) { it == "data" }
                }
            }
        }
        if (id1 != null) ctx.remote.device.filter { it.platform == "3ds" && it.key !in have && HEX16.matches(it.key) }.forEach { r ->
            val h = r.key.take(8).lowercase(); val l = r.key.drop(8).lowercase()
            slots += DeviceSlot("3ds", r.key, r.title ?: "3DS title ${r.key}", "Azahar", r.romId, {
                val t = id1.findFile("title") ?: id1.createDirectory("title")
                val hd = t?.findFile(h) ?: t?.createDirectory(h)
                hd?.findFile(l) ?: hd?.createDirectory(l)
            }, emptyList()) { it == "data" }
        }
        return slots
    }

    // Nintendo DS (melonDS / DraStic): "<ROM name>.sav" next to the ROM in the DS download folder.
    private suspend fun ndsSlots(ctx: Ctx, mapping: FolderMapping): List<Slot> {
        val dir = FolderStorage.resolve(app, mapping) ?: error("DS folder unavailable")
        val files = dir.listFiles()
        val saves = files.filter { it.isFile && it.name.orEmpty().endsWith(".sav", true) }.map { it.name!!.dropLast(4) }
        val roms = files.filter { it.isFile && ROM_EXT.matches(it.name.orEmpty()) }.map { it.name!!.substringBeforeLast('.') }.toSet()
        val matches = ctx.api.matchSaves(saves, listOf("nds"))
        val slots = saves.map { base ->
            val m = matches[base]?.firstOrNull()
            DeviceSlot("nds", base, m?.title ?: base, "Nintendo DS", m?.id ?: ctx.linkedRom("nds", base), { dir }, listOf("$base.sav")) { it == "$base.sav" }
        }
        return slots + ctx.remote.device.filter { it.platform == "nds" && it.key !in saves && it.key in roms }.map { r ->
            DeviceSlot("nds", r.key, r.title ?: r.key, "Nintendo DS", r.romId, { dir }, emptyList()) { it == "${r.key}.sav" }
        }
    }

    /**
     * RetroArch save states ↔ GameHub save states (the browser player's list), best effort: both are
     * the same libretro state ("RASTATE") when the core versions match. Up: each RetroArch slot that's
     * new is uploaded as "RetroArch slot N · <device>", replacing that slot's previous upload. Down: the
     * newest GameHub state made elsewhere goes into slot 9 — only if slot 9 is empty or was filled by sync.
     * Screenshots travel with them (RetroArch's "<state>.png" thumbnails).
     */
    private suspend fun syncStates(
        ctx: Ctx,
        uri: String,
        batteries: List<BatterySlot>,
        records: MutableMap<String, StateRecord>,
        log: MutableList<LogEntry>,
        errors: MutableList<String>,
        onlyRomId: Long?,
    ) {
        val root = tree(uri)
        data class LocalState(val file: DocumentFile, val core: String?, val base: String, val slot: String)
        val locals = mutableListOf<LocalState>()
        val coreDirs = HashMap<String, DocumentFile>()
        fun scan(dir: DocumentFile, core: String?) = dir.listFiles().forEach { f ->
            val m = STATE_FILE.matchEntire(f.name.orEmpty()) ?: return@forEach
            if (f.isFile) locals += LocalState(f, core, m.groupValues[1], m.groupValues[2].removePrefix("."))
        }
        root.listFiles().forEach { f -> if (f.isDirectory) { coreDirs[f.name.orEmpty()] = f; scan(f, f.name) } }
        scan(root, null)

        // Pair state files with games: by the battery saves already matched, else by file name.
        val byBase = batteries.associateBy { it.fileName.substringBeforeLast('.').lowercase() }
        val unmatched = locals.map { it.base }.distinct().filter { it.lowercase() !in byBase }
        val matches = if (unmatched.isEmpty()) emptyMap() else ctx.api.matchSaves(unmatched)
        data class Game(val romId: Long, val title: String, val base: String, val core: String?)
        val games = LinkedHashMap<Long, Game>()
        locals.forEach { l ->
            val b = byBase[l.base.lowercase()]
            val pick = b?.let { Game(it.romId, it.title, l.base, l.core) } ?: run {
                val c = matches[l.base].orEmpty()
                val systems = l.core?.let { RetroArchCores.systemsFor(it) }.orEmpty()
                (c.firstOrNull { it.platform in systems } ?: c.singleOrNull())?.let { Game(it.id, it.title.ifBlank { l.base }, l.base, l.core) }
            }
            if (pick != null) games.putIfAbsent(pick.romId, pick)
        }
        // Games played in RetroArch here (they have a battery save) can receive GameHub's states too.
        val sortedByCore = coreDirs.isNotEmpty()
        batteries.forEach { b ->
            games.putIfAbsent(b.romId, Game(b.romId, b.title, b.fileName.substringBeforeLast('.'), if (sortedByCore) b.core else null))
        }

        for (g in games.values) {
            if (onlyRomId != null && g.romId != onlyRomId) continue
            runCatching {
                val rec = records[g.romId.toString()] ?: StateRecord()
                val remote = ctx.api.states(g.romId)
                if (remote.any { it.sha256 == null }) error("GameHub is too old for save state sync (needs 0.1.9)")
                val dirFn: () -> DocumentFile = {
                    if (g.core == null) root else coreDirs[g.core] ?: root.findFile(g.core) ?: root.createDirectory(g.core) ?: error("Couldn't create ${g.core}")
                }
                val mine = locals.filter { it.base.equals(g.base, ignoreCase = true) && (it.core ?: "") == (g.core ?: "") }
                val localBySlot = mine.associate { l ->
                    l.slot to (l to readState(l.file))
                }
                val uploaded = rec.uploaded.toMutableMap()
                var downloaded = rec.downloaded
                val retired = rec.retired.toMutableList()
                val deletedIds = HashSet<Long>()
                val remoteHashes = remote.mapNotNull { it.sha256 }.toSet()

                // Up: new RetroArch states (not the one sync put in the download slot).
                for ((slot, pair) in localBySlot) {
                    val (l, raw) = pair
                    val hash = sha256(raw)
                    if (slot == DOWNLOAD_SLOT && hash == downloaded) continue
                    if (hash in remoteHashes) { remote.firstOrNull { it.sha256 == hash }?.let { uploaded[slot] = UploadedState(hash, it.id) }; continue }
                    if (uploaded[slot]?.sha256 == hash) continue // uploaded before and since removed on GameHub: leave it removed
                    val shot = l.file.parentFile?.findFile("${l.file.name}.png")?.let { f -> app.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } }
                    val label = "RetroArch ${if (slot == "auto") "auto" else "slot ${slot.ifEmpty { "0" }}"} - $deviceName".take(64)
                    val newId = ctx.api.uploadState(g.romId, raw, shot, label)
                    uploaded[slot]?.let { old ->
                        retired += old.sha256
                        if (remote.any { it.id == old.remoteId }) { ctx.api.deleteState(old.remoteId); deletedIds += old.remoteId }
                    }
                    uploaded[slot] = UploadedState(hash, newId)
                    log += LogEntry("state:${g.romId}:$slot", g.title, "RetroArch states", Action.UPLOADED, label)
                }

                // Down: the newest state made elsewhere, into the download slot.
                val ours = uploaded.values.map { it.sha256 }.toSet() + retired
                val localHashes = localBySlot.values.map { sha256(it.second) }.toSet()
                // The newest state made elsewhere; if this device already has it (any slot), there's nothing to do.
                val newest = remote.filter { it.id !in deletedIds && it.sha256 !in ours }.maxByOrNull { it.createdAt }
                if (newest != null && newest.sha256 != downloaded && newest.sha256 !in localHashes) {
                    val current = localBySlot[DOWNLOAD_SLOT]?.second?.let { sha256(it) }
                    if (current == null || current == downloaded) {
                        val raw = ctx.api.downloadState(newest.id)
                        val dir = dirFn()
                        val name = "${g.base}.state$DOWNLOAD_SLOT"
                        writeFile(dir, name, raw)
                        if (newest.hasScreenshot != 0) ctx.api.downloadStateScreenshot(newest.id)?.let { writeFile(dir, "$name.png", it) }
                        downloaded = newest.sha256
                        log += LogEntry("state:${g.romId}:$DOWNLOAD_SLOT", g.title, "RetroArch states", Action.DOWNLOADED, "in slot $DOWNLOAD_SLOT")
                    }
                }
                records[g.romId.toString()] = StateRecord(uploaded, downloaded, retired.distinct().takeLast(50))
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                errors += "${g.title} (save states): ${e.message ?: e}"
                log += LogEntry("state:${g.romId}", g.title, "RetroArch states", Action.ERROR, e.message)
            }
        }
    }

    /** A RetroArch state as raw libretro data (RetroArch compresses them as RZIP when that's enabled). */
    private fun readState(f: DocumentFile): ByteArray {
        if (f.length() > 64L * 1024 * 1024) error("${f.name} is too large")
        val bytes = app.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } ?: error("Couldn't read ${f.name}")
        return unrzip(bytes)
    }

    private fun writeFile(dir: DocumentFile, name: String, bytes: ByteArray) {
        val f = dir.findFile(name) ?: dir.createFile("application/octet-stream", name) ?: error("Couldn't create $name")
        app.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes) } ?: error("Couldn't write $name")
    }

    /**
     * Some PSP games install hundreds of MB of game data into SAVEDATA (e.g. "ULJS00266INST" next to
     * the real save "ULJS00266SYSTEM"). That isn't save data, so it's never synced.
     */
    private fun isPspInstallData(name: String): Boolean {
        val n = name.uppercase()
        return n.endsWith("INST") || n.endsWith("INSTALL") || n.contains("INSTALLDATA")
    }

    /** PSP saves are the SAVEDATA folders whose names start with the game id (ULUS10336DATA00, …). */
    private fun pspAccepts(id: String): (String) -> Boolean = { it.startsWith(id, ignoreCase = true) && !isPspInstallData(it) }

    companion object {
        private val PSP_ID = Regex("[A-Z]{4}\\d{5}")
        /** "<ROM name>.state", ".state1"…".state9", ".state.auto" — not thumbnails (".png"). */
        private val STATE_FILE = Regex("(.+)\\.state(\\d|\\.auto)?", RegexOption.IGNORE_CASE)
        /** RetroArch slot that receives the newest GameHub state made elsewhere. */
        const val DOWNLOAD_SLOT = "9"

        /** Decodes RetroArch's RZIP ("#RZIPv\u0001#", chunked zlib); anything else is returned as-is. */
        fun unrzip(bytes: ByteArray): ByteArray {
            val magic = "#RZIPv".toByteArray()
            if (bytes.size < 20 || !bytes.copyOfRange(0, 6).contentEquals(magic) || bytes[7] != '#'.code.toByte()) return bytes
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.position(8)
            buf.int // chunk size
            val total = buf.long
            if (total < 0 || total > 256L * 1024 * 1024) error("Unexpected state size")
            val out = ByteArrayOutputStream(total.toInt())
            val inflater = java.util.zip.Inflater()
            val chunk = ByteArray(64 * 1024)
            try {
                while (buf.remaining() >= 4) {
                    val len = buf.int
                    if (len <= 0 || len > buf.remaining()) error("Corrupt RZIP state")
                    inflater.reset()
                    inflater.setInput(bytes, buf.position(), len)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(chunk)
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        out.write(chunk, 0, n)
                    }
                    buf.position(buf.position() + len)
                }
            } finally {
                inflater.end()
            }
            if (out.size().toLong() != total) error("Corrupt RZIP state")
            return out.toByteArray()
        }

        /** Largest device save synced (a whole Vita/3DS save folder is a few MB at most). */
        private const val MAX_DEVICE_SAVE = 128L * 1024 * 1024
        private val VITA_ID = Regex("PCS[A-Z]\\d{5}")
        private val HEX8 = Regex("[0-9a-fA-F]{8}")
        private val HEX16 = Regex("[0-9a-fA-F]{16}")
        private val HEX32 = Regex("[0-9a-fA-F]{32}")
        private val ROM_EXT = Regex(".+\\.(nds|dsi|zip|7z)", RegexOption.IGNORE_CASE)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Hash of a save's contents (paths + file hashes), independent of how it's zipped. */
        fun contentHash(entries: Map<String, ByteArray>): String {
            val md = MessageDigest.getInstance("SHA-256")
            entries.toSortedMap().forEach { (path, bytes) ->
                md.update(path.toByteArray(Charsets.UTF_8)); md.update(0)
                md.update(sha256(bytes).toByteArray()); md.update('\n'.code.toByte())
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                entries.toSortedMap().forEach { (path, bytes) -> z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
            }
        }.toByteArray()

        fun unzip(bytes: ByteArray): Map<String, ByteArray> {
            val out = sortedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                generateSequence { z.nextEntry }.forEach { e ->
                    val name = e.name.replace('\\', '/').trimStart('/')
                    if (!e.isDirectory && name.split('/').none { it == ".." }) out[name] = z.readBytes()
                }
            }
            return out
        }
    }
}

/** RetroArch core save folders (when "sort saves by core" is on) ↔ GameHub systems. */
object RetroArchCores {
    private val bySystem = mapOf(
        "nes" to "FCEUmm", "fds" to "FCEUmm", "famicom" to "FCEUmm",
        "snes" to "Snes9x", "sfc" to "Snes9x",
        "gb" to "Gambatte", "gbc" to "Gambatte", "gba" to "mGBA",
        "n64" to "Mupen64Plus-Next",
        "genesis" to "Genesis Plus GX", "megadrive" to "Genesis Plus GX", "sms" to "Genesis Plus GX",
        "gg" to "Genesis Plus GX", "segacd" to "Genesis Plus GX", "sega32x" to "PicoDrive",
        "psx" to "PCSX-ReARMed", "pce" to "Beetle PCE Fast", "pcecd" to "Beetle PCE Fast",
        "ngp" to "Beetle NeoPop", "wonderswan" to "Beetle WonderSwan", "atari2600" to "Stella",
        "lynx" to "Handy", "vb" to "Beetle VB", "nds" to "melonDS",
    )
    private val coreSystems = listOf(
        listOf("gambatte", "sameboy", "gearboy", "tgb dual") to setOf("gb", "gbc"),
        listOf("mgba", "vba-m", "vba next", "gpsp") to setOf("gba", "gb", "gbc"),
        listOf("snes9x", "bsnes", "mesen-s") to setOf("snes", "sfc", "satellaview", "sufami"),
        listOf("fceumm", "nestopia", "mesen", "quicknes") to setOf("nes", "fds", "famicom"),
        listOf("mupen64plus", "parallel n64") to setOf("n64", "n64dd"),
        listOf("genesis plus gx", "picodrive", "blastem", "gearsystem") to setOf("genesis", "megadrive", "sms", "gg", "segacd", "sega32x", "sg1000"),
        listOf("pcsx", "beetle psx", "swanstation", "duckstation") to setOf("psx"),
        listOf("beetle pce", "beetle supergrafx") to setOf("pce", "pcecd", "supergrafx"),
        listOf("melonds", "desmume") to setOf("nds"),
        listOf("beetle neopop", "race") to setOf("ngp", "ngpc"),
        listOf("beetle wonderswan") to setOf("wonderswan", "wonderswancolor"),
        listOf("stella") to setOf("atari2600"),
        listOf("handy", "beetle lynx") to setOf("lynx"),
        listOf("beetle vb") to setOf("vb"),
    )

    fun defaultFor(system: String): String? = bySystem[system]

    fun systemsFor(core: String): Set<String> {
        val c = core.lowercase()
        return coreSystems.filter { (prefixes, _) -> prefixes.any { c.startsWith(it) } }.flatMap { it.second }.toSet()
    }
}
