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
import com.gamehub.downloader.data.SettingsRepository
import com.gamehub.downloader.download.DownloadManager
import com.gamehub.downloader.download.DownloadNotifications
import com.gamehub.downloader.ui.Gamepad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        Gamepad.init(this, appScope)
        installed = InstalledIndex(this, settings)
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
