package com.gamehub.downloader.data

import android.os.Build
import com.gamehub.downloader.GameHubApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** "How did it run?" after a play session, answered on the next screen the app shows. */
data class RatingRequest(
    val connectionId: String,
    val romId: Long,
    val title: String,
    val emulator: String,
    val emulatorName: String,
)

/**
 * Compatibility reports from this device: after a game started with Play has run for a few minutes,
 * the app asks once how it ran in that emulator, and the answer goes to the game's compatibility on
 * GameHub ("On handhelds"). Reports are per game, emulator and device.
 */
class Compat(private val app: GameHubApp) {
    private val _request = MutableStateFlow<RatingRequest?>(null)
    val request: StateFlow<RatingRequest?> = _request.asStateFlow()

    /** Called when a play session closes. Asks only after a real session, and only once per game + emulator. */
    suspend fun afterSession(session: PlaySession, seconds: Long) {
        if (seconds < MIN_SECONDS || session.emulator.isEmpty()) return
        val conn = app.settings.current().connections.firstOrNull { it.id == session.connectionId } ?: return
        val info = runCatching { app.api(conn).compat(session.romId, session.emulator, DEVICE) }.getOrNull() ?: return
        // Older servers would file the answer as an in-browser report; only ask newer ones.
        if (!info.supportsDeviceReports || info.mine != null) return
        _request.value = RatingRequest(session.connectionId, session.romId, session.title, session.emulator, session.emulatorName)
    }

    fun dismiss() {
        _request.value = null
    }

    /** Sends the answer; returns an error message, or null when it was saved. */
    suspend fun answer(req: RatingRequest, rating: String): String? {
        _request.value = null
        val conn = app.settings.current().connections.firstOrNull { it.id == req.connectionId } ?: return "Connection not found"
        return runCatching { app.api(conn).reportCompat(req.romId, rating, null, req.emulator, req.emulatorName, DEVICE) }
            .fold({ null }, { it.message ?: "Couldn't save the rating" })
    }

    companion object {
        /** Sessions shorter than this don't ask (the emulator probably failed to start, or it was a quick look). */
        const val MIN_SECONDS = 180L

        /** This device as reports name it, e.g. "AYN Thor". */
        val DEVICE: String = Build.MODEL.let { m -> if (m.startsWith(Build.MANUFACTURER, ignoreCase = true)) m else "${Build.MANUFACTURER} $m" }.trim()

        /**
         * A stable id for an emulator across devices: "retroarch:fceumm" for any RetroArch build,
         * else the app's package ("com.github.stenzek.duckstation").
         */
        fun emulatorKey(launcher: Launcher): String =
            if (launcher.core != null) "retroarch:${launcher.core}" else launcher.packageName
    }
}
