package com.gamehub.downloader.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.data.SaveSync
import com.gamehub.downloader.data.SaveSyncSettings
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckToggle
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.rememberFolderPicker
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.launch

/** Settings › Save sync: where each emulator keeps its saves, Sync now, conflicts and recent activity. */
@Composable
fun SaveSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GameHubApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val status by app.saveSync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.saveSync.loadLastReport() }
    // Confirmations ("Kept GameHub's save for …") and folder hints fade out on their own.
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(6_000)
            message = null
        }
    }

    val s = settings?.saveSync ?: SaveSyncSettings()
    val connection = settings?.activeConnection
    val ndsFolder = connection?.let { settings?.folderFor(it.id, "nds") }

    fun has(uri: Uri, vararg path: String): Boolean {
        var d = DocumentFile.fromTreeUri(context, uri) ?: return false
        for (p in path) d = d.findFile(p) ?: return false
        return true
    }
    val pickRetroarch = rememberFolderPicker { uri ->
        val dir = DocumentFile.fromTreeUri(context, uri)
        val hasStates = { d: DocumentFile -> d.listFiles().take(50).any { it.name.orEmpty().contains(".state", ignoreCase = true) } }
        val isStates = dir != null && (dir.name.equals("states", ignoreCase = true) || hasStates(dir) ||
            dir.listFiles().filter { it.isDirectory }.take(10).any { hasStates(it) })
        if (isStates) {
            message = "That's RetroArch's save states folder. Pick the \"saves\" folder next to it (Internal storage › RetroArch › saves)."
        } else {
            scope.launch { app.settings.updateSaveSync { it.copy(retroarchSavesUri = uri.toString()) } }
            message = null
        }
    }
    val pickStates = rememberFolderPicker { uri ->
        val dir = DocumentFile.fromTreeUri(context, uri)
        if (dir?.name.equals("saves", ignoreCase = true)) {
            message = "That's RetroArch's saves folder. Pick the \"states\" folder next to it (Internal storage › RetroArch › states)."
        } else {
            scope.launch { app.settings.updateSaveSync { it.copy(retroarchStatesUri = uri.toString()) } }
            message = null
        }
    }
    val pickPpsspp = rememberFolderPicker { uri ->
        if (has(uri, "SAVEDATA") || has(uri, "PSP", "SAVEDATA") || has(uri, "PSP")) {
            scope.launch { app.settings.updateSaveSync { it.copy(ppssppUri = uri.toString()) } }
            message = null
        } else message = "That isn't PPSSPP's folder — pick the PSP folder that contains SAVEDATA (e.g. SD card › Pspspp › PSP)"
    }
    val pickAzahar = rememberFolderPicker { uri ->
        if (has(uri, "sdmc")) {
            scope.launch { app.settings.updateSaveSync { it.copy(azaharUri = uri.toString()) } }
            message = null
        } else message = "That isn't Azahar's folder — pick the one that contains sdmc (e.g. SD card › Azahar)"
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Save sync", onBack) {
            DeckButton(
                if (status.running) "Syncing…" else "Sync now",
                { scope.launch { app.saveSync.syncAll() } },
                icon = Icons.Default.Sync,
                primary = true,
                enabled = !status.running && connection != null,
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card {
                    val r = status.report
                    Text(
                        when {
                            status.running -> status.progress
                            r == null -> "Not synced yet"
                            else -> r.summary()
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (r != null && !status.running)
                            "Last sync ${ago(r.at)}${if (r.auto) " (when the app opened)" else ""} with ${connection?.name ?: "GameHub"}"
                        else "Copies saves between this device's emulators and your GameHub account. The newer copy wins; " +
                            "if a save changed in both places you choose which to keep. Replaced saves on this device are backed up first.",
                        color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                    r?.errors?.take(5)?.forEach { Text("✗ $it", color = DeckColors.Error, fontSize = 12.sp) }
                    message?.let { Text(it, color = DeckColors.Warning, fontSize = 13.sp) }
                }
            }

            val conflicts = status.report?.conflicts.orEmpty()
            if (conflicts.isNotEmpty()) {
                item { SectionTitle("Needs attention (${conflicts.size})", Modifier.padding(top = 8.dp)) }
                items(conflicts, key = { it.id }) { c ->
                    Card {
                        Text("${c.title} · ${c.source}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Changed on this device" + (c.localModified?.let { " (${ago(it)})" } ?: "") +
                                " and on GameHub" + (c.remoteUpdatedAt?.let { " (${it.take(16).replace('T', ' ')} UTC)" } ?: "") +
                                ". Keep which one?",
                            color = DeckColors.TextDim, fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeckButton("Keep this device's", {
                                scope.launch { message = app.saveSync.resolve(c.id, SaveSync.Keep.DEVICE) }
                            }, icon = Icons.Default.CloudUpload, enabled = !status.running)
                            DeckButton("Keep GameHub's", {
                                scope.launch { message = app.saveSync.resolve(c.id, SaveSync.Keep.GAMEHUB) }
                            }, icon = Icons.Default.CloudDownload, enabled = !status.running)
                        }
                    }
                }
            }

            item { SectionTitle("Automatic", Modifier.padding(top = 8.dp)) }
            item {
                DeckToggle(
                    label = "Sync when the app opens",
                    description = "At most every 10 minutes. You'll see a short message only when something changed.",
                    checked = s.syncOnOpen,
                    onCheckedChange = { on -> scope.launch { app.settings.updateSaveSync { it.copy(syncOnOpen = on) } } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionTitle("Emulators", Modifier.padding(top = 8.dp)) }
            item {
                SourceCard(
                    "RetroArch",
                    "In-game saves (.srm), shared with GameHub's browser player. Pick RetroArch's saves folder (Internal storage › RetroArch › saves).",
                    s.retroarchSavesUri,
                    onPick = { pickRetroarch(null) },
                    onClear = { scope.launch { app.settings.updateSaveSync { it.copy(retroarchSavesUri = null) } } },
                )
            }
            item {
                SourceCard(
                    "RetroArch save states",
                    "Your RetroArch slots appear in GameHub's save states (\"RetroArch slot N - device\"), and the newest state from GameHub " +
                        "or another device goes into slot 9 (never over a state you saved there yourself). Pick RetroArch's states folder " +
                        "(Internal storage › RetroArch › states). For screenshots, turn on Save State Thumbnails in RetroArch › Settings › Saving. " +
                        "States only load in the same emulator core, so a few systems may not carry over.",
                    s.retroarchStatesUri,
                    onPick = { pickStates(null) },
                    onClear = { scope.launch { app.settings.updateSaveSync { it.copy(retroarchStatesUri = null) } } },
                )
            }
            item {
                SourceCard(
                    "PPSSPP (PSP)",
                    "SAVEDATA folders, one backup per game. Pick PPSSPP's PSP folder (e.g. SD card › Pspspp › PSP).",
                    s.ppssppUri,
                    onPick = { pickPpsspp(null) },
                    onClear = { scope.launch { app.settings.updateSaveSync { it.copy(ppssppUri = null) } } },
                )
            }
            item {
                SourceCard(
                    "Azahar (3DS)",
                    "Game save data per title. Pick Azahar's folder (the one containing sdmc, e.g. SD card › Azahar).",
                    s.azaharUri,
                    onPick = { pickAzahar(null) },
                    onClear = { scope.launch { app.settings.updateSaveSync { it.copy(azaharUri = null) } } },
                )
            }
            item {
                DeckToggle(
                    label = "Vita3K (PS Vita)",
                    description = if (settings?.vita3kFolderUri == null) "Set Vita3K's folder in Settings › Vita3K first"
                    else "Save data from ux0/user/00/savedata, restored only for games installed in Vita3K",
                    checked = s.vita && settings?.vita3kFolderUri != null,
                    onCheckedChange = { on -> scope.launch { app.settings.updateSaveSync { it.copy(vita = on) } } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                DeckToggle(
                    label = "Nintendo DS (melonDS / DraStic)",
                    description = if (ndsFolder == null) "Set the Nintendo DS download folder first (Settings › Download folders)"
                    else ".sav files next to the ROMs in ${FolderStorage.describe(ndsFolder)}",
                    checked = s.nds && ndsFolder != null,
                    onCheckedChange = { on -> scope.launch { app.settings.updateSaveSync { it.copy(nds = on) } } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "DuckStation, AetherSX2 and Dolphin keep saves in their private Android/data folder, which other apps can't read. " +
                        "Play those systems in RetroArch to sync their saves.",
                    color = DeckColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            val log = status.report?.log.orEmpty().filter { it.action != SaveSync.Action.CONFLICT }
            if (log.isNotEmpty()) {
                item { SectionTitle("Last sync", Modifier.padding(top = 8.dp)) }
                items(log.take(50), key = { it.id + it.action }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DeckColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (e.action) {
                                SaveSync.Action.UPLOADED -> "↑ Uploaded"
                                SaveSync.Action.DOWNLOADED -> "↓ Downloaded"
                                SaveSync.Action.ERROR -> "✗ Failed"
                                SaveSync.Action.CONFLICT -> "! Conflict"
                            },
                            color = if (e.action == SaveSync.Action.ERROR) DeckColors.Error else DeckColors.Accent,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp),
                        )
                        Text("${e.title} · ${e.source}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        HintBar(listOf(Hint(GameAction.Confirm, "Select"), Hint(GameAction.Back, "Back")))
    }
}

private fun ago(t: Long): CharSequence =
    if (System.currentTimeMillis() - t < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(t)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun SourceCard(name: String, help: String, uri: String?, onPick: () -> Unit, onClear: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(if (uri == null) "Not set up" else FolderStorage.describe(uri), color = if (uri == null) DeckColors.TextDim else DeckColors.Success, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            if (uri != null) {
                DeckButton("Turn off", onClear)
                Spacer(Modifier.width(8.dp))
            }
            DeckButton(if (uri == null) "Choose folder" else "Change", onPick, icon = Icons.Default.FolderOpen, primary = uri == null)
        }
        Text(help, color = DeckColors.TextDim, fontSize = 12.sp)
    }
}
