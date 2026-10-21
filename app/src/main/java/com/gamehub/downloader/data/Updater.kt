package com.gamehub.downloader.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.gamehub.downloader.BuildConfig
import com.gamehub.downloader.GameHubApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/** A newer release of this app on GitHub. */
data class AppUpdate(val version: String, val notes: String, val apkUrl: String, val size: Long, val pageUrl: String)

data class UpdateState(
    val available: AppUpdate? = null,
    val checking: Boolean = false,
    /** Bytes downloaded / total while fetching the APK. */
    val progress: Pair<Long, Long>? = null,
    val error: String? = null,
    /** Shown after a manual check found nothing newer. */
    val upToDate: Boolean = false,
)

/** Checks GitHub Releases for a newer version and installs it (Android asks the user to confirm). */
class Updater(private val app: GameHubApp) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Serializable
    private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Looks for a newer release. [manual] also reports "up to date" and ignores a skipped version. */
    suspend fun check(manual: Boolean = false) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, error = null, upToDate = false) }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
                app.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("GitHub returned HTTP ${resp.code}")
                    json.decodeFromString<GhRelease>(resp.body!!.string())
                }
            }
        }
        val rel = result.getOrElse { e ->
            _state.update { it.copy(checking = false, error = if (manual) e.message ?: "Couldn't check for updates" else null) }
            return
        }
        val version = rel.tag.removePrefix("v")
        val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && "release" in it.name.lowercase() }
            ?: rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        val skipped = app.settings.current().skippedUpdate
        val newer = !rel.draft && !rel.prerelease && apk != null && isNewer(version, BuildConfig.VERSION_NAME) && (manual || skipped != version)
        _state.update {
            it.copy(
                checking = false,
                available = if (newer) AppUpdate(version, rel.body.orEmpty().trim(), apk!!.url, apk.size, rel.htmlUrl) else null,
                upToDate = manual && !newer,
            )
        }
    }

    suspend fun skip() {
        _state.value.available?.let { app.settings.setSkippedUpdate(it.version) }
        _state.update { it.copy(available = null) }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, upToDate = false) }

    /** Downloads the APK and hands it to Android's package installer. */
    suspend fun install() {
        val update = _state.value.available ?: return
        _state.update { it.copy(progress = 0L to update.size, error = null) }
        runCatching {
            withContext(Dispatchers.IO) {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (update.size > 0) setSize(update.size)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    app.http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
                        val body = resp.body!!
                        val total = body.contentLength().takeIf { it > 0 } ?: update.size
                        session.openWrite("app.apk", 0, total.takeIf { it > 0 } ?: -1).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    _state.update { it.copy(progress = done to total) }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(app, InstallResultReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(app, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pi.intentSender)
                }
            }
        }.onFailure { e -> _state.update { it.copy(progress = null, error = e.message ?: "Update failed") } }
            .onSuccess { _state.update { it.copy(progress = null) } }
    }

    internal fun reportInstallError(message: String) = _state.update { it.copy(progress = null, error = message) }

    companion object {
        const val LATEST = "https://api.github.com/repos/shadowtek5/gamehub_downloader/releases/latest"

        /** Compares dotted versions ("1.10.0" > "1.9.2"); suffixes like "-debug" are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives the package installer's status; asks the user to confirm when Android needs it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update couldn't be installed"
                val hint = if (msg.contains("signature", ignoreCase = true) || msg.contains("conflict", ignoreCase = true))
                    " (this copy was installed from a different build; uninstall it once and install the release from GitHub)" else ""
                GameHubApp.instance.updater.reportInstallError(msg + hint)
            }
        }
    }
}
