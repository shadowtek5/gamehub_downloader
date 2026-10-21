package com.gamehub.downloader.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Free space and space used, for the folders the user picked. */
object Storage {

    /** Leave this much free so the device doesn't fill up completely. */
    const val RESERVE_BYTES = 512L * 1024 * 1024

    /** Root of the volume a SAF tree lives on, e.g. "/storage/emulated/0" or "/storage/3830-6461". */
    private fun volumeRoot(treeUri: String): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull() ?: return null
        val volume = id.substringBefore(':', "")
        if (volume.isEmpty()) return null
        return if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
    }

    /** Free bytes on the volume holding [treeUri], or null when it can't be read. */
    fun freeBytes(treeUri: String): Long? = volumeRoot(treeUri)?.let { root ->
        runCatching { StatFs(root).availableBytes }.getOrNull()
    }

    fun totalBytes(treeUri: String): Long? = volumeRoot(treeUri)?.let { root ->
        runCatching { StatFs(root).totalBytes }.getOrNull()
    }

    /** "SD card" / "Internal storage" for messages. */
    fun volumeLabel(treeUri: String): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull().orEmpty()
        return if (id.startsWith("primary", ignoreCase = true)) "internal storage" else "the SD card"
    }

    /** Total size of everything in a folder (one level of files plus sub-folders, recursively). */
    suspend fun folderSize(context: Context, dir: Uri): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val stack = ArrayDeque<String>().apply { add(DocumentsContract.getDocumentId(dir)) }
        while (stack.isNotEmpty()) {
            val docId = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, docId)
            runCatching {
                context.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(c.getString(0))
                        else if (!c.isNull(2)) total += c.getLong(2)
                    }
                }
            }
        }
        total
    }
}
