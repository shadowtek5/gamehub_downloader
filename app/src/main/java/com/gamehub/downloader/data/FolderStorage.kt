package com.gamehub.downloader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** Helpers for Storage Access Framework tree URIs chosen by the user. */
object FolderStorage {

    fun persist(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    fun hasAccess(context: Context, treeUri: String): Boolean {
        val uri = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } &&
            DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
    }

    /** Human readable label like "Primary:ROMs/snes" or "SD card:ROMs/snes". */
    fun describe(treeUri: String): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull()
            ?: return treeUri
        val volume = docId.substringBefore(':')
        val path = docId.substringAfter(':', "")
        val volumeLabel = when {
            volume.equals("primary", ignoreCase = true) -> "Internal"
            volume.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}", RegexOption.IGNORE_CASE)) -> "SD card"
            else -> volume
        }
        return if (path.isEmpty()) "$volumeLabel / (root)" else "$volumeLabel / $path"
    }

    /** Resolves (creating if needed) the folder a mapping points at. */
    fun resolve(context: Context, mapping: FolderMapping): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(mapping.treeUri)) ?: return null
        if (mapping.subPath.isEmpty()) return root
        val existing = root.findFile(mapping.subPath)
        if (existing != null && existing.isDirectory) return existing
        return root.createDirectory(mapping.subPath)
    }

    fun describe(mapping: FolderMapping): String =
        if (mapping.subPath.isEmpty()) describe(mapping.treeUri)
        else describe(mapping.treeUri).removeSuffix("/ (root)").trimEnd() + "/" + mapping.subPath

    fun treeRoot(context: Context, treeUri: String): DocumentFile? =
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').ifEmpty { "file" }
}
