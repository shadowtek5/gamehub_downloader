package com.gamehub.downloader.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.compose.AsyncImage
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.download.DownloadState
import com.gamehub.downloader.download.formatBytes
import com.gamehub.downloader.ui.theme.DeckColors
import com.gamehub.downloader.ui.theme.DeckTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** What the second screen shows about the game in focus (set by the library and game pages). */
data class SpotlightGame(
    val title: String,
    val system: String?,
    val boxartUrl: String?,
    val heroUrl: String?,
    val description: String? = null,
    val details: String? = null,
)

object SecondScreen {
    val spotlight = MutableStateFlow<SpotlightGame?>(null)

    /**
     * Shows [Presentation] content on a secondary display (the AYN Thor's lower screen) while
     * [activity] is in the foreground. Call [attach] in onStart and [detach] in onStop.
     */
    private var presentation: Presentation? = null
    private var listener: DisplayManager.DisplayListener? = null

    fun attach(activity: Activity) {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        show(activity, dm)
        listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = show(activity, dm)
            override fun onDisplayRemoved(displayId: Int) {
                if (presentation?.display?.displayId == displayId) dismiss()
            }
            override fun onDisplayChanged(displayId: Int) = Unit
        }.also { dm.registerDisplayListener(it, null) }
    }

    fun detach(activity: Activity) {
        listener?.let { (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        listener = null
        dismiss()
    }

    private fun dismiss() {
        runCatching { presentation?.dismiss() }
        presentation = null
    }

    private fun show(activity: Activity, dm: DisplayManager) {
        if (presentation != null) return
        // Never draw on the display the app itself is on.
        val own = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        val target = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != own } ?: return
        presentation = SpotlightPresentation(activity, target).also { p -> runCatching { p.show() }.onFailure { presentation = null } }
    }
}

private class SpotlightPresentation(private val activity: Activity, display: Display) : Presentation(activity, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Display only: never take focus or controller input from the app on the main screen.
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val view = ComposeView(context).apply {
            setContent { DeckTheme { SpotlightContent() } }
        }
        // Compose looks these up from the window's root view; a Presentation's window has none of its own.
        window?.decorView?.let { root ->
            (activity as? LifecycleOwner)?.let { root.setViewTreeLifecycleOwner(it) }
            (activity as? SavedStateRegistryOwner)?.let { root.setViewTreeSavedStateRegistryOwner(it) }
            (activity as? ViewModelStoreOwner)?.let { root.setViewTreeViewModelStoreOwner(it) }
        }
        setContentView(view)
    }
}

@Composable
private fun SpotlightContent() {
    val app = GameHubApp.instance
    val game by SecondScreen.spotlight.collectAsState()
    val tasks by app.downloads.tasks.collectAsState()
    val active = tasks.filter { it.isActive }

    Box(Modifier.fillMaxSize().background(DeckColors.Background)) {
        val g = game
        if (g != null) {
            (g.heroUrl ?: g.boxartUrl)?.let {
                AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, DeckColors.Background))))
            Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.fillMaxHeight(0.8f).aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)).background(DeckColors.SurfaceHigh)) {
                    g.boxartUrl?.let { AsyncImage(it, g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    g.system?.let { Text(it, color = DeckColors.Accent, fontWeight = FontWeight.SemiBold) }
                    g.details?.let { Text(it, color = DeckColors.TextDim, fontSize = 14.sp) }
                    g.description?.let { Text(it, color = DeckColors.Text, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 9, overflow = TextOverflow.Ellipsis) }
                }
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.SportsEsports, null, Modifier.height(64.dp).width(64.dp), tint = DeckColors.Accent)
                Spacer(Modifier.height(12.dp))
                Text("GameHub", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (active.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                active.take(3).forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (t.state) {
                                DownloadState.Queued -> "Queued"
                                DownloadState.Extracting -> "Extracting"
                                DownloadState.Installing -> "Installing"
                                DownloadState.Retrying -> "Reconnecting"
                                else -> (t.percent?.let { "$it% · " } ?: "") + formatBytes(t.downloadedBytes)
                            },
                            color = DeckColors.TextDim, fontSize = 13.sp,
                        )
                    }
                    if (t.state == DownloadState.Running) {
                        LinearProgressIndicator(
                            progress = { (t.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth(),
                            color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh,
                        )
                    }
                }
                if (active.size > 3) Text("+${active.size - 3} more queued", color = DeckColors.TextDim, fontSize = 12.sp)
            }
        }
    }
}
