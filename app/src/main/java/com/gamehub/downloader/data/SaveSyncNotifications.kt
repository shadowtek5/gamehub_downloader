package com.gamehub.downloader.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gamehub.downloader.MainActivity

/** Android notifications for save sync: progress while it runs, and what happened afterwards. */
object SaveSyncNotifications {
    private const val CHANNEL_PROGRESS = "save_sync_progress"
    private const val CHANNEL_RESULT = "save_sync_result"
    private const val ID_PROGRESS = 41
    private const val ID_RESULT = 42

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Save sync progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while saves are syncing with GameHub"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Save sync", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Saves uploaded or downloaded, saves that need you to choose a copy, and sync errors"
            }
        )
    }

    private fun openSaveSync(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SAVE_SYNC, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun progress(context: Context, done: Int, total: Int, title: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncing saves with GameHub")
            .setContentText(if (total > 0) "$done of $total • $title" else title)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openSaveSync(context))
            .build()
        notify(context, ID_PROGRESS, n)
    }

    fun clearProgress(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PROGRESS)

    /** Posts the outcome when something happened; a sync with nothing to do stays silent. */
    fun result(context: Context, report: SaveSync.Report) {
        val changed = report.log.filter { it.action == SaveSync.Action.UPLOADED || it.action == SaveSync.Action.DOWNLOADED }
        if (changed.isEmpty() && report.conflicts.isEmpty() && report.errors.isEmpty()) return
        val title = when {
            report.conflicts.isNotEmpty() ->
                "${report.conflicts.size} save${if (report.conflicts.size == 1) "" else "s"} need${if (report.conflicts.size == 1) "s" else ""} your choice"
            report.errors.isNotEmpty() -> "Save sync: ${report.errors.size} failed"
            else -> "Saves synced with GameHub"
        }
        val lines = buildList {
            report.conflicts.forEach { add("! ${it.title} (${it.source}): changed on both sides") }
            report.errors.forEach { add("✗ $it") }
            changed.forEach { add("${if (it.action == SaveSync.Action.UPLOADED) "↑" else "↓"} ${it.title} (${it.source})") }
        }
        val style = NotificationCompat.InboxStyle().setSummaryText(report.summary())
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.addLine("…and ${lines.size - 6} more")
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(if (report.conflicts.isEmpty() && report.errors.isEmpty()) android.R.drawable.stat_notify_sync else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(report.summary())
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openSaveSync(context))
            .build()
        notify(context, ID_RESULT, n)
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) {
            try { nm.notify(id, n) } catch (_: SecurityException) { }
        }
    }
}
