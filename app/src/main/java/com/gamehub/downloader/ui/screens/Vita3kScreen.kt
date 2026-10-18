package com.gamehub.downloader.ui.screens

import android.net.Uri
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.data.Vita3kApp
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckDialog
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.rememberFolderPicker
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.launch

/** Games installed in Vita3K, with play / uninstall. */
@Composable
fun Vita3kScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val apps by app.vita3k.apps.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Vita3kApp?>(null) }

    val pickFolder = rememberFolderPicker { uri: Uri ->
        if (app.vita3k.isVita3kFolder(uri.toString())) scope.launch { app.settings.setVita3kFolder(uri.toString()) }
        else message = "That isn't Vita3K's folder — pick the one that contains ux0 (e.g. SD card › Vita3k)"
    }
    LaunchedEffect(settings?.vita3kFolderUri) { app.vita3k.refresh() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Vita3K", onBack) {
            DeckButton("Refresh", { scope.launch { app.vita3k.refresh() } }, icon = Icons.Default.Refresh)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val folder = settings?.vita3kFolderUri
                    Text(if (folder == null) "Vita3K folder not set" else "Vita3K folder: ${FolderStorage.describe(folder)}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pick the folder Vita3K uses for storage (the one containing ux0 — see Vita3K's settings). " +
                            "Games are installed into it straight from downloaded zips; uninstalling keeps save data." +
                            if (!app.vita3k.isAppInstalled()) " Vita3K itself doesn't seem to be installed." else "",
                        color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                    DeckButton(if (folder == null) "Choose Vita3K folder" else "Change", { pickFolder(null) }, icon = Icons.Default.FolderOpen, primary = folder == null)
                    message?.let { Text(it, color = DeckColors.Warning, fontSize = 13.sp) }
                }
            }
            item { SectionTitle("Installed games (${apps.size})", Modifier.padding(top = 8.dp)) }
            items(apps.values.sortedBy { it.title.lowercase() }, key = { it.titleId }) { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.titleId, color = DeckColors.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(10.dp))
                    DeckButton("Play", { if (!app.vita3k.play(a.titleId)) message = "Couldn't start Vita3K" }, icon = Icons.Default.PlayArrow)
                    Spacer(Modifier.width(8.dp))
                    DeckButton("Uninstall", { confirm = a }, icon = Icons.Default.Delete)
                }
            }
        }
        HintBar(listOf(Hint(GameAction.Confirm, "Select"), Hint(GameAction.Back, "Back")))
    }

    confirm?.let { a ->
        DeckDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Default.Delete, null, tint = DeckColors.Error) },
            title = { Text("Uninstall ${a.title}?") },
            text = { Text("Removes ${a.titleId} (game, updates and DLC) from Vita3K. Save data is kept.", color = DeckColors.TextDim) },
            confirmButton = {
                DeckButton("Uninstall", {
                    confirm = null
                    scope.launch { message = if (app.vita3k.uninstall(a.titleId)) "Uninstalled ${a.title}" else "Couldn't uninstall ${a.title}" }
                }, icon = Icons.Default.Delete)
            },
            dismissButton = { FocusedButton("Cancel", primary = true) { confirm = null } },
        )
    }
}
