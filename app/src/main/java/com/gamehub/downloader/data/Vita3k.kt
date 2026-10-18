package com.gamehub.downloader.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.gamehub.downloader.download.ArchiveExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/** A game installed in Vita3K (a folder under ux0/app). */
data class Vita3kApp(val titleId: String, val title: String, val appDir: Uri)

/**
 * Vita3K integration. Its Android build only accepts a launch request (-r TITLE_ID) from other apps,
 * so installs and uninstalls are done directly in its storage folder (the one holding ux0), the
 * same layout Vita3K itself writes; Vita3K rebuilds its app list when that content changes.
 */
class Vita3k(private val context: Context, private val settings: SettingsRepository) {

    companion object {
        const val PACKAGE = "org.vita3k.emulator"
        private const val EMULATOR = "org.vita3k.emulator.Emulator"
        /** Top-level folders in "installed layout" zips that map straight into ux0. */
        private val UX0_DIRS = setOf("app", "patch", "addcont", "license")

        /**
         * True if a SELF (eboot.bin / .suprx) header is retail-encrypted, i.e. Vita3K needs the game's
         * license to run it. Decrypted dumps carry sdk_type 0xC0 (fake-signed); anything else is encrypted.
         */
        fun isEncryptedSelf(header: ByteArray): Boolean {
            if (header.size < 10 || header[0] != 'S'.code.toByte() || header[1] != 'C'.code.toByte() || header[2] != 'E'.code.toByte()) return false
            val sdkType = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
            return sdkType != 0xC0
        }

        /** Content ID stored in a license (.rif / work.bin) at offset 0x10, e.g. UP0438-PCSE01315_00-0000000000000001. */
        fun rifContentId(rif: ByteArray): String? =
            if (rif.size < 0x40) null
            else String(rif, 0x10, 0x30, Charsets.US_ASCII).trimEnd('\u0000')
                .takeIf { Regex("""^[A-Z]{2}\d{4}-[A-Z]{4}\d{5}_\d{2}-[A-Z0-9]{16}$""").matches(it) }
    }

    private val _apps = MutableStateFlow<Map<String, Vita3kApp>>(emptyMap())
    /** Installed games keyed by title ID. */
    val apps: StateFlow<Map<String, Vita3kApp>> = _apps.asStateFlow()
    private val lock = Mutex()

    /** Installs in progress, keyed by zip file name → (bytes done, bytes total). */
    private val _installing = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    val installing: StateFlow<Map<String, Pair<Long, Long>>> = _installing.asStateFlow()

    fun isAppInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrElse { e -> if (e is PackageManager.NameNotFoundException) false else false }

    /** Starts a game in Vita3K by title ID (the launch form Vita3K accepts from other apps). */
    fun play(titleId: String): Boolean = runCatching {
        context.startActivity(
            Intent().setComponent(ComponentName(PACKAGE, EMULATOR))
                .putExtra("AppStartParameters", arrayOf("-r", titleId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    private suspend fun ux0(): DocumentFile? {
        val uri = settings.current().vita3kFolderUri ?: return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
        return root.findFile("ux0")?.takeIf { it.isDirectory }
    }

    /** True if [treeUri] looks like a Vita3K storage folder (contains ux0). */
    fun isVita3kFolder(treeUri: String): Boolean =
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.findFile("ux0")?.isDirectory == true

    /** Rescans ux0/app and reads each game's name from its param.sfo. */
    suspend fun refresh() = lock.withLock {
        val found = withContext(Dispatchers.IO) {
            val app = ux0()?.findFile("app") ?: return@withContext emptyMap()
            app.listFiles().filter { it.isDirectory }.mapNotNull { dir ->
                val id = dir.name ?: return@mapNotNull null
                val title = runCatching {
                    dir.findFile("sce_sys")?.findFile("param.sfo")?.let { sfo ->
                        context.contentResolver.openInputStream(sfo.uri)?.use { sfoTitle(it.readBytes()) }
                    }
                }.getOrNull() ?: id
                id to Vita3kApp(id, title, dir.uri)
            }.toMap()
        }
        _apps.value = found
    }

    /**
     * Installs a game zip into Vita3K. "Installed layout" zips (app/, patch/, addcont/ at the top)
     * are copied into ux0 as-is; zips with the game at the root (sce_sys/param.sfo) go to
     * ux0/app/<TITLE_ID>/. Returns the title ID.
     */
    suspend fun install(zip: DocumentFile, onProgress: (Long, Long) -> Unit = { _, _ -> }): String = withContext(Dispatchers.IO) {
        val key = zip.name ?: zip.uri.toString()
        _installing.value = _installing.value + (key to (0L to 0L))
        try {
            installInternal(zip) { d, t ->
                _installing.value = _installing.value + (key to (d to t))
                onProgress(d, t)
            }
        } finally {
            _installing.value = _installing.value - key
        }
    }

    private suspend fun installInternal(zip: DocumentFile, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val ux0 = ux0() ?: error("Vita3K folder isn't set (Settings › Vita3K)")
        // One pass over the zip's table of contents: entry names (for the layout), the title ID, and
        // what's needed to tell whether Vita3K will be able to run it (encrypted eboot + license).
        val info = context.contentResolver.openFileDescriptor(zip.uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(ch).get().use { z ->
                    val entries = z.entries.toList()
                    val names = entries.map { it.name.replace('\\', '/') }
                    val tid = LaunchFileWriter.titleIdIn(z) ?: zip.name?.let { LaunchFileWriter.titleIdFromName(it) }
                    val layout = names.any { it.substringBefore('/').lowercase() in UX0_DIRS && it.contains('/') }
                    val prefix = if (layout) "app/${tid.orEmpty().lowercase()}/" else ""
                    fun read(path: String, max: Int = Int.MAX_VALUE) =
                        entries.firstOrNull { it.name.replace('\\', '/').lowercase() == prefix + path }?.let { e ->
                            z.getInputStream(e).use { s -> if (max == Int.MAX_VALUE) s.readBytes() else s.readNBytes(max) }
                        }
                    ZipInfo(
                        names = names,
                        titleId = tid,
                        installedLayout = layout,
                        ebootHeader = read("eboot.bin", 16),
                        workBin = read("sce_sys/package/work.bin"),
                        contentId = read("sce_sys/param.sfo")?.let { LaunchFileWriter.sfoString(it, "CONTENT_ID") },
                        hasLicenseDir = names.any { it.lowercase().startsWith("license/") && it.lowercase().endsWith(".rif") },
                    )
                }
            }
        } ?: error("Can't open ${zip.name}")
        val id = info.titleId ?: error("No title ID found in ${zip.name}")
        val installedLayout = info.installedLayout

        // Retail-encrypted dumps only run with their license. Refuse before touching anything so an
        // existing (working) install in Vita3K is never overwritten by one that can't boot.
        val hasLicense = info.workBin != null || info.hasLicenseDir ||
            ux0.findFile("license")?.findFile(id)?.listFiles()?.any { it.name.orEmpty().endsWith(".rif", ignoreCase = true) } == true
        if (info.ebootHeader != null && isEncryptedSelf(info.ebootHeader) && !hasLicense) {
            error("$id is an encrypted dump with no license (sce_sys/package/work.bin), so Vita3K can't run it. Use a decrypted dump, or install it in Vita3K with its zRIF")
        }

        val map: (List<String>) -> List<String>? = if (installedLayout) {
            { segs -> segs.takeIf { it.size > 1 && it[0].lowercase() in UX0_DIRS }?.let { listOf(it[0].lowercase()) + it.drop(1) } }
        } else {
            { segs -> listOf("app", id) + segs }
        }
        ArchiveExtractor(context).extractZipMapped(zip, ux0, map, onProgress)
        // Like Vita3K's own installer: a NoNpDrm work.bin becomes ux0/license/<TITLE_ID>/<CONTENT_ID>.rif.
        info.workBin?.let { rif ->
            val contentId = info.contentId ?: rifContentId(rif)
            if (contentId != null) {
                val dir = (ux0.findFile("license") ?: ux0.createDirectory("license"))
                    ?.let { it.findFile(id) ?: it.createDirectory(id) }
                    ?: error("Couldn't create Vita3K's license folder")
                dir.findFile("$contentId.rif")?.delete()
                val out = dir.createFile("application/octet-stream", "$contentId.rif") ?: error("Couldn't write the license")
                context.contentResolver.openOutputStream(out.uri, "w")?.use { it.write(rif) }
            }
        }
        refresh()
        id
    }

    private class ZipInfo(
        val names: List<String>,
        val titleId: String?,
        val installedLayout: Boolean,
        val ebootHeader: ByteArray?,
        val workBin: ByteArray?,
        val contentId: String?,
        val hasLicenseDir: Boolean,
    )

    /** Removes a game from Vita3K (app, patch and DLC folders). Save data is kept. */
    suspend fun uninstall(titleId: String): Boolean = withContext(Dispatchers.IO) {
        val ux0 = ux0() ?: return@withContext false
        val removed = listOf("app", "patch", "addcont").mapNotNull { ux0.findFile(it)?.findFile(titleId) }
            .count { it.delete() }
        refresh()
        removed > 0
    }

    /** Folders that [uninstall] would remove, for showing in a confirmation. */
    suspend fun installFolders(titleId: String): List<DocumentFile> = withContext(Dispatchers.IO) {
        val ux0 = ux0() ?: return@withContext emptyList()
        listOf("app", "patch", "addcont").mapNotNull { ux0.findFile(it)?.findFile(titleId) }
    }

    /** What happened to one downloaded Vita zip. */
    data class Outcome(val titleId: String?, val launchFile: String?, val installed: Boolean, val zipRemoved: Boolean, val problems: List<String>) {
        fun summary(): String = buildList {
            if (installed) add("Installed in Vita3K ($titleId)")
            if (launchFile != null) add("launch file $launchFile")
            if (zipRemoved) add("zip removed")
            addAll(problems)
        }.joinToString(" • ")
    }

    /**
     * The PS Vita flow for a downloaded zip (the zip is only a temporary staging file):
     * read its title ID → write the .dpt launch file → install into Vita3K → delete the zip.
     * The zip is kept if installing isn't possible, so nothing is lost.
     */
    suspend fun processDownloadedZip(zip: DocumentFile, mapping: FolderMapping, onProgress: (Long, Long) -> Unit = { _, _ -> }): Outcome {
        val problems = mutableListOf<String>()
        val writer = LaunchFileWriter(context)
        val titleId = writer.titleIdFor(zip)
        if (titleId == null) return Outcome(null, null, false, false, listOf("no title ID found in ${zip.name}; zip kept"))

        var installed = false
        val zipName = zip.name ?: "$titleId.zip"
        if (settings.current().vita3kFolderUri == null) {
            problems += "Vita3K folder not set, zip kept"
        } else {
            runCatching { install(zip, onProgress) }
                .onSuccess { installed = true }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    problems += "Vita3K install failed: ${e.message}. Zip kept"
                }
        }

        // The .dpt sits next to the zip in the same folder; Cocoon only looks at the .dpt files.
        // Skipped when the install failed and the game isn't in Vita3K, since it would launch nothing.
        val wantLaunchFile = installed || titleId in apps.value || settings.current().vita3kFolderUri == null
        val dir = withContext(Dispatchers.IO) { FolderStorage.resolve(context, mapping) }
        val launchFile = if (!wantLaunchFile) null else dir?.let { d ->
            runCatching { writer.write(d, zipName, titleId) }.getOrElse { problems += "launch file failed: ${it.message}"; null }
        } ?: run { if (dir == null) problems += "game folder unavailable"; null }
        val removed = installed && zip.delete()
        return Outcome(titleId, launchFile, installed, removed, problems)
    }

    data class SyncResult(val processedZips: Int, val createdLaunchFiles: Int, val problems: List<String>)

    /**
     * Brings a Vita folder setup in line: installs any zips still in the staging folder (then removes
     * them), and creates .dpt launch files for games already installed in Vita3K that don't have one.
     */
    suspend fun sync(mapping: FolderMapping, staging: DocumentFile?, onStatus: (String) -> Unit = {}): SyncResult = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()
        var processed = 0
        val zips = staging?.listFiles()?.filter { it.isFile && it.name.orEmpty().endsWith(".zip", ignoreCase = true) }.orEmpty()
        zips.forEachIndexed { i, z ->
            onStatus("Installing ${i + 1}/${zips.size}: ${z.name}")
            val o = processDownloadedZip(z, mapping)
            if (o.installed) processed++ else problems += "${z.name}: ${o.problems.joinToString()}"
        }
        var created = 0
        if (staging != null) {
            refresh()
            val writer = LaunchFileWriter(context)
            val have = writer.existingByTitleId(staging)
            apps.value.values.filter { it.titleId !in have }.forEachIndexed { i, a ->
                onStatus("Creating launch files… ${i + 1}")
                runCatching { writer.write(staging, FolderStorage.sanitize("${a.title} [${a.titleId}]") + ".zip", a.titleId) }
                    .onSuccess { created++ }.onFailure { problems += "${a.title}: ${it.message}" }
            }
        } else problems += "game folder unavailable"
        SyncResult(processed, created, problems)
    }

    private fun sfoTitle(bytes: ByteArray): String? = LaunchFileWriter.sfoString(bytes, "TITLE")
}
