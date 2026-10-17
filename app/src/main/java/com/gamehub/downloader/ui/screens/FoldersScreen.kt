package com.gamehub.downloader.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
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
import com.gamehub.downloader.api.Platform
import com.gamehub.downloader.data.FolderMapping
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckFocusable
import com.gamehub.downloader.ui.components.DeckToggle
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.rememberDeckLayout
import com.gamehub.downloader.ui.rememberFolderPicker
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoldersScreen(initialConnectionId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val layout = rememberDeckLayout()

    var selectedId by remember { mutableStateOf(initialConnectionId) }
    val connection = settings?.connections?.let { list -> list.firstOrNull { it.id == selectedId } ?: settings?.activeConnection }

    var allPlatforms by remember { mutableStateOf<List<Platform>>(emptyList()) }
    // Only systems shown in the library (Settings › Show / hide systems).
    val platforms = allPlatforms.filter { connection?.isSystemVisible(it.slug) != false }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connection?.id) {
        val c = connection ?: return@LaunchedEffect
        loading = true
        error = null
        runCatching { app.api(c).platforms() }
            .onSuccess { allPlatforms = it }
            .onFailure { allPlatforms = emptyList(); error = it.message ?: "Couldn't load systems" }
        loading = false
    }

    // Verify we still hold write access to each folder (it's lost if the user revokes it or removes the SD card).
    val folders = settings?.folders.orEmpty().filter { it.connectionId == connection?.id }
    LaunchedEffect(folders, connection?.baseFolderUri) {
        val uris = folders.map { it.treeUri } + listOfNotNull(connection?.baseFolderUri)
        access = withContext(Dispatchers.IO) { uris.distinct().associateWith { FolderStorage.hasAccess(app, it) } }
    }

    var pickingFor by remember { mutableStateOf<Platform?>(null) }
    val pickSystem = rememberFolderPicker { uri ->
        val p = pickingFor ?: return@rememberFolderPicker
        val c = connection ?: return@rememberFolderPicker
        scope.launch { app.settings.setFolder(FolderMapping(c.id, p.slug, p.name, uri.toString())) }
    }
    val pickBase = rememberFolderPicker { uri ->
        val c = connection ?: return@rememberFolderPicker
        scope.launch { app.settings.setBaseFolder(c.id, uri.toString()) }
    }

    fun useBase(targets: List<Platform>) {
        val c = connection ?: return
        val base = c.baseFolderUri ?: return
        scope.launch {
            val mappings = withContext(Dispatchers.IO) {
                targets.mapNotNull { p ->
                    val m = FolderMapping(c.id, p.slug, p.name, base, subPath = p.slug)
                    if (FolderStorage.resolve(app, m) != null) m else null
                }
            }
            app.settings.setFolders(mappings)
            info = "Mapped ${mappings.size} system${if (mappings.size == 1) "" else "s"} to sub-folders of the base folder"
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Download folders", onBack)
        if (connection == null) {
            EmptyState("No connections", "Add a GameHub connection first.", "Back", onBack)
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val conns = settings?.connections.orEmpty()
            if (conns.size > 1) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(conns, key = { it.id }) { c ->
                            DeckFocusable(
                                onClick = { selectedId = c.id },
                                shape = RoundedCornerShape(50),
                                background = if (c.id == connection.id) DeckColors.Accent else DeckColors.Surface,
                            ) { Text(c.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }

            // ---- Base folder ----
            item { SectionTitle("ROMs base folder", Modifier.padding(top = 6.dp)) }
            item {
                val base = connection.baseFolderUri
                val baseOk = base != null && access[base] == true
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Optional. Pick one parent folder (e.g. ROMs) and each system can use a sub-folder named after it, " +
                            "like ROMs/snes. Systems with their own folder below aren't affected.",
                        color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(base == null, baseOk)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                base == null -> "Not set"
                                !baseOk -> "${FolderStorage.describe(base)} (access lost)"
                                else -> FolderStorage.describe(base)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeckButton(if (base == null) "Choose base folder" else "Change", { pickBase(null) }, icon = Icons.Default.CreateNewFolder, primary = base == null)
                        val unset = platforms.filter { p -> folders.none { it.systemKey == p.slug } }
                        if (baseOk && unset.isNotEmpty()) {
                            DeckButton("Use for ${unset.size} unset system${if (unset.size == 1) "" else "s"}", { useBase(unset) }, icon = Icons.Default.AutoFixHigh)
                        }
                        if (base != null) DeckButton("Clear", { scope.launch { app.settings.setBaseFolder(connection.id, null) } }, icon = Icons.Default.Clear)
                    }
                    info?.let { Text(it, color = DeckColors.Success, fontSize = 12.sp) }
                }
            }

            // ---- Per-system folders ----
            item {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Systems on ${connection.name}", Modifier.weight(1f))
                    Text("${folders.size}/${platforms.size} set", color = DeckColors.TextDim, fontSize = 12.sp)
                }
            }
            when {
                loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DeckColors.Accent) }
                }
                error != null -> item { Text(error ?: "", color = DeckColors.Error) }
                else -> items(platforms, key = { it.slug }) { p ->
                    val mapping = folders.firstOrNull { it.systemKey == p.slug }
                    SystemFolderRow(
                        platform = p,
                        mapping = mapping,
                        ok = mapping != null && access[mapping.treeUri] == true,
                        stacked = layout.isPortrait || layout.compact,
                        canUseBase = connection.baseFolderUri != null && access[connection.baseFolderUri] == true,
                        onChoose = { pickingFor = p; pickSystem(null) },
                        onUseBase = { useBase(listOf(p)) },
                        onClear = { scope.launch { app.settings.clearFolder(connection.id, p.slug) } },
                        onExtract = { on -> scope.launch { app.settings.setExtract(connection.id, p.slug, on) } },
                    )
                }
            }
        }
        if (!layout.compact) HintBar(listOf(Hint(GameAction.Confirm, "Select"), Hint(GameAction.Back, "Back")))
    }
}

@Composable
private fun StatusIcon(unset: Boolean, ok: Boolean) {
    when {
        unset -> Icon(Icons.Default.RadioButtonUnchecked, null, Modifier.size(18.dp), tint = DeckColors.TextDim)
        ok -> Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = DeckColors.Success)
        else -> Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = DeckColors.Warning)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemFolderRow(
    platform: Platform,
    mapping: FolderMapping?,
    ok: Boolean,
    stacked: Boolean,
    canUseBase: Boolean,
    onChoose: () -> Unit,
    onUseBase: () -> Unit,
    onClear: () -> Unit,
    onExtract: (Boolean) -> Unit,
) {
    val info: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(mapping == null, ok)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(platform.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text("${platform.games} games", color = DeckColors.TextDim, fontSize = 12.sp)
                }
                Text(
                    when {
                        mapping == null -> "Not set — you'll be asked on first download"
                        !ok -> "${FolderStorage.describe(mapping)} (access lost — choose again)"
                        else -> FolderStorage.describe(mapping)
                    },
                    color = if (mapping != null && !ok) DeckColors.Warning else DeckColors.TextDim,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val actions: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(if (mapping == null) "Choose" else "Change", onChoose, icon = Icons.Default.FolderOpen)
            if (canUseBase && mapping?.subPath != platform.slug) DeckButton("Use base/${platform.slug}", onUseBase, icon = Icons.Default.AutoFixHigh)
            if (mapping != null) DeckButton("Clear", onClear, icon = Icons.Default.Clear)
        }
    }
    val bg = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp)
    Column(bg, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stacked) {
            info(Modifier.fillMaxWidth())
            actions()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                info(Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                actions()
            }
        }
        if (mapping != null) {
            DeckToggle(
                label = "Extract .zip / .7z after download",
                description = "Unpack into this folder and delete the archive",
                checked = mapping.extract,
                onCheckedChange = onExtract,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
