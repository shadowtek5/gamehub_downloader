package com.gamehub.downloader

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.data.Connection
import com.gamehub.downloader.data.DownloadHistory
import com.gamehub.downloader.data.InstalledIndex
import com.gamehub.downloader.data.Playtime
import com.gamehub.downloader.data.SaveSyncWorker
import com.gamehub.downloader.data.Updater
import com.gamehub.downloader.data.SaveSync
import com.gamehub.downloader.data.SettingsRepository
import com.gamehub.downloader.data.Vita3k
import com.gamehub.downloader.download.DownloadManager
import com.gamehub.downloader.download.DownloadNotifications
import com.gamehub.downloader.ui.Gamepad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class GameHubApp : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository private set
    lateinit var history: DownloadHistory private set
    lateinit var downloads: DownloadManager private set
    lateinit var http: OkHttpClient private set
    lateinit var installed: InstalledIndex private set
    lateinit var vita3k: Vita3k private set
    lateinit var saveSync: SaveSync private set
    lateinit var playtime: Playtime private set
    lateinit var updater: Updater private set
    lateinit var emulatorCatalogs: com.gamehub.downloader.data.EmulatorCatalogs private set
    lateinit var compat: com.gamehub.downloader.data.Compat private set

    /** Short app-wide messages shown as a toast at the top of the screen. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun showMessage(text: String) { _messages.tryEmit(text) }

    /** Connections cached for the image interceptor, which can't suspend. */
    @Volatile private var connections: List<Connection> = emptyList()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        history = DownloadHistory(this)
        downloads = DownloadManager(this)
        http = GameHubApi.defaultClient()
        DownloadNotifications.createChannels(this)
        com.gamehub.downloader.data.SaveSyncNotifications.createChannels(this)
        Gamepad.init(this, appScope)
        installed = InstalledIndex(this, settings)
        vita3k = Vita3k(this, settings)
        saveSync = SaveSync(this)
        playtime = Playtime(this)
        updater = Updater(this)
        emulatorCatalogs = com.gamehub.downloader.data.EmulatorCatalogs(this)
        compat = com.gamehub.downloader.data.Compat(this)
        // Settings › Save sync › background schedule.
        appScope.launch {
            settings.settings.map { it.saveSync.backgroundHours to it.saveSync.backgroundUnmetered }.distinctUntilChanged().collect {
                runCatching { SaveSyncWorker.schedule(this@GameHubApp, settings.current().saveSync) }
            }
        }
        // Look for a newer release once per launch (Settings › Updates).
        appScope.launch { if (settings.current().checkUpdates) updater.check() }
        // Rescan Vita3K's installed games whenever its folder is set or changed.
        appScope.launch {
            settings.settings.map { it.vita3kFolderUri }.distinctUntilChanged().collect { vita3k.refresh() }
        }
        appScope.launch { settings.settings.collect { connections = it.connections } }
        // Rescan system folders whenever a folder mapping changes.
        appScope.launch {
            settings.settings.map { it.folders }.distinctUntilChanged().collect { installed.refresh() }
        }
    }

    fun api(connection: Connection) = GameHubApi(http, connection)

    /**
     * Coil loader that attaches the right Bearer token when an image lives on one of the
     * configured GameHub servers (box art under /api/media requires auth).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val authInterceptor = Interceptor { chain ->
            val req = chain.request()
            val match = connections.firstOrNull { c ->
                val base = runCatching { GameHubApi.normalizeBaseUrl(c.baseUrl) }.getOrNull()
                base != null && base.host == req.url.host && base.port == req.url.port
            }
            if (match != null && req.header("Authorization") == null)
                chain.proceed(req.newBuilder().header("Authorization", "Bearer ${match.token.trim()}").build())
            else chain.proceed(req)
        }
        val imageClient = http.newBuilder().addInterceptor(authInterceptor).build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
            .build()
    }

    companion object {
        lateinit var instance: GameHubApp private set
    }
}
