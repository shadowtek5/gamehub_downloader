package com.gamehub.downloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gamehub.downloader.MainActivity
import com.gamehub.downloader.R

object DownloadNotifications {
    const val CHANNEL_PROGRESS = "downloads_progress"
    const val CHANNEL_RESULT = "downloads_result"
    const val FOREGROUND_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of games currently downloading"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a game finishes downloading or fails"
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun cancelIntent(context: Context, id: String?): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL)
        if (id != null) intent.putExtra(DownloadService.EXTRA_ID, id)
        return PendingIntent.getService(
            context, id?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The ongoing notification that keeps the foreground service alive. */
    fun progress(context: Context, active: DownloadTask?, queued: Int): android.app.Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (active == null) {
            return b.setContentTitle(context.getString(R.string.app_name)).setContentText("Preparing downloads…")
                .setProgress(0, 0, true).build()
        }
        val pct = active.percent
        val sub = buildString {
            append(formatBytes(active.downloadedBytes))
            if (active.totalBytes > 0) append(" / ").append(formatBytes(active.totalBytes))
            if (active.bytesPerSecond > 0) append(" • ").append(formatBytes(active.bytesPerSecond)).append("/s")
            if (queued > 0) append(" • $queued queued")
        }
        if (active.state == DownloadState.Extracting || active.state == DownloadState.Installing) {
            val verb = if (active.state == DownloadState.Installing) "Installing in Vita3K:" else "Extracting"
            return b.setContentTitle("$verb ${active.title}")
                .setContentText(if (queued > 0) "$queued queued" else active.systemName)
                .setProgress(100, pct ?: 0, pct == null)
                .build()
        }
        return b.setContentTitle(active.title)
            .setContentText(sub)
            .setSubText(active.systemName)
            .setProgress(100, pct ?: 0, pct == null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent(context, active.id))
            .build()
    }

    fun finished(context: Context, task: DownloadTask) {
        val ok = task.state == DownloadState.Completed
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "Downloaded ${task.title}" else "Download failed: ${task.title}")
            .setContentText(
                if (ok && task.note != null) "${task.systemName} • ${task.note}"
                else if (ok) task.extractedFiles?.let { "${task.systemName} • extracted $it file${if (it == 1) "" else "s"}" }
                    ?: "${task.systemName} • ${formatBytes(task.downloadedBytes)}"
                else task.error ?: "Unknown error"
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, task.id.hashCode(), n)
    }

    fun queueDone(context: Context, completed: Int, failed: Int) {
        if (completed + failed < 2) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("All downloads finished")
            .setContentText("$completed completed" + if (failed > 0) ", $failed failed" else "")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, 2, n)
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) {
            try { nm.notify(id, n) } catch (_: SecurityException) { }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}
