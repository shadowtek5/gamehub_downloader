package com.gamehub.downloader.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/** Per-system behaviour that differs from the defaults. */
object SystemRules {
    /** Systems whose archives must stay packed (Vita3K installs the .zip directly). */
    private val NO_EXTRACT = setOf("vita")

    /** Systems that get a launch file for front-ends like Cocoon / Daijishou. */
    private val LAUNCH_FILES = setOf("vita")

    fun canExtract(systemKey: String) = systemKey !in NO_EXTRACT
    fun supportsLaunchFiles(systemKey: String) = systemKey in LAUNCH_FILES

    /** Systems whose games can be installed into Vita3K. */
    fun supportsVita3k(systemKey: String) = systemKey == "vita"
}

/**
 * Writes Daijishou Player Template (.dpt) files so a front-end (Cocoon FE) can scrape a
 * launch-file folder and start PS Vita games in Vita3K by title ID.
 */
class LaunchFileWriter(private val context: Context) {

    companion object {
        /** Retail/digital Vita title IDs: PCSA00001, PCSE00890, PCSH10090, … (not NPWR trophy IDs). */
        private val TITLE_ID = Regex("""(?<![A-Z0-9])(PCS[A-Z]\d{5})(?!\d)""")

        fun dptName(romFileName: String) = romFileName.substringBeforeLast('.', romFileName) + ".dpt"

        fun dptContents(titleId: String) = "# Daijishou Player Template\n[vita_game_id] $titleId\n"

        fun titleIdFromName(name: String): String? = TITLE_ID.find(name.uppercase())?.groupValues?.get(1)

        /**
         * Reads only the zip's table of contents: an app/<TITLE_ID>/ (or patch/) folder wins, otherwise
         * the shallowest sce_sys/param.sfo is parsed. NPWR… folders are trophy IDs and are ignored.
         */
        fun titleIdFromZip(channel: SeekableByteChannel): String? =
            ZipFile.builder().setSeekableByteChannel(channel).get().use { titleIdIn(it) }

        fun titleIdIn(zip: ZipFile): String? {
            val entries = zip.entries.toList()
            return entries.firstNotNullOfOrNull { e ->
                Regex("""^(?:APP|PATCH)/(PCS[A-Z]\d{5})/""").find(e.name.uppercase().replace('\\', '/'))?.groupValues?.get(1)
            } ?: entries
                .filter { it.name.replace('\\', '/').lowercase().endsWith("sce_sys/param.sfo") }
                .minByOrNull { it.name.count { c -> c == '/' } }
                ?.let { e -> zip.getInputStream(e).use { titleIdFromSfo(it.readBytes()) } }
        }

        /** Reads TITLE_ID from a PlayStation param.sfo. */
        fun titleIdFromSfo(bytes: ByteArray): String? = sfoString(bytes, "TITLE_ID")

        /** Reads a string value (e.g. "TITLE_ID", "TITLE") from a PlayStation param.sfo. */
        fun sfoString(bytes: ByteArray, wanted: String): String? {
            if (bytes.size < 20 || bytes[1] != 'P'.code.toByte() || bytes[2] != 'S'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val keyTable = b.getInt(8)
            val dataTable = b.getInt(12)
            val count = b.getInt(16)
            for (i in 0 until count) {
                val e = 20 + i * 16
                if (e + 16 > bytes.size) break
                val keyOff = b.getShort(e).toInt() and 0xFFFF
                val len = b.getInt(e + 4)
                val dataOff = b.getInt(e + 12)
                var k = keyTable + keyOff
                val key = StringBuilder()
                while (k < bytes.size && bytes[k] != 0.toByte()) key.append(bytes[k++].toInt().toChar())
                if (key.toString() == wanted) {
                    val start = dataTable + dataOff
                    if (start < 0 || start + len > bytes.size) return null
                    return String(bytes, start, len, Charsets.UTF_8).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
                }
            }
            return null
        }
    }

    /**
     * Finds a Vita game's title ID: from the file name if it carries one, otherwise from the zip's
     * table of contents (an app/<TITLE_ID>/ folder) or its sce_sys/param.sfo. Nothing is extracted.
     */
    suspend fun titleIdFor(archive: DocumentFile): String? = withContext(Dispatchers.IO) {
        archive.name?.let { titleIdFromName(it) }?.let { return@withContext it }
        // After install only the .dpt remains; its contents carry the ID.
        if (archive.name.orEmpty().endsWith(".dpt", ignoreCase = true)) return@withContext runCatching {
            context.contentResolver.openInputStream(archive.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.let { Regex("""\[vita_game_id]\s*([A-Z0-9]+)""").find(it)?.groupValues?.get(1) }
        }.getOrNull()
        if (!archive.name.orEmpty().endsWith(".zip", ignoreCase = true)) return@withContext null
        runCatching {
            context.contentResolver.openFileDescriptor(archive.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { titleIdFromZip(it) }
            }
        }.getOrNull()
    }

    /** Creates or replaces the .dpt for [romFileName] in [dir] (the system's game folder). Returns the file name written. */
    suspend fun write(dir: DocumentFile, romFileName: String, titleId: String): String = withContext(Dispatchers.IO) {
        val name = dptName(romFileName)
        dir.findFile(name)?.delete()
        val file = dir.createFile("application/octet-stream", name) ?: error("Couldn't create $name")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(dptContents(titleId).toByteArray()) }
            ?: error("Couldn't write $name")
        name
    }

    /** Title ID → .dpt file for every launch file already in the folder (read from their contents). */
    suspend fun existingByTitleId(dir: DocumentFile): Map<String, DocumentFile> = withContext(Dispatchers.IO) {
        dir.listFiles().filter { it.isFile && it.name.orEmpty().endsWith(".dpt", ignoreCase = true) }.mapNotNull { f ->
            val text = runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            Regex("""\[vita_game_id]\s*([A-Z0-9]+)""").find(text.orEmpty())?.groupValues?.get(1)?.let { it to f }
        }.toMap()
    }

}
