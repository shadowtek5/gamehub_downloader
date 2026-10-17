package com.gamehub.downloader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One file or sub-folder in a system folder (for deleting an installed game). */
data class FolderEntry(val name: String, val uri: Uri, val isDir: Boolean, val size: Long)

/** What's already sitting in one system's folder. */
data class FolderContents(
    /** Normalized names (see [InstalledIndex.normalize]) of every file and sub-folder. */
    val names: Set<String>,
    /** Exact file names, for precise matches on the game page. */
    val files: Set<String>,
) {
    val count: Int get() = files.size
}

/**
 * Scans each mapped system folder so the UI can mark games that are already installed, including
 * ones copied there outside this app. Matching is by normalized name, so "Game (USA).zip" on the
 * server matches an extracted "Game (USA).sfc" or "Game.sfc" in the folder.
 */
class InstalledIndex(private val context: Context, private val settings: SettingsRepository) {

    /** Key: "connectionId|systemKey". */
    private val _contents = MutableStateFlow<Map<String, FolderContents>>(emptyMap())
    val contents: StateFlow<Map<String, FolderContents>> = _contents.asStateFlow()
    private val lock = Mutex()

    suspend fun refresh(connectionId: String? = null) = lock.withLock {
        val s = settings.current()
        val mappings = s.folders.filter { connectionId == null || it.connectionId == connectionId }
        val scanned = withContext(Dispatchers.IO) {
            mappings.associate { m -> key(m.connectionId, m.systemKey) to (scan(m) ?: FolderContents(emptySet(), emptySet())) }
        }
        _contents.update { old ->
            // Drop entries for folders that are no longer mapped.
            val live = s.folders.map { key(it.connectionId, it.systemKey) }.toSet()
            (old + scanned).filterKeys { it in live }
        }
    }

    fun get(connectionId: String, systemKey: String): FolderContents? = _contents.value[key(connectionId, systemKey)]

    fun isInstalled(connectionId: String, systemKey: String, title: String): Boolean =
        get(connectionId, systemKey)?.names?.contains(normalize(title, isFile = false)) == true

    /**
     * Files/folders in [m]'s folder that belong to a game: same normalized name as its title or its
     * server file name (so "Game (USA).zip" also finds an extracted "Game (USA).sfc" or a "Game" folder).
     */
    suspend fun findGameFiles(m: FolderMapping, title: String, fileName: String?): List<FolderEntry> = withContext(Dispatchers.IO) {
        val dir = find(m) ?: return@withContext emptyList()
        val targets = setOfNotNull(normalize(title, isFile = false), fileName?.let { normalize(it, isFile = true) })
            .filter { it.isNotEmpty() }.toSet()
        val out = mutableListOf<FolderEntry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (name == fileName || normalize(name, isFile = !isDir) in targets) {
                    out += FolderEntry(name, DocumentsContract.buildDocumentUriUsingTree(dir, c.getString(0)), isDir, if (c.isNull(3)) 0 else c.getLong(3))
                }
            }
        }
        out.sortedBy { it.name.lowercase() }
    }

    /** Deletes the given entries (folders recursively); returns how many were removed. */
    suspend fun delete(entries: List<FolderEntry>): Int = withContext(Dispatchers.IO) {
        entries.count { e -> runCatching { DocumentsContract.deleteDocument(context.contentResolver, e.uri) }.getOrDefault(false) }
    }

    private fun scan(m: FolderMapping): FolderContents? {
        val dir = find(m) ?: return null
        val names = HashSet<String>()
        val files = HashSet<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        runCatching {
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    if (name.startsWith(".") || name.endsWith(".part")) continue
                    val isDir = c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                    if (!isDir) files += name
                    names += normalize(name, isFile = !isDir)
                }
            }
        }
        return FolderContents(names, files)
    }

    /** Like [FolderStorage.resolve] but never creates the sub-folder. */
    private fun find(m: FolderMapping): Uri? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(m.treeUri)) ?: return null
        if (!root.canRead()) return null
        if (m.subPath.isEmpty()) return root.uri
        return root.findFile(m.subPath)?.takeIf { it.isDirectory }?.uri
    }

    companion object {
        private fun key(connectionId: String, systemKey: String) = "$connectionId|$systemKey"

        private val tags = Regex("""\s*[(\[][^)\]]*[)\]]""")

        /**
         * Reduces a title or file name to comparable letters/digits: drops the extension (files),
         * "(USA)" / "[!]" style tags, a leading or trailing ", The", and all punctuation.
         */
        fun normalize(name: String, isFile: Boolean): String {
            var s = name
            if (isFile) {
                val dot = s.lastIndexOf('.')
                if (dot > 0 && s.length - dot in 2..6) s = s.substring(0, dot)
            }
            s = s.replace(tags, "").trim().lowercase()
            if (s.endsWith(", the")) s = s.removeSuffix(", the")
            s = s.removePrefix("the ")
            return s.filter { it.isLetterOrDigit() }
        }
    }
}
