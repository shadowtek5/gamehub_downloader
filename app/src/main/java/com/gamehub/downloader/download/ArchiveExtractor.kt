package com.gamehub.downloader.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.gamehub.downloader.data.FolderStorage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks a downloaded .zip or .7z into its folder through the Storage Access Framework.
 * Paths inside the archive are kept (sub-folders are created) but sanitised so nothing can
 * escape the destination folder.
 */
class ArchiveExtractor(private val context: Context) {

    class Result(val files: Int)

    companion object {
        fun isSupported(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in setOf("zip", "7z")
    }

    /** [onProgress] receives (bytesDone, bytesTotal); total may be 0 if unknown. */
    suspend fun extract(archive: DocumentFile, dest: DocumentFile, onProgress: (Long, Long) -> Unit): Result {
        val tree = DocTree(context, dest.uri)
        return when (archive.name.orEmpty().substringAfterLast('.', "").lowercase()) {
            "zip" -> extractZip(archive, tree, onProgress)
            "7z" -> extract7z(archive, tree, onProgress)
            else -> throw IOException("Unsupported archive type")
        }
    }

    /**
     * Extracts a zip into [dest], rewriting each entry's path with [map] (return null to skip an
     * entry). Used to install Vita games into Vita3K's ux0 layout.
     */
    suspend fun extractZipMapped(
        archive: DocumentFile,
        dest: DocumentFile,
        map: (List<String>) -> List<String>?,
        onProgress: (Long, Long) -> Unit,
    ): Result = extractZip(archive, DocTree(context, dest.uri), onProgress, map)

    private suspend fun extractZip(
        archive: DocumentFile,
        tree: DocTree,
        onProgress: (Long, Long) -> Unit,
        map: (List<String>) -> List<String>? = { it },
    ): Result {
        val total = archive.length()
        val input = context.contentResolver.openInputStream(archive.uri) ?: throw IOException("Can't open archive")
        val counting = CountingInputStream(input)
        var files = 0
        ZipInputStream(counting.buffered(256 * 1024)).use { zip ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                val segments = map(safeSegments(entry.name))?.takeIf { it.isNotEmpty() } ?: continue
                if (entry.isDirectory) {
                    tree.ensureDirs(segments)
                    continue
                }
                val out = tree.createFile(segments)
                context.contentResolver.openOutputStream(out, "w")?.use { os ->
                    var lastUi = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        val now = System.nanoTime()
                        if (now - lastUi > 300_000_000L) {
                            lastUi = now
                            currentCoroutineContext().ensureActive()
                            onProgress(counting.count, total)
                        }
                    }
                } ?: throw IOException("Can't write ${segments.last()}")
                files++
            }
        }
        onProgress(total, total)
        return Result(files)
    }

    private suspend fun extract7z(archive: DocumentFile, tree: DocTree, onProgress: (Long, Long) -> Unit): Result {
        val pfd = context.contentResolver.openFileDescriptor(archive.uri, "r") ?: throw IOException("Can't open archive")
        var files = 0
        pfd.use {
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                SevenZFile.builder().setSeekableByteChannel(channel).get().use { sevenZ ->
                    val entries = sevenZ.entries.toList()
                    val total = entries.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
                    var done = 0L
                    val buf = ByteArray(256 * 1024)
                    var lastUi = 0L
                    while (true) {
                        val entry = sevenZ.nextEntry ?: break
                        currentCoroutineContext().ensureActive()
                        val segments = safeSegments(entry.name ?: continue)
                        if (segments.isEmpty()) continue
                        if (entry.isDirectory) {
                            tree.ensureDirs(segments)
                            continue
                        }
                        val out = tree.createFile(segments)
                        context.contentResolver.openOutputStream(out, "w")?.use { os ->
                            if (entry.hasStream()) {
                                while (true) {
                                    val n = sevenZ.read(buf)
                                    if (n < 0) break
                                    os.write(buf, 0, n)
                                    done += n
                                    val now = System.nanoTime()
                                    if (now - lastUi > 300_000_000L) {
                                        lastUi = now
                                        currentCoroutineContext().ensureActive()
                                        onProgress(done, total)
                                    }
                                }
                            }
                        } ?: throw IOException("Can't write ${segments.last()}")
                        files++
                    }
                    onProgress(total, total)
                }
            }
        }
        return Result(files)
    }

    /** Splits an archive path into safe folder/file names; drops "", ".", ".." and absolute roots. */
    private fun safeSegments(path: String): List<String> =
        path.replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { FolderStorage.sanitize(it) }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /**
     * Minimal SAF directory helper that caches each folder's listing, since looking names up
     * with DocumentFile.findFile re-queries the whole folder every time (slow in big ROM folders).
     */
    private class DocTree(private val context: Context, private val root: Uri) {
        private data class Child(val uri: Uri, val isDir: Boolean)
        private val listings = HashMap<Uri, MutableMap<String, Child>>()
        private val resolver get() = context.contentResolver

        private fun list(dir: Uri): MutableMap<String, Child> = listings.getOrPut(dir) {
            val map = HashMap<String, Child>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(dir, c.getString(0))
                    map[c.getString(1)] = Child(uri, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
            map
        }

        fun ensureDirs(segments: List<String>): Uri {
            var dir = root
            for (name in segments) {
                val existing = list(dir)[name]
                dir = if (existing != null && existing.isDir) existing.uri
                else {
                    val created = DocumentsContract.createDocument(resolver, dir, DocumentsContract.Document.MIME_TYPE_DIR, name)
                        ?: throw IOException("Can't create folder $name")
                    list(dir)[name] = Child(created, true)
                    created
                }
            }
            return dir
        }

        /** Creates (replacing any existing file of the same name) and returns the new file's URI. */
        fun createFile(segments: List<String>): Uri {
            val parent = ensureDirs(segments.dropLast(1))
            val name = segments.last()
            val siblings = list(parent)
            siblings[name]?.let { old ->
                if (!old.isDir) runCatching { DocumentsContract.deleteDocument(resolver, old.uri) }
            }
            val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
                ?: throw IOException("Can't create $name")
            siblings[name] = Child(created, false)
            return created
        }
    }
}
