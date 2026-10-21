package com.gamehub.downloader.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamehub.downloader.GameHubApp
import java.util.concurrent.TimeUnit

/** Settings › Save sync › "Sync in the background": a periodic sync, even when the app isn't open. */
class SaveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GameHubApp
        if (app.settings.current().saveSync.backgroundHours <= 0) return Result.success()
        // Same as "Sync when the app opens": the outcome is posted as a notification when anything changed.
        return runCatching { app.saveSync.syncAll(auto = true) }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        private const val NAME = "save-sync"

        /** Starts, changes or stops the schedule to match the settings. */
        fun schedule(context: Context, settings: SaveSyncSettings) {
            val wm = WorkManager.getInstance(context)
            if (settings.backgroundHours <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.backgroundUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SaveSyncWorker>(settings.backgroundHours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(settings.backgroundHours.toLong(), TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
