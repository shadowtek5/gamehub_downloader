package com.gamehub.downloader.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.gamehub.downloader.api.ApiException
import com.gamehub.downloader.api.BiosSystem
import com.gamehub.downloader.api.EmulatorCatalog
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.api.RemoteBiosTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One BIOS file GameHub has, as it should be written into one emulator's folder. */
data class BiosItem(
    /** Path inside the destination folder, e.g. "scph5501.bin" or "pcsx2/bios/ps2.bin". */
    val path: String,
    val rowId: Long,
    val size: Long,
    val system: String,
    val description: String?,
    val onDevice: Boolean,
)

/**
 * An emulator on this device that reads BIOS files from a folder: RetroArch (its system folder,
 * files keep the core's exact sub-paths) or a standalone emulator (files go straight in the folder).
 */
data class BiosDestination(
    val id: String,
    val name: String,
    /** Where the folder is, in plain words. */
    val where: String,
    /** Folder the user picked for it, if any. */
    val folderUri: String?,
    /** A place to open the folder picker at (e.g. SD card › Azahar › sysdata). */
    val suggested: String?,
    val systems: List<String>,
    val items: List<BiosItem>,
) {
    val toInstall: List<BiosItem> get() = items.filter { !it.onDevice }
}

/** Copies BIOS / firmware files from GameHub into the emulators installed on this device. */
class Bios(private val context: Context) {

    /** True when [treeUri] looks like RetroArch's "system" folder. */
    fun looksLikeSystemFolder(treeUri: String): Boolean {
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull() ?: return false
        return id.substringAfterLast('/').substringAfter(':').equals("system", ignoreCase = true)
    }

    /**
     * Every emulator installed here that can use BIOS files GameHub has, with what's already in
     * its folder. [catalog] is GameHub's emulator catalog (null on older servers: built-in list).
     */
    suspend fun destinations(api: GameHubApi, catalog: EmulatorCatalog?, settings: AppSettings): List<BiosDestination> {
        val systems: List<BiosSystem> = api.bios()
        val bySlug = systems.associateBy { it.slug }
        val installed = installedPackages()
        val volume = preferredVolume(settings)
        val out = mutableListOf<BiosDestination>()

        // RetroArch: every system, exact libretro file names (with core sub-folders).
        if (Emulators.retroarchPackage(context) != null) {
            val uri = settings.retroarchSystemUri?.takeIf { FolderStorage.hasAccess(context, it) }
            val present = withContext(Dispatchers.IO) { uri?.let { listAll(it) } ?: emptyMap() }
            val items = systems.flatMap { sys -> itemsFor(sys, flatten = false, present) }
            if (items.isNotEmpty()) out += BiosDestination(
                "retroarch", "RetroArch", "RetroArch's system folder (Internal storage › RetroArch › system)",
                settings.retroarchSystemUri, "primary:RetroArch/system", systems.filter { s -> items.any { it.system == s.name } }.map { it.name }, items,
            )
        }

        // Standalone emulators: that system's files, straight into the folder the emulator reads.
        for (target in catalog?.bios ?: BUILT_IN) {
            if (target.packages.none { pattern -> installed.any { matches(pattern, it) } }) continue
            val folder = settings.biosFolders[target.id]?.takeIf { FolderStorage.hasAccess(context, it) }
            val present = withContext(Dispatchers.IO) { folder?.let { listAll(it) } ?: emptyMap() }
            val items = target.platforms.mapNotNull { bySlug[it] }.flatMap { itemsFor(it, flatten = true, present) }.distinctBy { it.path.lowercase() }
            if (items.isEmpty()) continue
            out += BiosDestination(
                target.id, target.name, target.where, settings.biosFolders[target.id],
                SUGGESTED[target.id]?.let { "$volume:$it" }, target.platforms.mapNotNull { bySlug[it]?.name }.distinct(), items,
            )
        }
        return out
    }

    private fun itemsFor(sys: BiosSystem, flatten: Boolean, present: Map<String, Long>): List<BiosItem> {
        fun path(file: String) = if (flatten) file.replace('\\', '/').substringAfterLast('/') else file
        fun onDevice(p: String, size: Long) = present[p.lowercase()]?.let { it == size || size == 0L } == true
        // [BiosFile.size] is the size a correct dump has; unverified uploads may differ, so only the name counts for them.
        return sys.files.filter { it.have && it.rowId != null }.map { f ->
            val p = path(f.file)
            BiosItem(p, f.rowId!!, if (f.verified) f.size else 0, sys.name, f.description ?: f.region, onDevice(p, if (f.verified) f.size else 0))
        } + sys.extras.map { e ->
            val p = path(e.filename)
            BiosItem(p, e.id, e.size, sys.name, null, onDevice(p, e.size))
        }
    }

    /** Downloads [items] into [folderUri] (creating sub-folders). Returns how many were written. */
    suspend fun install(api: GameHubApi, folderUri: String, items: List<BiosItem>): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: throw ApiException(0, "Can't open the folder")
        var done = 0
        for (item in items) {
            val parts = item.path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." && it != "." }
            if (parts.isEmpty()) continue
            var dir: DocumentFile? = root
            for (seg in parts.dropLast(1)) {
                dir = dir?.findFile(seg)?.takeIf { it.isDirectory } ?: dir?.createDirectory(seg)
            }
            val d = dir ?: continue
            val name = parts.last()
            d.findFile(name)?.delete()
            val file = d.createFile("application/octet-stream", name) ?: continue
            val req = api.authed(api.firmwareUrl(item.rowId)).build()
            val ok = runCatching {
                com.gamehub.downloader.GameHubApp.instance.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
                    context.contentResolver.openOutputStream(file.uri, "wt")!!.use { out -> resp.body!!.byteStream().copyTo(out) }
                }
            }.isSuccess
            // Some providers append ".bin" when the name has no known extension; keep the exact name.
            if (ok && file.name != name) runCatching { file.renameTo(name) }
            if (ok) done++ else file.delete()
        }
        done
    }

    private fun installedPackages(): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA).map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** "xyz.aethersx2.*" and "com.armsx2*" are prefixes; anything else must match exactly. */
    private fun matches(pattern: String, pkg: String): Boolean =
        if (pattern.endsWith("*")) pkg.startsWith(pattern.removeSuffix("*")) else pkg == pattern

    /** The SD card if any folder the user picked is on one, else internal storage. */
    private fun preferredVolume(settings: AppSettings): String {
        val uris = settings.folders.map { it.treeUri } + settings.connections.mapNotNull { it.baseFolderUri }
        return uris.firstNotNullOfOrNull { u ->
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(u)).substringBefore(':') }.getOrNull()?.takeIf { it != "primary" && it.isNotEmpty() }
        } ?: "primary"
    }

    /** Lower-cased relative path → size for every file under a folder (a few levels deep). */
    private fun listAll(treeUri: String): Map<String, Long> {
        val tree = Uri.parse(treeUri)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Long>()
        val stack = ArrayDeque<Pair<String, String>>().apply { add(rootId to "") }
        while (stack.isNotEmpty()) {
            val (docId, prefix) = stack.removeLast()
            if (prefix.count { it == '/' } > 4) continue
            runCatching {
                context.contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(c.getString(0) to rel)
                        else out[rel.lowercase()] = if (c.isNull(3)) 0 else c.getLong(3)
                    }
                }
            }
        }
        return out
    }

    companion object {
        /** Where to open the folder picker for each emulator (relative to the SD card or internal storage). */
        private val SUGGESTED = mapOf(
            "citra" to "Azahar/sysdata",
            "switch" to "Eden",
            "duckstation" to "BIOS",
            "ps2" to "BIOS",
            "melonds" to "BIOS",
            "flycast" to "Flycast/data",
            "saturn" to "yabause/bios",
            "drastic" to "DraStic/system",
        )

        /** Used when the GameHub server is older than 0.1.11 (no emulator catalog). Mirrors GameHub's EMULATOR_BIOS. */
        val BUILT_IN = listOf(
            RemoteBiosTarget("duckstation", "DuckStation", listOf("com.github.stenzek.duckstation"), listOf("psx"),
                "The folder chosen in DuckStation › Settings › BIOS (BIOS directory)."),
            RemoteBiosTarget("ps2", "AetherSX2 / NetherSX2 / ARMSX2", listOf("xyz.aethersx2.*", "com.armsx2*", "come.nanodata.armsx2*"), listOf("ps2"),
                "Any folder you then pick in the emulator's BIOS settings (Settings › System › BIOS)."),
            RemoteBiosTarget("melonds", "melonDS", listOf("me.magnum.melon*"), listOf("nds"),
                "The folder chosen for BIOS and firmware in melonDS › Settings › System (bios7.bin, bios9.bin, firmware.bin)."),
            RemoteBiosTarget("drastic", "DraStic", listOf("com.dsemu.drastic"), listOf("nds"),
                "DraStic's system folder (DraStic/system). Optional: DraStic runs without them."),
            RemoteBiosTarget("flycast", "Flycast", listOf("com.flycast.emulator", "com.reicast.emulator"), listOf("dreamcast", "naomi", "naomi2", "atomiswave"),
                "Flycast's data folder (inside the home folder set in Flycast › Settings › General)."),
            RemoteBiosTarget("citra", "Azahar / Lime3DS / Citra", listOf("org.azahar_emu.azahar*", "io.github.lime3ds.android", "org.citra.citra_emu*", "io.github.borked3ds.android", "org.gamerytb.lemonade*"), listOf("3ds"),
                "The sysdata folder inside the emulator's user folder."),
            RemoteBiosTarget("saturn", "Yaba Sanshiro", listOf("org.devmiyax.yabasanshioro2*", "org.uoyabause.*"), listOf("saturn"),
                "The BIOS folder set in Yaba Sanshiro's settings (yabause/bios)."),
            RemoteBiosTarget("switch", "Eden / Citron / Sudachi / yuzu", listOf("dev.eden.*", "org.citron.*", "org.sudachi.*", "org.yuzu.*", "dev.suyu.*"), listOf("switch"),
                "A folder you then install keys and firmware from in the emulator (Settings › Install keys / Install firmware)."),
            RemoteBiosTarget("dolphin", "Dolphin", listOf("org.dolphinemu.*", "org.mm.j*", "org.dolphin.*"), listOf("gamecube"),
                "Optional. Dolphin's user folder › GC › USA, EUR or JAP as IPL.bin (for the GameCube boot animation)."),
        )
    }
}
