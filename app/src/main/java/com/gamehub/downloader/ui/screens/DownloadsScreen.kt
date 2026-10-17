package com.gamehub.downloader.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.download.DownloadState
import com.gamehub.downloader.download.DownloadTask
import com.gamehub.downloader.download.formatBytes
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.theme.DeckColors

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val tasks by app.downloads.tasks.collectAsState()
    val settings by app.settings.settings.collectAsState(initial = null)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Downloads", onBack) {
            if (tasks.any { !it.isActive }) DeckButton("Clear finished", { app.downloads.clearFinished() }, icon = Icons.Default.ClearAll)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { BackgroundStatusCard() }
            if (tasks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("No downloads yet. Open a game and press Download.", color = DeckColors.TextDim)
                    }
                }
            } else {
                val active = tasks.filter { it.isActive }
                val done = tasks.filterNot { it.isActive }.reversed()
                if (active.isNotEmpty()) {
                    item { SectionTitle("In progress (${active.size})", Modifier.padding(top = 8.dp)) }
                    items(active, key = { it.id }) { t -> TaskRow(t, thumb(app, settings?.connections, t)) }
                }
                if (done.isNotEmpty()) {
                    item { SectionTitle("Finished", Modifier.padding(top = 8.dp)) }
                    items(done, key = { it.id }) { t -> TaskRow(t, thumb(app, settings?.connections, t)) }
                }
            }
        }
        HintBar(listOf(Hint(GameAction.Confirm, "Select"), Hint(GameAction.Back, "Back")))
    }
}

private fun thumb(app: GameHubApp, connections: List<com.gamehub.downloader.data.Connection>?, t: DownloadTask): String? {
    val c = connections?.firstOrNull { it.id == t.connectionId } ?: return null
    return GameHubApi(app.http, c).mediaUrl(t.boxartUrl, 160)
}

@Composable
private fun TaskRow(t: DownloadTask, imageUrl: String?) {
    val app = LocalContext.current.applicationContext as GameHubApp
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(54.dp).aspectRatio(0.75f).clip(RoundedCornerShape(6.dp)).background(DeckColors.SurfaceHigh)) {
            if (imageUrl != null) AsyncImage(imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.systemName} • ${t.fileName}", color = DeckColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            when (t.state) {
                DownloadState.Running, DownloadState.Retrying, DownloadState.Queued, DownloadState.Extracting -> {
                    val pct = t.percent
                    if ((t.state == DownloadState.Running || t.state == DownloadState.Extracting) && pct != null) {
                        LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth(), color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = if (t.state == DownloadState.Retrying) DeckColors.Warning else DeckColors.TextDim, trackColor = DeckColors.SurfaceHigh)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (t.state) {
                            DownloadState.Queued -> "Queued"
                            DownloadState.Retrying -> t.error ?: "Reconnecting…"
                            DownloadState.Extracting -> "Extracting archive…" + (pct?.let { " $it%" } ?: "")
                            else -> buildString {
                                pct?.let { append("$it% • ") }
                                append(formatBytes(t.downloadedBytes))
                                if (t.totalBytes > 0) append(" / ${formatBytes(t.totalBytes)}")
                                if (t.bytesPerSecond > 0) append(" • ${formatBytes(t.bytesPerSecond)}/s")
                            }
                        },
                        fontSize = 12.sp, color = DeckColors.TextDim,
                    )
                }
                DownloadState.Completed -> StatusLine(
                    Icons.Default.CheckCircle, DeckColors.Success,
                    "Saved to ${FolderStorage.describe(t.folder)} • " +
                        (t.extractedFiles?.let { "extracted $it file${if (it == 1) "" else "s"}" } ?: formatBytes(t.downloadedBytes)),
                )
                DownloadState.Failed -> StatusLine(Icons.Default.Error, DeckColors.Error, t.error ?: "Failed")
                DownloadState.Cancelled -> StatusLine(Icons.Default.Close, DeckColors.TextDim, "Cancelled")
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            t.isActive -> DeckButton("Cancel", { app.downloads.cancel(t.id) }, icon = Icons.Default.Close)
            t.state == DownloadState.Failed || t.state == DownloadState.Cancelled -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckButton("Retry", { app.downloads.retry(t.id) }, icon = Icons.Default.Refresh)
                DeckButton("Remove", { app.downloads.remove(t.id) }, icon = Icons.Default.Delete)
            }
            else -> DeckButton("Remove", { app.downloads.remove(t.id) }, icon = Icons.Default.Delete)
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = DeckColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Shows whether the app can keep downloading while the device sleeps, with one-tap fixes for
 * battery optimisation and notification permission.
 */
@SuppressLint("BatteryLife")
@Composable
fun BackgroundStatusCard() {
    val context = LocalContext.current
    // Re-check whenever the screen resumes (the user may have just changed a system setting).
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val unrestricted = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val notifications = remember(tick) {
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    if (unrestricted && notifications) return

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!unrestricted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryChargingFull, null, tint = DeckColors.Warning)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Allow unrestricted battery use so long downloads keep going when the screen is off.",
                    Modifier.weight(1f), fontSize = 13.sp,
                )
                Spacer(Modifier.width(10.dp))
                DeckButton("Allow", {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        )
                    }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                })
            }
        }
        if (!notifications) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, tint = DeckColors.Warning)
                Spacer(Modifier.width(10.dp))
                Text("Notifications are off — you won't see progress or completion alerts.", Modifier.weight(1f), fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                DeckButton("Enable", {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                })
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.AutoMirrored.Filled.ArrowBack, onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}
