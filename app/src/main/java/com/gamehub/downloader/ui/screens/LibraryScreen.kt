package com.gamehub.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gamehub.downloader.api.BrowseRom
import com.gamehub.downloader.api.Platform
import com.gamehub.downloader.data.InstalledIndex
import com.gamehub.downloader.data.SystemRules
import com.gamehub.downloader.download.DownloadState
import com.gamehub.downloader.download.DownloadTask
import com.gamehub.downloader.ui.DeckLayout
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.OnAction
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckDialog
import com.gamehub.downloader.ui.components.DeckFocusable
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.rememberDeckLayout
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.delay

@Composable
fun LibraryScreen(
    onOpenGame: (String, Long) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddConnection: () -> Unit,
    onOpenSystems: () -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val downloaded by vm.downloadedIds.collectAsState()
    val tasks by vm.downloads.collectAsState()
    val installed by vm.installed.collectAsState()
    // Files may have been added or removed outside the app; rescan when we come back.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.rescanFolders()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val connKey = state.connection?.id.orEmpty()
    val vitaApps by vm.vita3kApps.collectAsState()
    val vitaTitles = remember(vitaApps) { vitaApps.values.map { InstalledIndex.normalize(it.title, isFile = false) }.toSet() }
    val isInstalled: (BrowseRom) -> Boolean = { rom ->
        val key = InstalledIndex.normalize(rom.title, isFile = false)
        installed["$connKey|${rom.platformSlug}"]?.names?.contains(key) == true ||
            (SystemRules.supportsVita3k(rom.platformSlug) && key in vitaTitles)
    }
    val installedCount: (String) -> Int? = { slug -> installed["$connKey|$slug"]?.count }
    val layout = rememberDeckLayout()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var connectionPicker by remember { mutableStateOf(false) }
    // B with the search box open closes the search instead of leaving the app.
    androidx.activity.compose.BackHandler(enabled = searchOpen) {
        if (state.query.isNotEmpty()) vm.setQuery("")
        searchOpen = false
    }
    // B on the main screen: first back to "All games", then ask before exiting.
    var confirmExit by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = !searchOpen) {
        if (state.selected != null) vm.selectPlatform(null) else confirmExit = true
    }
    if (confirmExit) {
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        DeckDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Exit GameHub Downloader?") },
            text = { Text("Downloads in progress keep running in the background.", color = DeckColors.TextDim) },
            confirmButton = { DeckButton("Exit", { confirmExit = false; activity?.finish() }) },
            // Stay is focused so an extra B/A press doesn't close the app by accident.
            dismissButton = { FocusedButton("Stay", primary = true) { confirmExit = false } },
        )
    }
    val activeCount = tasks.count { it.isActive }

    OnAction { a ->
        when (a) {
            GameAction.PrevSystem -> vm.cyclePlatform(-1)
            GameAction.NextSystem -> vm.cyclePlatform(1)
            GameAction.Search -> searchOpen = !searchOpen
            GameAction.Downloads -> onOpenDownloads()
            GameAction.Settings -> onOpenSettings()
            GameAction.SwitchConnection -> if (state.connections.size > 1) connectionPicker = true
            else -> {}
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = state.connection?.name ?: "GameHub",
            activeDownloads = activeCount,
            searchOpen = searchOpen,
            query = state.query,
            onQuery = vm::setQuery,
            onToggleSearch = {
                if (searchOpen && state.query.isNotEmpty()) vm.setQuery("")
                searchOpen = !searchOpen
            },
            onConnection = { if (state.connections.isNotEmpty()) connectionPicker = true },
            onDownloads = onOpenDownloads,
            onSettings = onOpenSettings,
            compact = layout.compact || layout.short,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.settingsLoaded -> Unit
                state.connection == null -> EmptyState(
                    title = "Connect to GameHub",
                    body = "Add your GameHub server and an API token to browse and download your games.",
                    action = "Add connection",
                    onAction = onAddConnection,
                )
                state.allPlatforms.isNotEmpty() && state.platforms.isEmpty() -> EmptyState(
                    title = "All systems are hidden",
                    body = "Choose which systems to show in Settings › Show / hide systems.",
                    action = "Choose systems",
                    onAction = onOpenSystems,
                )
                state.platformsError != null && state.platforms.isEmpty() -> EmptyState(
                    title = "Can't reach ${state.connection?.name}",
                    body = state.platformsError ?: "",
                    action = "Retry",
                    onAction = vm::reload,
                )
                layout.useRail -> Row(Modifier.fillMaxSize()) {
                    SystemRail(state.platforms, state.selected, vm::selectPlatform, installedCount, Modifier.width(if (layout.short) 196.dp else 230.dp).fillMaxHeight())
                    GameGrid(state, downloaded, isInstalled, tasks, layout, vm, onOpenGame, Modifier.weight(1f))
                }
                else -> Column(Modifier.fillMaxSize()) {
                    SystemTabs(state.platforms, state.selected, vm::selectPlatform)
                    GameGrid(state, downloaded, isInstalled, tasks, layout, vm, onOpenGame, Modifier.weight(1f))
                }
            }
        }

        if (!layout.compact) {
            HintBar(
                if (layout.isPortrait) listOf(Hint(GameAction.Confirm, "Open"), Hint(GameAction.Search, "Search"), Hint(GameAction.Downloads, "Downloads"))
                else listOf(
                    Hint(GameAction.Confirm, "Open"), Hint(GameAction.PrevSystem, ""), Hint(GameAction.NextSystem, "System"),
                    Hint(GameAction.Search, "Search"), Hint(GameAction.Downloads, "Downloads"), Hint(GameAction.Settings, "Settings"),
                )
            )
        }
    }

    if (connectionPicker) {
        DeckDialog(
            onDismissRequest = { connectionPicker = false },
            containerColor = DeckColors.Surface,
            title = { Text("Switch connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.connections.forEach { c ->
                        DeckFocusable(onClick = { vm.switchConnection(c.id); connectionPicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Dns, null, tint = if (c.id == state.connection?.id) DeckColors.Accent else DeckColors.TextDim)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.SemiBold)
                                    Text(c.baseUrl, color = DeckColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (c.id == state.connection?.id) Icon(Icons.Default.CheckCircle, null, tint = DeckColors.Accent)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { DeckButton("Close", { connectionPicker = false }) },
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    activeDownloads: Int,
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onConnection: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    compact: Boolean,
) {
    val searchFocus = remember { FocusRequester() }
    val searchButtonFocus = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(searchOpen) }
    LaunchedEffect(searchOpen) {
        if (searchOpen) runCatching { searchFocus.requestFocus() }
        else if (wasOpen) runCatching { searchButtonFocus.requestFocus() } // closing search: keep a highlight
        wasOpen = searchOpen
    }
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Background).padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).focusRequester(searchFocus),
                singleLine = true,
                placeholder = { Text("Search games…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                colors = deckFieldColors(),
            )
        } else {
            Box(Modifier.weight(1f)) {
                DeckFocusable(onClick = onConnection, background = Color.Transparent) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SportsEsports, null, tint = DeckColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Text(title, fontSize = if (compact) 17.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        IconAction(if (searchOpen) Icons.Default.Close else Icons.Default.Search, onToggleSearch, Modifier.focusRequester(searchButtonFocus))
        Box {
            IconAction(Icons.Default.Download, onDownloads)
            if (activeDownloads > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(DeckColors.Accent),
                    contentAlignment = Alignment.Center,
                ) { Text("$activeDownloads", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
        IconAction(Icons.Default.Settings, onSettings)
    }
}

@Composable
fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    DeckFocusable(onClick = onClick, modifier = modifier, shape = CircleShape, background = DeckColors.Surface, focusedScale = 1.1f) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = DeckColors.Text) }
    }
}

@Composable
private fun SystemRail(
    platforms: List<Platform>,
    selected: String?,
    onSelect: (String?) -> Unit,
    installedCount: (String) -> Int?,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier.background(DeckColors.Background),
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SystemRow("All games", null, platforms.sumOf { it.games }, null, selected == null) { onSelect(null) } }
        items(platforms, key = { it.slug }) { p ->
            SystemRow(p.name, p.shortName, p.games, installedCount(p.slug), selected == p.slug) { onSelect(p.slug) }
        }
    }
}

@Composable
private fun SystemRow(name: String, short: String?, count: Int, installed: Int?, selected: Boolean, onClick: () -> Unit) {
    DeckFocusable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        background = if (selected) DeckColors.AccentSoft else Color.Transparent,
        focusedScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) DeckColors.Accent else Color.Transparent))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!short.isNullOrBlank() && short != name) Text(short, color = DeckColors.TextDim, fontSize = 11.sp)
                    if (installed != null && installed > 0) {
                        if (!short.isNullOrBlank() && short != name) Text(" · ", color = DeckColors.TextDim, fontSize = 11.sp)
                        Text("$installed installed", color = DeckColors.Success, fontSize = 11.sp)
                    }
                }
            }
            Text("$count", color = DeckColors.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SystemTabs(platforms: List<Platform>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Chip("All", selected == null) { onSelect(null) } }
        items(platforms, key = { it.slug }) { p -> Chip(p.shortName.ifBlank { p.name }, selected == p.slug) { onSelect(p.slug) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    DeckFocusable(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        background = if (selected) DeckColors.Accent else DeckColors.Surface,
    ) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun GameGrid(
    state: LibraryState,
    downloaded: Set<Long>,
    isInstalled: (BrowseRom) -> Boolean,
    tasks: List<DownloadTask>,
    layout: DeckLayout,
    vm: LibraryViewModel,
    onOpenGame: (String, Long) -> Unit,
    modifier: Modifier,
) {
    val gridState = rememberLazyGridState()
    // Which cover should hold focus: the one last opened (when coming back), else the first one.
    var focusTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    // Only jump to the top when the system/search actually changes (not when returning from a game).
    val listKey = "${state.selected}|${state.query}"
    var lastListKey by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey) {
        if (listKey != lastListKey) {
            lastListKey = listKey
            focusTarget = null
            initialFocusDone = false
            gridState.scrollToItem(0)
        }
    }
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.games.size) { if (nearEnd) vm.loadMore() }

    val connId = state.connection?.id ?: return
    val shortNames = remember(state.platforms) { state.platforms.associate { it.slug to it.shortName.ifBlank { it.name } } }
    val minCell = when {
        layout.compact -> 104.dp
        layout.isPortrait -> 130.dp
        layout.short -> 112.dp
        else -> 150.dp
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (layout.short) 2.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.selectedPlatform?.name ?: "All games",
                fontSize = if (layout.compact || layout.short) 16.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(if (state.total > 0) "${state.total} games" else "", color = DeckColors.TextDim, fontSize = 13.sp)
        }
        if (state.error != null && state.games.isEmpty()) {
            EmptyState("Couldn't load games", state.error, "Retry", vm::reload)
            return
        }
        if (!state.loading && state.games.isEmpty()) {
            EmptyState(if (state.query.isBlank()) "No games here" else "No matches", if (state.query.isBlank()) "" else "Nothing matched \"${state.query}\".", null) {}
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCell),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (layout.short) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.short) 10.dp else 14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.games, key = { _, rom -> rom.id }) { index, rom ->
                val task = tasks.lastOrNull { it.connectionId == connId && it.romId == rom.id }
                val focus = remember { FocusRequester() }
                // Runs when this cover enters composition (first load, or coming back from a game) —
                // not when focusTarget changes on click, which would steal focus mid-navigation.
                LaunchedEffect(Unit) {
                    delay(80) // let the grid lay out first
                    val target = focusTarget
                    if (target == rom.id || (target == null && !initialFocusDone && index == 0)) {
                        runCatching { focus.requestFocus() }
                        initialFocusDone = true
                        focusTarget = null
                    }
                }
                GameCard(
                    modifier = Modifier.focusRequester(focus),
                    systemLabel = shortNames[rom.platformSlug] ?: rom.platformSlug.uppercase(),
                    rom = rom,
                    imageUrl = vm.mediaUrl(rom.boxartUrl, 400),
                    showSystem = state.selected == null,
                    isDownloaded = rom.id in downloaded || isInstalled(rom),
                    task = task,
                    onClick = {
                        focusTarget = rom.id
                        onOpenGame(connId, rom.id)
                    },
                )
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = DeckColors.Accent, strokeWidth = 3.dp)
                    }
                }
            }
            if (state.error != null && state.games.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        DeckButton("Retry loading", { vm.loadMore(force = true) }, icon = Icons.Default.Refresh)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    modifier: Modifier,
    systemLabel: String,
    rom: BrowseRom,
    imageUrl: String?,
    showSystem: Boolean,
    isDownloaded: Boolean,
    task: DownloadTask?,
    onClick: () -> Unit,
) {
    DeckFocusable(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.06f) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(DeckColors.SurfaceHigh)) {
                if (imageUrl != null) {
                    AsyncImage(model = imageUrl, contentDescription = rom.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        rom.title, Modifier.align(Alignment.Center).padding(10.dp),
                        textAlign = TextAlign.Center, color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                }
                if (showSystem) {
                    Text(
                        systemLabel,
                        Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    )
                }
                val active = task?.isActive == true
                if (isDownloaded && !active) {
                    Icon(
                        Icons.Default.CheckCircle, "Installed",
                        Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                        tint = DeckColors.Success,
                    )
                }
                if (active) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(6.dp)) {
                        Text(
                            when (task!!.state) {
                                DownloadState.Queued -> "Queued"
                                DownloadState.Retrying -> "Reconnecting…"
                                DownloadState.Extracting -> "Extracting…"
                                DownloadState.Installing -> "Installing…"
                                else -> task.percent?.let { "$it%" } ?: "Downloading"
                            },
                            fontSize = 11.sp,
                        )
                        LinearProgressIndicator(
                            progress = { (task.percent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            color = DeckColors.Accent,
                            trackColor = DeckColors.SurfaceHigh,
                        )
                    }
                }
            }
            Text(
                rom.title,
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = if (focused) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(body, color = DeckColors.TextDim, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            val fr = remember { FocusRequester() }
            DeckButton(action, onAction, Modifier.focusRequester(fr), primary = true)
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        }
    }
}

@Composable
fun deckFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = DeckColors.Accent,
    unfocusedBorderColor = DeckColors.SurfaceHigh,
    focusedContainerColor = DeckColors.Surface,
    unfocusedContainerColor = DeckColors.Surface,
    cursorColor = DeckColors.Accent,
)
