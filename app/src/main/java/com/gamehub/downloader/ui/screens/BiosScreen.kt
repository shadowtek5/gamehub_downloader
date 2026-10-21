package com.gamehub.downloader.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.data.Bios
import com.gamehub.downloader.data.BiosDestination
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.download.formatBytes
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.rememberFolderPicker
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.launch

/**
 * Settings › BIOS & firmware: copies the BIOS files stored on GameHub into every emulator on this
 * device that needs them — RetroArch's system folder, and the folder each standalone emulator reads.
 */
@Composable
fun BiosScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val bios = remember { Bios(app) }
    var destinations by remember { mutableStateOf<List<BiosDestination>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val conn = settings?.activeConnection

    fun reload() {
        val c = conn ?: return
        val s = settings ?: return
        scope.launch {
            error = null
            runCatching { bios.destinations(app.api(c), app.emulatorCatalogs.get(c), s) }
                .onSuccess { destinations = it }
                .onFailure { error = it.message ?: "Couldn't load BIOS files" }
        }
    }
    LaunchedEffect(conn?.id, settings?.retroarchSystemUri, settings?.biosFolders) { reload() }

    // One picker; [pickingFor] says which emulator the chosen folder is for.
    var pickingFor by remember { mutableStateOf<String?>(null) }
    val pick = rememberFolderPicker { uri ->
        val id = pickingFor ?: return@rememberFolderPicker
        scope.launch {
            if (id == "retroarch") {
                if (bios.looksLikeSystemFolder(uri.toString())) app.settings.setRetroarchSystem(uri.toString())
                else message = "That isn't RetroArch's system folder — pick RetroArch › system (e.g. Internal storage › RetroArch › system)"
            } else app.settings.setBiosFolder(id, uri.toString())
        }
    }
    fun choose(d: BiosDestination) {
        pickingFor = d.id
        val initial = d.suggested?.let {
            runCatching { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", it) }.getOrNull()
        }
        pick(initial ?: d.folderUri?.let { Uri.parse(it) })
    }

    fun install(targets: List<BiosDestination>) {
        val c = conn ?: return
        val work = targets.filter { it.folderUri != null && it.toInstall.isNotEmpty() }
        if (work.isEmpty() || busy != null) return
        busy = if (work.size == 1) work.first().id else "all"
        scope.launch {
            val notes = work.map { d ->
                runCatching {
                    val n = bios.install(app.api(c), d.folderUri!!, d.toInstall) { i, item ->
                        message = "${d.name}: copying ${item.path} (${i + 1} of ${d.toInstall.size})…"
                    }
                    "${d.name}: $n of ${d.toInstall.size}"
                }.getOrElse { "${d.name}: ${it.message ?: "failed"}" }
            }
            busy = null
            message = "Installed " + notes.joinToString(" · ")
            reload()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("BIOS & firmware", onBack) {
            DeckButton("Refresh", { reload() }, icon = Icons.Default.Refresh)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Some systems (PlayStation, PS2, Saturn, Sega CD, DS, 3DS…) need BIOS or firmware files. Upload them once in " +
                            "GameHub (Settings › Firmware) and install them here into each emulator on this device that uses them. " +
                            "Pick the folder each emulator reads them from once; files already there are skipped.",
                        color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                    val ready = destinations.orEmpty().filter { it.folderUri != null && it.toInstall.isNotEmpty() }
                    if (ready.isNotEmpty()) {
                        DeckButton(
                            "Install all missing (${ready.sumOf { it.toInstall.size }} files)", { install(ready) },
                            icon = Icons.Default.Download, primary = true, enabled = busy == null,
                        )
                    }
                    message?.let { Text(it, color = DeckColors.Success, fontSize = 13.sp) }
                }
            }
            val list = destinations
            when {
                error != null -> item { Text(error ?: "", color = DeckColors.Error) }
                list == null -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DeckColors.Accent) }
                }
                list.isEmpty() -> item {
                    Text(
                        "Nothing to install: either GameHub doesn't have BIOS files yet (upload them in GameHub › Settings › Firmware), " +
                            "or none of the emulators that use them are installed on this device.",
                        color = DeckColors.TextDim,
                    )
                }
                else -> {
                    item { SectionTitle("Emulators on this device", Modifier.padding(top = 8.dp)) }
                    items(list, key = { it.id }) { d ->
                        DestinationCard(d, busy == d.id, busy == null, onChoose = { choose(d) }, onInstall = { install(listOf(d)) })
                    }
                }
            }
        }
        HintBar(listOf(Hint(GameAction.Confirm, "Select"), Hint(GameAction.Back, "Back")))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DestinationCard(d: BiosDestination, working: Boolean, enabled: Boolean, onChoose: () -> Unit, onInstall: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = if (d.folderUri != null && d.toInstall.isEmpty()) DeckColors.Success else DeckColors.Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, fontWeight = FontWeight.SemiBold)
                Text(d.systems.joinToString(", "), color = DeckColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                working -> CircularProgressIndicator(Modifier.size(22.dp), color = DeckColors.Accent, strokeWidth = 2.dp)
                d.folderUri != null && d.toInstall.isEmpty() -> Icon(Icons.Default.CheckCircle, "All installed", tint = DeckColors.Success)
            }
        }
        Text(d.where, color = DeckColors.TextDim, fontSize = 12.sp)
        Text(
            if (d.folderUri == null) "Folder: not chosen yet" else "Folder: ${FolderStorage.describe(d.folderUri)}",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${d.items.size - d.toInstall.size} of ${d.items.size} file${if (d.items.size == 1) "" else "s"} from GameHub are in this folder",
            color = DeckColors.TextDim, fontSize = 12.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(if (d.folderUri == null) "Choose folder" else "Change folder", onChoose, icon = Icons.Default.FolderOpen, primary = d.folderUri == null)
            if (d.folderUri != null && d.toInstall.isNotEmpty()) {
                DeckButton("Install ${d.toInstall.size}", onInstall, icon = Icons.Default.Download, primary = true, enabled = enabled)
            }
            DeckButton(if (open) "Hide files" else "Show files", { open = !open }, icon = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore)
        }
        if (open) d.items.forEach { f ->
            Text(
                (if (f.onDevice) "✓ " else "• ") + f.path + " · ${f.system}" + (if (f.size > 0) " (${formatBytes(f.size)})" else ""),
                color = if (f.onDevice) DeckColors.Success else DeckColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
