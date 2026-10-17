package com.gamehub.downloader.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.gamehub.downloader.data.FolderMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class DownloadState { Queued, Running, Retrying, Extracting, Completed, Failed, Cancelled }

enum class DownloadMode { File, DiscsZip }

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val romId: Long,
    val title: String,
    val systemKey: String,
    val systemName: String,
    val fileName: String,
    val mode: DownloadMode,
    val folder: FolderMapping,
    val boxartUrl: String? = null,
    val state: DownloadState = DownloadState.Queued,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val error: String? = null,
    val attempt: Int = 0,
    /** Set after extraction: how many files came out of the archive. */
    val extractedFiles: Int? = null,
) {
    val percent: Int?
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else null
    val isActive: Boolean
        get() = state == DownloadState.Queued || state == DownloadState.Running ||
            state == DownloadState.Retrying || state == DownloadState.Extracting
}

/**
 * Process-wide download queue. The UI observes [tasks]; [DownloadService] does the work
 * in the foreground so downloads keep going with the screen off.
 */
class DownloadManager(private val context: Context) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun enqueue(task: DownloadTask) {
        _tasks.update { list ->
            // Don't queue the same game twice while it's still pending.
            if (list.any { it.isActive && it.connectionId == task.connectionId && it.romId == task.romId }) list
            else list + task
        }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel(id: String) {
        update(id) { if (it.isActive) it.copy(state = DownloadState.Cancelled, bytesPerSecond = 0) else it }
        context.startService(Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL).putExtra(DownloadService.EXTRA_ID, id))
    }

    fun retry(id: String) {
        update(id) {
            if (it.state == DownloadState.Failed || it.state == DownloadState.Cancelled)
                it.copy(state = DownloadState.Queued, error = null, attempt = 0, bytesPerSecond = 0)
            else it
        }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun clearFinished() = _tasks.update { list -> list.filter { it.isActive } }

    fun remove(id: String) = _tasks.update { list -> list.filterNot { it.id == id && !it.isActive } }

    internal fun nextQueued(): DownloadTask? = _tasks.value.firstOrNull { it.state == DownloadState.Queued }

    internal fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    internal fun update(id: String, transform: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }

    fun activeFor(connectionId: String, romId: Long): DownloadTask? =
        _tasks.value.lastOrNull { it.connectionId == connectionId && it.romId == romId }
}
