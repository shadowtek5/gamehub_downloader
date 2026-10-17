package com.gamehub.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamehub.downloader.BuildConfig
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.data.Connection
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.launch

/** Result of pinging a connection: null = checking. */
data class ConnStatus(val ok: Boolean?, val text: String)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditConnection: (String?) -> Unit,
    onOpenFolders: (String?) -> Unit,
    onOpenControls: () -> Unit,
    onOpenSystems: (String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val statuses = remember { mutableStateMapOf<String, ConnStatus>() }

    val connections = settings?.connections.orEmpty()
    LaunchedEffect(connections) {
        connections.forEach { c ->
            if (statuses[c.id] != null) return@forEach
            statuses[c.id] = ConnStatus(null, "Checking…")
            launch { statuses[c.id] = testConnection(app, c) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle("GameHub connections") }
            items(connections, key = { it.id }) { c ->
                ConnectionCard(
                    c = c,
                    active = c.id == settings?.activeConnection?.id,
                    status = statuses[c.id],
                    onActivate = { scope.launch { app.settings.setActiveConnection(c.id) } },
                    onEdit = { onEditConnection(c.id) },
                    onFolders = { onOpenFolders(c.id) },
                    onTest = {
                        statuses[c.id] = ConnStatus(null, "Checking…")
                        scope.launch { statuses[c.id] = testConnection(app, c) }
                    },
                )
            }
            item { DeckButton("Add connection", { onEditConnection(null) }, icon = Icons.Default.Add, primary = connections.isEmpty()) }

            item { SectionTitle("Library", Modifier.padding(top = 14.dp)) }
            item {
                val c = settings?.activeConnection
                SettingsRow(
                    Icons.Default.Visibility,
                    "Show / hide systems",
                    if (c == null || c.hiddenSystems.isEmpty()) "All systems shown. One tap to keep only Nintendo, Sega & Sony"
                    else "${c.hiddenSystems.size} system${if (c.hiddenSystems.size == 1) "" else "s"} hidden",
                ) { DeckButton("Open", { onOpenSystems(c?.id) }, enabled = c != null) }
            }

            item { SectionTitle("Download folders", Modifier.padding(top = 14.dp)) }
            item {
                SettingsRow(
                    Icons.Default.Folder,
                    "Manage system folders",
                    "Choose where each system's ROMs are saved, per connection",
                ) { DeckButton("Open", { onOpenFolders(settings?.activeConnection?.id) }, enabled = connections.isNotEmpty()) }
            }

            item { SectionTitle("Controller", Modifier.padding(top = 14.dp)) }
            item {
                SettingsRow(
                    Icons.Default.SportsEsports,
                    "Controller mapping",
                    "Auto-detected per controller. Remap buttons or switch to Nintendo-style layout",
                ) { DeckButton("Open", onOpenControls) }
            }

            item { SectionTitle("Background downloads", Modifier.padding(top = 14.dp)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Downloads run as a foreground service with a progress notification, keep the CPU and Wi-Fi awake while active, " +
                            "resume after network drops, and alert you when each game finishes.",
                        color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                    BackgroundStatusCard()
                }
            }

            item { SectionTitle("About", Modifier.padding(top = 14.dp)) }
            item { Text("GameHub Downloader ${BuildConfig.VERSION_NAME}", color = DeckColors.TextDim, fontSize = 13.sp) }
        }
        HintBar(listOf(Hint(GameAction.Confirm, "Select"), Hint(GameAction.Back, "Back")))
    }
}

suspend fun testConnection(app: GameHubApp, c: Connection): ConnStatus = runCatching {
    val hb = app.api(c).heartbeat()
    if (hb.user == null) ConnStatus(false, "Server reachable, but the token wasn't accepted")
    else ConnStatus(true, "Connected as ${hb.user}${hb.role?.let { " ($it)" } ?: ""} • ${hb.games ?: 0} games • GameHub ${hb.version ?: ""}".trim())
}.getOrElse { ConnStatus(false, it.message ?: "Couldn't reach server") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(
    c: Connection,
    active: Boolean,
    status: ConnStatus?,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onFolders: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Dns, null, tint = if (active) DeckColors.Accent else DeckColors.TextDim)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "ACTIVE",
                            Modifier.clip(RoundedCornerShape(4.dp)).background(DeckColors.AccentSoft).padding(horizontal = 6.dp, vertical = 2.dp),
                            color = DeckColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(c.baseUrl, color = DeckColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (status.ok) { true -> Icons.Default.CheckCircle; false -> Icons.Default.Error; null -> Icons.Default.HourglassEmpty },
                    null, Modifier.size(16.dp),
                    tint = when (status.ok) { true -> DeckColors.Success; false -> DeckColors.Error; null -> DeckColors.TextDim },
                )
                Spacer(Modifier.width(6.dp))
                Text(status.text, fontSize = 12.sp, color = DeckColors.TextDim)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active) DeckButton("Make active", onActivate, icon = Icons.Default.Star)
            DeckButton("Edit", onEdit, icon = Icons.Default.Edit)
            DeckButton("Folders", onFolders, icon = Icons.Default.Folder)
            DeckButton("Test", onTest, icon = Icons.Default.CheckCircle)
        }
    }
}

@Composable
fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = DeckColors.Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = DeckColors.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
