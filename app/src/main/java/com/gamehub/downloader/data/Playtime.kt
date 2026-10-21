package com.gamehub.downloader.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.gamehub.downloader.GameHubApp

/**
 * Time played on this device. The Play button opens a [PlaySession]; when the app comes back to
 * the front the session is closed and its length is added to the game's playtime on GameHub.
 *
 * With usage access (Settings › Playtime) the length is how long the emulator was actually on
 * screen; without it, it's the time until you came back to this app, capped at [MAX_GUESS_MS].
 */
class Playtime(private val app: GameHubApp) {

    /** Called right after the emulator was started. */
    suspend fun start(session: PlaySession) {
        val s = app.settings.current()
        // Close anything left open first (e.g. a game started while the app was in the background).
        finish()
        // The session is kept even with playtime tracking off: it's also when the app asks how the game ran.
        app.settings.setPlaySession(session)
        android.util.Log.i(TAG, "session started: rom ${session.romId} in ${session.packageName}")
        if (!s.trackPlaytime) return
        val conn = s.connections.firstOrNull { it.id == session.connectionId } ?: return
        runCatching { app.api(conn).startPlaying(session.romId) }
    }

    /** Closes the open session, if any. Returns the seconds recorded (null when nothing was open). */
    suspend fun finish(now: Long = System.currentTimeMillis()): Long? {
        val s = app.settings.current()
        val session = s.playSession ?: return null
        android.util.Log.i(TAG, "closing session for rom ${session.romId} after ${(now - session.startedAt) / 1000}s")
        app.settings.setPlaySession(null)
        val conn = s.connections.firstOrNull { it.id == session.connectionId } ?: return null
        val api = app.api(conn)
        // Back within a few seconds (e.g. the emulator failed to start): not a session.
        if (now - session.startedAt < MIN_SESSION_MS) {
            if (s.trackPlaytime) runCatching { api.stopPlaying(session.romId) }
            return null
        }
        val ms = foregroundMs(session.packageName, session.startedAt, now) ?: (now - session.startedAt).coerceAtMost(MAX_GUESS_MS)
        if (!s.trackPlaytime) {
            app.compat.afterSession(session, ms / 1000)
            return null
        }
        // startPlaying() already added the first minute.
        val seconds = (ms / 1000 - 60).coerceAtLeast(0)
        runCatching { api.addPlaytime(session.romId, seconds) }
            .onFailure { android.util.Log.w(TAG, "couldn't add playtime for rom ${session.romId}", it) }
        runCatching { api.stopPlaying(session.romId) }
        // Played for a while: ask how it ran (once per game and emulator).
        app.compat.afterSession(session, ms / 1000)
        return ms / 1000
    }

    fun hasUsageAccess(): Boolean {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** How long [pkg] was in the foreground between [from] and [to], from Android's usage events. */
    private fun foregroundMs(pkg: String, from: Long, to: Long): Long? {
        if (!hasUsageAccess()) return null
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return null
        var total = 0L
        var resumedAt: Long? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != pkg) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (resumedAt == null) resumedAt = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> resumedAt?.let { total += e.timeStamp - it; resumedAt = null }
            }
        }
        resumedAt?.let { total += to - it }
        return total
    }

    companion object {
        private const val TAG = "GameHubPlaytime"
        private const val MIN_SESSION_MS = 30_000L
        private const val MAX_GUESS_MS = 4 * 60 * 60 * 1000L
    }
}
