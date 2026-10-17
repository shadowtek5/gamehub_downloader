package com.gamehub.downloader.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.ApiException
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.data.DownloadRecord
import com.gamehub.downloader.data.FolderStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Foreground service that drains the download queue one game at a time.
 *
 * It holds a partial wake lock and a Wi-Fi lock while working so downloads finish when
 * the screen turns off or the device goes to sleep, resumes interrupted transfers with
 * HTTP Range requests, and retries on network drops.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var currentId: String? = null
    private var currentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var completedCount = 0
    private var failedCount = 0

    private val app get() = application as GameHubApp
    private val manager get() = app.downloads

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_ID)
            if (id == null) {
                manager.tasks.value.filter { it.isActive }.forEach { manager.cancel(it.id) }
            } else {
                manager.update(id) { if (it.isActive) it.copy(state = DownloadState.Cancelled, bytesPerSecond = 0) else it }
                if (id == currentId) currentJob?.cancel()
            }
        }
        if (worker?.isActive != true) worker = scope.launch { drainQueue() }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val active = manager.tasks.value.firstOrNull { it.state == DownloadState.Running }
        val queued = manager.tasks.value.count { it.state == DownloadState.Queued }
        val n = DownloadNotifications.progress(this, active, queued)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, DownloadNotifications.FOREGROUND_ID, n, type)
    }

    private suspend fun drainQueue() {
        acquireLocks()
        completedCount = 0
        failedCount = 0
        try {
            while (coroutineContext.isActive) {
                val task = manager.nextQueued() ?: break
                currentId = task.id
                val job = scope.launch { runTask(task.id) }
                currentJob = job
                job.join()
                manager.get(task.id)?.let { done ->
                    when (done.state) {
                        DownloadState.Completed -> { completedCount++; DownloadNotifications.finished(this, done) }
                        DownloadState.Failed -> { failedCount++; DownloadNotifications.finished(this, done) }
                        DownloadState.Cancelled -> cleanupPartial(done)
                        else -> {}
                    }
                }
            }
            DownloadNotifications.queueDone(this, completedCount, failedCount)
        } finally {
            currentId = null
            currentJob = null
            releaseLocks()
            if (manager.nextQueued() != null && scope.isActive) {
                // Something was queued while we were finishing up; keep going.
                worker = scope.launch { drainQueue() }
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runTask(id: String) {
        val maxAttempts = 6
        var attempt = 0
        while (true) {
            val task = manager.get(id) ?: return
            if (task.state == DownloadState.Cancelled) return
            manager.update(id) { it.copy(state = DownloadState.Running, error = null, attempt = attempt) }
            try {
                transfer(id)
                return
            } catch (e: CancellationException) {
                manager.update(id) { it.copy(state = DownloadState.Cancelled, bytesPerSecond = 0) }
                throw e
            } catch (e: ApiException) {
                // HTTP errors (401/404/415…) won't fix themselves on retry.
                manager.update(id) { it.copy(state = DownloadState.Failed, error = e.message, bytesPerSecond = 0) }
                return
            } catch (e: IOException) {
                attempt++
                if (attempt >= maxAttempts || manager.get(id)?.state == DownloadState.Cancelled) {
                    manager.update(id) {
                        if (it.state == DownloadState.Cancelled) it
                        else it.copy(state = DownloadState.Failed, error = e.message ?: "Network error", bytesPerSecond = 0)
                    }
                    return
                }
                manager.update(id) {
                    it.copy(state = DownloadState.Retrying, error = "Connection lost — retrying (${attempt}/${maxAttempts - 1})", bytesPerSecond = 0)
                }
                refreshNotification()
                waitForNetwork()
                delay(minOf(30_000L, 2_000L shl (attempt - 1)))
            } catch (e: Exception) {
                manager.update(id) { it.copy(state = DownloadState.Failed, error = e.message ?: e.javaClass.simpleName, bytesPerSecond = 0) }
                return
            }
        }
    }

    private suspend fun transfer(id: String) {
        val task = manager.get(id) ?: return
        val settings = app.settings.current()
        val connection = settings.connections.firstOrNull { it.id == task.connectionId }
            ?: throw ApiException(0, "Connection was removed")
        val api = GameHubApi(app.http, connection)

        val folder = FolderStorage.resolve(this, task.folder)
            ?: throw ApiException(0, "Download folder is unavailable — re-select it in Settings › Folders")
        if (!folder.canWrite()) throw ApiException(0, "No write access to ${FolderStorage.describe(task.folder)}")

        val partName = task.fileName + ".part"
        val part: DocumentFile = folder.findFile(partName)
            ?: folder.createFile("application/octet-stream", partName)
            ?: throw ApiException(0, "Couldn't create $partName")

        val resumable = task.mode == DownloadMode.File
        val have = if (resumable) part.length() else 0L
        val url = if (task.mode == DownloadMode.File) api.fileUrl(task.romId) else api.discsZipUrl(task.romId)
        val req = api.authed(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()

        app.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, GameHubApi.errorMessage(resp.code, resp.body?.string().orEmpty()))
            }
            val body = resp.body ?: throw IOException("Empty response")
            val append = resp.code == 206 && have > 0
            val start = if (append) have else 0L
            val total = totalSize(resp, start) ?: task.totalBytes.takeIf { it > 0 } ?: 0L
            manager.update(id) { it.copy(downloadedBytes = start, totalBytes = total) }

            val out = runCatching { contentResolver.openOutputStream(part.uri, if (append) "wa" else "wt") }.getOrNull()
                ?: contentResolver.openOutputStream(part.uri, "w")
                ?: throw IOException("Couldn't open output file")

            out.use { os ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = start
                    var lastUi = 0L
                    var windowStart = System.nanoTime()
                    var windowBytes = 0L
                    var speed = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        windowBytes += n
                        val now = System.nanoTime()
                        val elapsed = now - windowStart
                        if (elapsed >= 1_000_000_000L) {
                            speed = windowBytes * 1_000_000_000L / elapsed
                            windowStart = now
                            windowBytes = 0
                        }
                        if (now - lastUi >= 500_000_000L) {
                            lastUi = now
                            val d = done
                            val s = speed
                            manager.update(id) { it.copy(downloadedBytes = d, bytesPerSecond = s) }
                            refreshNotification()
                        }
                    }
                    os.flush()
                    if (total > 0 && done < total) throw IOException("Connection closed early")
                    manager.update(id) { it.copy(downloadedBytes = done, totalBytes = if (total > 0) total else done) }
                }
            }
        }

        // Swap the finished .part into place, replacing any older copy.
        val finalName = finalFileName(task)
        folder.findFile(finalName)?.takeIf { it.uri != part.uri }?.delete()
        if (!part.renameTo(finalName)) throw ApiException(0, "Downloaded, but couldn't rename $partName")

        // Optional per-system step: unpack .zip/.7z into the folder, then remove the archive.
        var extracted: Int? = null
        if (task.folder.extract && ArchiveExtractor.isSupported(finalName)) {
            val archive = folder.findFile(finalName) ?: throw ApiException(0, "Downloaded file went missing before extraction")
            manager.update(id) { it.copy(state = DownloadState.Extracting, downloadedBytes = 0, totalBytes = 0, bytesPerSecond = 0) }
            refreshNotification()
            val result = try {
                ArchiveExtractor(this).extract(archive, folder) { done, total ->
                    manager.update(id) { it.copy(downloadedBytes = done, totalBytes = total) }
                    refreshNotification()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Download succeeded; keep the archive so nothing is lost.
                throw ApiException(0, "Downloaded, but extraction failed (${e.message ?: e.javaClass.simpleName}). The archive was kept.")
            }
            archive.delete()
            extracted = result.files
        }

        manager.update(id) { it.copy(state = DownloadState.Completed, bytesPerSecond = 0, error = null, extractedFiles = extracted) }
        app.installed.refresh(task.connectionId)
        app.history.add(
            DownloadRecord(
                connectionId = task.connectionId,
                romId = task.romId,
                systemKey = task.systemKey,
                fileName = finalName,
                folderLabel = FolderStorage.describe(task.folder),
                completedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun finalFileName(task: DownloadTask) = FolderStorage.sanitize(task.fileName)

    private fun totalSize(resp: Response, start: Long): Long? {
        resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { return it }
        val len = resp.header("Content-Length")?.toLongOrNull() ?: return null
        return len + start
    }

    private fun cleanupPartial(task: DownloadTask) {
        runCatching {
            FolderStorage.resolve(this, task.folder)?.findFile(task.fileName + ".part")?.delete()
        }
    }

    private suspend fun waitForNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        repeat(120) { // up to ~10 minutes
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return
            delay(5_000)
        }
    }

    private fun refreshNotification() {
        val tasks = manager.tasks.value
        val active = tasks.firstOrNull {
            it.state == DownloadState.Running || it.state == DownloadState.Retrying || it.state == DownloadState.Extracting
        }
        val queued = tasks.count { it.state == DownloadState.Queued }
        val nm = NotificationManagerCompat.from(this)
        try {
            nm.notify(DownloadNotifications.FOREGROUND_ID, DownloadNotifications.progress(this, active, queued))
        } catch (_: SecurityException) { }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameHub:download").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameHub:download").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.gamehub.downloader.CANCEL"
        const val EXTRA_ID = "id"
    }
}
