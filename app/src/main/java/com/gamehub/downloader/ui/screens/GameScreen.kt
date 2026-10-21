package com.gamehub.downloader.ui.screens

import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.rememberCoroutineScope
import com.gamehub.downloader.GameHubApp
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gamehub.downloader.api.Hltb
import com.gamehub.downloader.api.RelatedGame
import com.gamehub.downloader.api.RomDetail
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.data.InstalledIndex
import com.gamehub.downloader.data.SystemRules
import com.gamehub.downloader.download.DownloadMode
import com.gamehub.downloader.download.DownloadState
import com.gamehub.downloader.download.formatBytes
import com.gamehub.downloader.ui.DeckLayout
import com.gamehub.downloader.ui.SecondScreen
import com.gamehub.downloader.ui.SpotlightGame
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.OnAction
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckDialog
import com.gamehub.downloader.ui.components.DeckFocusable
import com.gamehub.downloader.ui.components.DeckToggle
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.rememberDeckLayout
import com.gamehub.downloader.ui.rememberFolderPicker
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GameScreen(
    connectionId: String,
    romId: Long,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenGame: (Long) -> Unit,
    vm: GameViewModel = viewModel(key = "game-$connectionId-$romId"),
) {
    LaunchedEffect(connectionId, romId) { vm.load(connectionId, romId) }
    val spotRom = vm.state.collectAsState().value.rom
    LaunchedEffect(spotRom?.id) {
        val r = spotRom ?: return@LaunchedEffect
        SecondScreen.spotlight.value = SpotlightGame(
            title = r.title,
            system = r.platformName ?: r.platformSlug,
            boxartUrl = vm.mediaUrl(r.boxartUrl, 600),
            heroUrl = vm.mediaUrl(r.heroUrl ?: r.screenshotUrl, 1200),
            description = r.description,
            details = listOfNotNull(r.releaseDate?.take(4), r.developer, r.genre).joinToString(" · ").ifBlank { null },
        )
    }
    val state by vm.state.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val history by vm.history.collectAsState(initial = emptyList())
    val related by vm.related.collectAsState()
    val installedContents by vm.installed.collectAsState()
    val layout = rememberDeckLayout()
    val context = LocalContext.current

    val task = tasks.lastOrNull { it.connectionId == connectionId && it.romId == romId }
    val record = history.lastOrNull { it.connectionId == connectionId && it.romId == romId }

    // Which mode the folder pickers are for (set right before launching them).
    var pendingMode by remember { mutableStateOf(DownloadMode.File) }
    val pickSystemFolder = rememberFolderPicker { vm.onFolderPicked(it, pendingMode) }
    val pickBaseFolder = rememberFolderPicker { vm.useBaseFolder(pendingMode, it) }
    val pickChangeFolder = rememberFolderPicker { vm.changeFolder(it) }
    val pickVita3kFolder = rememberFolderPicker { vm.setVita3kFolder(it) }
    val vita3kInstalling by vm.vita3kInstalling.collectAsState()
    val vita3kFolderSet by vm.vita3kFolderSet.collectAsState(initial = false)

    OnAction { if (it == GameAction.Downloads) onOpenDownloads() }

    LaunchedEffect(state.message) {
        // Longer messages (e.g. why a Vita install was refused) stay up long enough to read.
        state.message?.let { delay((2500L + it.length * 60L).coerceAtMost(12_000L)); vm.clearMessage() }
    }

    Box(Modifier.fillMaxSize()) {
        val rom = state.rom
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DeckColors.Accent)
            }
            rom == null -> EmptyState("Couldn't open game", state.error ?: "", "Retry") { vm.load(connectionId, romId, force = true) }
            else -> {
                // Hero backdrop fading into the page.
                val heroHeight = if (layout.short) 260.dp else 360.dp
                vm.mediaUrl(rom.heroUrl ?: rom.screenshotUrl, 1200)?.let {
                    AsyncImage(it, null, Modifier.fillMaxWidth().height(heroHeight), contentScale = ContentScale.Crop, alpha = 0.4f)
                }
                Box(Modifier.fillMaxWidth().height(heroHeight).background(Brush.verticalGradient(listOf(Color.Transparent, DeckColors.Background))))

                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(if (layout.short) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconAction(Icons.AutoMirrored.Filled.ArrowBack, onBack)
                    }

                    val details: @Composable (Modifier) -> Unit = { m ->
                        Details(
                            rom = rom,
                            state = state,
                            layout = layout,
                            mediaUrl = vm::mediaUrl,
                            taskState = task?.state,
                            taskPercent = task?.percent,
                            taskBytes = task?.downloadedBytes ?: 0,
                            taskTotal = task?.totalBytes ?: 0,
                            taskError = task?.error,
                            downloadedAt = record?.let { "${it.fileName} • ${it.folderLabel}" },
                            installedFile = installedContents["$connectionId|${rom.platformSlug}"]?.let { c ->
                                rom.filename?.takeIf { it in c.files }
                                    ?: c.files.firstOrNull { f ->
                                        val n = InstalledIndex.normalize(f, isFile = true)
                                        n == InstalledIndex.normalize(rom.title, isFile = false) ||
                                            (rom.filename != null && n == InstalledIndex.normalize(rom.filename, isFile = true))
                                    }
                            },
                            onDownload = { mode -> pendingMode = mode; vm.requestDownload(mode) },
                            onCancel = { task?.let { vm.cancel(it.id) } },
                            onChangeFolder = { pickChangeFolder(null) },
                            onDelete = vm::requestDelete,
                            onCreateLaunchFile = vm::createLaunchFile,
                            vita3kFolderSet = vita3kFolderSet,
                            vita3kProgress = vita3kInstalling.entries.firstOrNull { (name, _) ->
                                InstalledIndex.normalize(name, isFile = true) == InstalledIndex.normalize(rom.filename ?: rom.title, isFile = true)
                            }?.value,
                            onChooseVita3kFolder = { pickVita3kFolder(null) },
                            onInstallVita3k = vm::installInVita3k,
                            onPlayVita3k = vm::playInVita3k,
                            onPlay = vm::play,
                            onChooseLauncher = vm::openLauncherChoice,
                            onToggleFavorite = vm::toggleFavorite,
                            onRate = vm::rate,
                            onSetExtract = vm::setExtract,
                            onTrailer = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            relatedIds = related,
                            onOpenRelated = { name, id -> if (id != null) onOpenGame(id) else vm.notInLibrary(name) },
                            promptOpen = state.prompt != null,
                            modifier = m,
                        )
                    }

                    if (layout.useRail) {
                        Row(
                            Modifier.weight(1f).padding(horizontal = if (layout.short) 24.dp else 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (layout.short) 24.dp else 32.dp),
                        ) {
                            BoxArt(vm.mediaUrl(rom.boxartUrl, 600), rom.title, Modifier.width(if (layout.short) 190.dp else 260.dp))
                            details(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                        }
                    } else {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BoxArt(vm.mediaUrl(rom.boxartUrl, 600), rom.title, Modifier.widthIn(max = if (layout.compact) 140.dp else 200.dp))
                            Spacer(Modifier.height(16.dp))
                            details(Modifier.fillMaxWidth())
                        }
                    }
                    if (!layout.compact) HintBar(listOf(Hint(GameAction.Confirm, "Select"), Hint(GameAction.Back, "Back"), Hint(GameAction.Downloads, "Downloads")))
                }
            }
        }

        state.message?.let { msg ->
            Text(
                msg,
                Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 56.dp).clip(RoundedCornerShape(20.dp))
                    .background(DeckColors.SurfaceHigh).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }

    if (state.choosingLauncher) {
        DeckDialog(
            onDismissRequest = vm::closeLauncherChoice,
            title = { Text("Play ${state.rom?.platformName ?: ""} games with") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.launchers.forEach { l ->
                        val fr = remember { FocusRequester() }
                        val current = l.id == state.launcher?.id
                        DeckFocusable(onClick = { vm.chooseLauncher(l) }, modifier = Modifier.fillMaxWidth().focusRequester(fr)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SportsEsports, null, tint = if (current) DeckColors.Accent else DeckColors.TextDim)
                                Spacer(Modifier.width(12.dp))
                                Text(l.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                state.compat?.summary?.devices
                                    ?.firstOrNull { it.emulator == com.gamehub.downloader.data.Compat.emulatorKey(l) }
                                    ?.let { g -> RatingChip(g.consensus); Spacer(Modifier.width(10.dp)) }
                                if (current) Icon(Icons.Default.CheckCircle, null, tint = DeckColors.Accent)
                            }
                        }
                        if (current) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                    }
                    Text(
                        "RetroArch cores have to be downloaded in RetroArch first (Main Menu › Load Core › Download a Core).",
                        color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { DeckButton("Close", vm::closeLauncherChoice) },
        )
    }

    when (val p = state.prompt) {
        is GamePrompt.ChooseFolder -> FolderPromptDialog(
            systemName = state.rom?.platformName ?: state.rom?.platformSlug ?: "",
            baseLabel = p.baseFolderLabel,
            extract = p.extract,
            showExtract = state.rom?.platformSlug?.let { SystemRules.canExtract(it) } ?: true,
            onToggleExtract = vm::togglePromptExtract,
            onUseBase = { vm.useBaseFolder(p.mode) },
            onPickSystem = { pendingMode = p.mode; pickSystemFolder(null) },
            onPickBase = { pendingMode = p.mode; pickBaseFolder(null) },
            onDismiss = vm::dismissPrompt,
        )
        is GamePrompt.ConfirmDelete -> DeckDialog(
            onDismissRequest = vm::dismissPrompt,
            icon = { Icon(Icons.Default.Delete, null, tint = DeckColors.Error) },
            title = { Text("Delete ${state.rom?.title ?: "game"}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("These will be permanently deleted from ${p.folderLabel}:", color = DeckColors.TextDim)
                    p.entries.forEach { e ->
                        DeckToggle(
                            label = e.name,
                            description = if (e.isDir) "Folder and everything in it" else formatBytes(e.size),
                            checked = e.name in p.selected,
                            onCheckedChange = { vm.toggleDeleteEntry(e.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                DeckButton(
                    "Delete ${p.selected.size} item${if (p.selected.size == 1) "" else "s"}",
                    vm::confirmDelete,
                    icon = Icons.Default.Delete,
                    enabled = p.selected.isNotEmpty(),
                )
            },
            // Cancel is focused first so a stray A press doesn't delete anything.
            dismissButton = { FocusedButton("Cancel", primary = true, onClick = vm::dismissPrompt) },
        )
        is GamePrompt.ConfirmReplace -> DeckDialog(
            onDismissRequest = vm::dismissPrompt,
            icon = { Icon(Icons.Default.Warning, null, tint = DeckColors.Warning) },
            title = { Text("Replace existing file?") },
            text = { Text("${p.fileName} already exists in ${p.folderLabel}. Download it again and replace it?") },
            confirmButton = { FocusedButton("Replace") { vm.confirmReplace(p.mode) } },
            dismissButton = { DeckButton("Cancel", vm::dismissPrompt) },
        )
        null -> {}
    }
}

@Composable
private fun BoxArt(url: String?, title: String, modifier: Modifier) {
    Box(modifier.aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)).background(DeckColors.SurfaceHigh)) {
        if (url != null) AsyncImage(url, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Album, null, Modifier.align(Alignment.Center).size(64.dp), tint = DeckColors.TextDim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(
    rom: RomDetail,
    state: GameState,
    layout: DeckLayout,
    mediaUrl: (String?, Int?) -> String?,
    taskState: DownloadState?,
    taskPercent: Int?,
    taskBytes: Long,
    taskTotal: Long,
    taskError: String?,
    downloadedAt: String?,
    installedFile: String?,
    onDownload: (DownloadMode) -> Unit,
    onCancel: () -> Unit,
    onChangeFolder: () -> Unit,
    onDelete: () -> Unit,
    onCreateLaunchFile: () -> Unit,
    vita3kFolderSet: Boolean,
    vita3kProgress: Pair<Long, Long>?,
    onChooseVita3kFolder: () -> Unit,
    onInstallVita3k: () -> Unit,
    onPlayVita3k: () -> Unit,
    onPlay: () -> Unit,
    onChooseLauncher: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRate: (String) -> Unit,
    onSetExtract: (Boolean) -> Unit,
    onTrailer: (String) -> Unit,
    relatedIds: Map<String, Long?>,
    onOpenRelated: (String, Long?) -> Unit,
    promptOpen: Boolean,
    modifier: Modifier,
) {
    val active = taskState == DownloadState.Queued || taskState == DownloadState.Running ||
        taskState == DownloadState.Retrying || taskState == DownloadState.Extracting || taskState == DownloadState.Installing
    val downloadFocus = remember { FocusRequester() }
    // Installed and an emulator is set up: Play comes first and takes the initial focus.
    val canPlay = installedFile != null && state.launcher != null && !SystemRules.supportsVita3k(rom.platformSlug)
    LaunchedEffect(promptOpen, canPlay) { if (!promptOpen) runCatching { downloadFocus.requestFocus() } }
    val trailer = rom.trailerUrl?.takeIf { it.startsWith("http") }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- Title (logo art when available) ----
        val logo = mediaUrl(rom.logoUrl, 800)
        if (logo != null) {
            AsyncImage(
                logo, rom.title,
                Modifier.heightIn(max = if (layout.short) 72.dp else 110.dp).widthIn(max = 420.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
            Text(rom.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DeckColors.TextDim)
        } else {
            Text(rom.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
        }

        rom.playtimeSeconds?.takeIf { it >= 60 }?.let { secs ->
            Text("Played ${formatPlaytime(secs)}" + (rom.lastPlayedAt?.take(10)?.let { " · last $it" } ?: ""), color = DeckColors.TextDim, fontSize = 14.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(rom.platformName ?: rom.platformSlug, accent = true)
            rom.releaseDate?.take(4)?.let { Tag(it) }
            rom.players?.let { Tag("$it players".replace("1 players", "1 player")) }
            rom.region?.let { Tag(it) }
            rom.language?.let { Tag(it) }
            rom.sizeBytes?.let { Tag(formatBytes(it)) }
            rom.ageRating?.let { Tag(it) }
            rom.variant?.let { Tag(it) }
            rom.revision?.let { Tag("Rev $it") }
            rom.discNumber?.let { Tag("Disc $it") }
            if (rom.datStatus.equals("verified", ignoreCase = true)) Tag("✓ Verified dump", good = true)
        }

        // ---- Download actions ----
        if (active) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp)) {
                Text(
                    when (taskState) {
                        DownloadState.Queued -> "Waiting in queue…"
                        DownloadState.Retrying -> taskError ?: "Reconnecting…"
                        DownloadState.Extracting -> "Extracting archive…" + (taskPercent?.let { " $it%" } ?: "")
                        DownloadState.Installing -> "Installing in Vita3K…" + (taskPercent?.let { " $it%" } ?: "")
                        else -> buildString {
                            append("Downloading ")
                            taskPercent?.let { append("$it% ") }
                            append("• ${formatBytes(taskBytes)}")
                            if (taskTotal > 0) append(" / ${formatBytes(taskTotal)}")
                        }
                    },
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (taskPercent != null) {
                    LinearProgressIndicator(progress = { taskPercent / 100f }, Modifier.fillMaxWidth(), color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh)
                }
                Spacer(Modifier.height(10.dp))
                DeckButton("Cancel", onCancel, Modifier.focusRequester(downloadFocus), icon = Icons.Default.Close)
            }
        } else {
            val firstFocus = if (canPlay) Modifier else Modifier.focusRequester(downloadFocus)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canPlay) DeckButton("Play", onPlay, Modifier.focusRequester(downloadFocus), icon = Icons.Default.PlayArrow, primary = true)
                if (rom.isMultiDisc) {
                    DeckButton("Download all discs (.zip)", { onDownload(DownloadMode.DiscsZip) }, firstFocus, icon = Icons.Default.Download, primary = !canPlay)
                    DeckButton("This disc only", { onDownload(DownloadMode.File) }, icon = Icons.Default.Album)
                } else {
                    val have = downloadedAt != null || installedFile != null
                    DeckButton(
                        if (have) "Download again" else "Download",
                        { onDownload(DownloadMode.File) },
                        firstFocus,
                        icon = Icons.Default.Download,
                        primary = !have,
                    )
                }
                DeckButton(if (state.favorite) "Favorited" else "Favorite", onToggleFavorite, icon = if (state.favorite) Icons.Default.Star else Icons.Default.StarBorder)
                if (trailer != null) DeckButton("Watch trailer", { onTrailer(trailer) }, icon = Icons.Default.PlayCircle)
                if (state.vita3kInstalled) DeckButton("Play in Vita3K", onPlayVita3k, icon = Icons.Default.PlayArrow, primary = true)
                if (installedFile != null || state.vita3kInstalled) DeckButton("Delete", onDelete, icon = Icons.Default.Delete)
            }
        }
        if (taskState == DownloadState.Failed && taskError != null) {
            Notice(Icons.Default.Warning, DeckColors.Error, "Last attempt failed: $taskError")
        }
        if (!active) {
            if (installedFile != null && !SystemRules.supportsVita3k(rom.platformSlug)) {
                LauncherRow(state.launcher?.label, state.launchers.size, onChooseLauncher)
            }
            state.compat?.let { c -> CompatCard(c, state.launcher?.label, installedFile != null || state.vita3kInstalled, onRate) }
            when {
                installedFile != null -> Notice(Icons.Default.CheckCircle, DeckColors.Success, "Installed: $installedFile")
                downloadedAt != null -> Notice(Icons.Default.Info, DeckColors.TextDim, "Downloaded earlier: $downloadedAt (no longer found in the folder)")
            }
        }
        if (rom.isMultiDisc) {
            Notice(Icons.Default.Info, DeckColors.TextDim, "Multi-disc game. The .zip bundles every disc but can't resume if interrupted.")
        }
        if (rom.isCueSheet) {
            Notice(Icons.Default.Warning, DeckColors.Warning, "This is a .cue sheet. GameHub only serves the .cue file itself, so its .bin tracks won't be included.")
        }

        // ---- Destination folder + per-system extract setting ----
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state.folderOk) Icons.Default.Folder else Icons.Default.FolderOpen, null, tint = if (state.folderOk) DeckColors.Accent else DeckColors.TextDim)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Save to", color = DeckColors.TextDim, fontSize = 12.sp)
                    Text(
                        when {
                            state.folder == null -> "Not set — you'll be asked on first download"
                            !state.folderOk -> "${FolderStorage.describe(state.folder)} (access lost — choose again)"
                            else -> FolderStorage.describe(state.folder)
                        },
                        fontSize = 14.sp,
                    )
                }
                DeckButton("Change", onChangeFolder)
            }
            state.folder?.takeIf { SystemRules.canExtract(rom.platformSlug) }?.let { folder ->
                DeckToggle(
                    label = "Extract .zip / .7z after download",
                    description = "For ${rom.platformName ?: rom.platformSlug}: unpack into the folder, then delete the archive",
                    checked = folder.extract,
                    onCheckedChange = onSetExtract,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- Vita3K (PS Vita): installed status, install from the downloaded zip ----
        if (SystemRules.supportsVita3k(rom.platformSlug)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SportsEsports, null, tint = if (state.vita3kInstalled) DeckColors.Success else DeckColors.TextDim)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Vita3K", color = DeckColors.TextDim, fontSize = 12.sp)
                        Text(
                            when {
                                !vita3kFolderSet -> "Vita3K folder not set — needed to install and list games"
                                vita3kProgress != null -> "Installing… " + (vita3kProgress.second.takeIf { it > 0 }
                                    ?.let { "${vita3kProgress.first * 100 / it}%" } ?: "")
                                state.vita3kInstalled -> "Installed (${state.vitaTitleId})"
                                installedFile != null -> "Not installed yet" + (state.vitaTitleId?.let { " • $it" } ?: "")
                                else -> "Not installed • download first"
                            },
                            fontSize = 14.sp,
                        )
                    }
                    if (!vita3kFolderSet) DeckButton("Choose Vita3K folder", onChooseVita3kFolder)
                }
                vita3kProgress?.let { (done, total) ->
                    if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth(), color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh)
                    else LinearProgressIndicator(Modifier.fillMaxWidth(), color = DeckColors.Accent, trackColor = DeckColors.SurfaceHigh)
                }
                if (vita3kFolderSet && vita3kProgress == null && installedFile != null && installedFile.endsWith(".zip", ignoreCase = true)) {
                    DeckButton(
                        if (state.vita3kInstalled) "Reinstall in Vita3K" else "Install in Vita3K",
                        onInstallVita3k,
                        icon = Icons.Default.InstallMobile,
                        primary = !state.vita3kInstalled,
                    )
                }
            }
        }

        // ---- Launch file for front-ends (PS Vita → .dpt for Cocoon / Daijishou) ----
        if (SystemRules.supportsLaunchFiles(rom.platformSlug)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RocketLaunch, null, tint = if (state.launchFile != null) DeckColors.Accent else DeckColors.TextDim)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Launch file (.dpt) for Cocoon", color = DeckColors.TextDim, fontSize = 12.sp)
                        Text(
                            when {
                                state.launchFile != null -> state.launchFile
                                state.folder == null -> "Created in the game folder after download"
                                else -> "Not created yet"
                            },
                            fontSize = 14.sp,
                        )
                    }
                }
                if (state.folder == null) {
                    Text("Set the game folder first (download once, or Settings › Folders).", color = DeckColors.TextDim, fontSize = 12.sp)
                } else if (installedFile != null || state.vita3kInstalled) {
                    DeckButton(
                        if (state.launchFile != null) "Recreate launch file" else "Create launch file",
                        onCreateLaunchFile,
                        icon = Icons.Default.RocketLaunch,
                        primary = state.launchFile == null,
                    )
                }
            }
        }

        // ---- Saves (Settings › Save sync) ----
        if (installedFile != null || state.vita3kInstalled) GameSavesCard(rom.id)

        // ---- About ----
        rom.description?.takeIf { it.isNotBlank() }?.let { desc ->
            var expanded by remember { mutableStateOf(false) }
            Panel("About", onClick = { expanded = !expanded }) {
                Text(
                    desc,
                    color = DeckColors.Text, fontSize = 14.sp, lineHeight = 21.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                )
                if (desc.length > 300) {
                    Text(if (expanded) "Show less" else "Show more", color = DeckColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---- Screenshot ----
        mediaUrl(rom.screenshotUrl, 1200)?.let { shot ->
            Panel("Screenshot") {
                AsyncImage(
                    shot, "Screenshot",
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            }
        }

        // ---- Details ----
        val facts = buildList {
            rom.developer?.let { add("Developer" to it) }
            rom.publisher?.let { add("Publisher" to it) }
            rom.releaseDate?.let { add("Released" to formatDate(it)) }
            rom.genre?.let { add("Genre" to it) }
            rom.gameModes?.let { add("Modes" to it) }
            rom.players?.let { add("Players" to it) }
            rom.perspectives?.let { add("Perspective" to it) }
            rom.themes?.let { add("Themes" to it) }
            rom.franchise?.let { add("Series" to it) }
            rom.rating?.let { add("Rating" to it) }
            rom.ageRating?.let { add("Age rating" to it) }
            rom.raAchievements?.takeIf { it > 0 }?.let { add("Achievements" to "$it (RetroAchievements)") }
        }.filter { it.second.isNotBlank() }
        if (facts.isNotEmpty()) {
            Panel("Details") {
                val twoColumns = layout.widthDp >= 760
                if (twoColumns) {
                    val half = (facts.size + 1) / 2
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { facts.take(half).forEach { InfoLine(it.first, it.second) } }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { facts.drop(half).forEach { InfoLine(it.first, it.second) } }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { facts.forEach { InfoLine(it.first, it.second) } }
                }
            }
        }

        // ---- How long to beat ----
        rom.howLongToBeat?.let { h -> HltbPanel(h) }

        // ---- Related games: open directly when they're in the library ----
        rom.related?.let { rel ->
            if (rel.editions.isNotEmpty()) RelatedRow("Other editions", rel.editions, relatedIds, onOpenRelated)
            if (rel.similar.isNotEmpty()) RelatedRow("Similar games", rel.similar, relatedIds, onOpenRelated)
        }

        // ---- File ----
        Panel("File") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rom.filename?.let { InfoLine("Name", it) }
                rom.sizeBytes?.let { InfoLine("Size", formatBytes(it)) }
                rom.datStatus?.let { InfoLine("DAT status", it.replaceFirstChar(Char::uppercase)) }
                rom.crc32?.let { InfoLine("CRC32", it, mono = true) }
                rom.md5?.let { InfoLine("MD5", it, mono = true) }
                rom.sha1?.let { InfoLine("SHA-1", it, mono = true) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A titled content card that can take controller focus, so the D-pad can scroll down through
 * text-only sections. [onClick] is optional (e.g. expand/collapse).
 */
/** Sync just this game's saves (whichever emulators in Settings › Save sync have them). */
@Composable
private fun GameSavesCard(romId: Long) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val status by app.saveSync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    val configured = settings?.let { app.saveSync.anySourceConfigured(it) } == true
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Sync, null, tint = if (configured) DeckColors.Accent else DeckColors.TextDim)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Saves", color = DeckColors.TextDim, fontSize = 12.sp)
                Text(
                    when {
                        !configured -> "Set up save sync in Settings › Save sync"
                        status.running -> status.progress
                        result != null -> result!!
                        else -> "Sync this game's saves with GameHub"
                    },
                    fontSize = 14.sp,
                )
            }
        }
        if (configured) DeckButton("Sync saves", {
            scope.launch {
                val r = app.saveSync.syncAll(onlyRomId = romId)
                result = if (r.checked == 0) "No saves for this game yet (play it once, or sync after GameHub links it)" else r.summary()
            }
        }, icon = Icons.Default.Sync, enabled = !status.running)
    }
}

@Composable
private fun Panel(title: String, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    DeckFocusable(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.0f) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun HltbPanel(h: Hltb) {
    Panel("How long to beat") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Main story" to h.main, "Main + extras" to h.plus, "Completionist" to h.completionist)
                .filter { it.second != null && it.second!! > 0 }
                .forEach { (label, secs) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DeckColors.SurfaceHigh).padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(formatDuration(secs!!), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DeckColors.Accent)
                        Text(label, fontSize = 12.sp, color = DeckColors.TextDim, textAlign = TextAlign.Center)
                    }
                }
        }
    }
}

@Composable
private fun RelatedRow(title: String, games: List<RelatedGame>, ids: Map<String, Long?>, onOpen: (String, Long?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(games, key = { it.name + (it.kind ?: "") }) { g ->
                val checked = ids.containsKey(g.name)
                val id = ids[g.name]
                val owned = id != null
                DeckFocusable(
                    onClick = { onOpen(g.name, id) },
                    modifier = Modifier.width(110.dp).alpha(if (checked && !owned) 0.45f else 1f),
                    focusedScale = 1.06f,
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(DeckColors.SurfaceHigh)) {
                            if (g.cover != null) AsyncImage(g.cover, g.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (owned) {
                                Text(
                                    "In library",
                                    Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp))
                                        .background(DeckColors.Success).padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DeckColors.Background,
                                )
                            }
                            g.kind?.let {
                                Text(
                                    it, Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(g.name, Modifier.padding(6.dp), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                    }
                }
            }
        }
        Text("Games in your library open directly; dimmed ones aren't in it.", fontSize = 11.sp, color = DeckColors.TextDim)
    }
}

@Composable
private fun Tag(text: String, accent: Boolean = false, good: Boolean = false) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    accent -> DeckColors.AccentSoft
                    good -> DeckColors.Success.copy(alpha = 0.18f)
                    else -> DeckColors.SurfaceHigh
                }
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 12.sp,
        color = when {
            accent -> DeckColors.Accent
            good -> DeckColors.Success
            else -> DeckColors.Text
        },
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Notice(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = DeckColors.TextDim)
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, Modifier.width(110.dp), color = DeckColors.TextDim, fontSize = 13.sp)
        Text(value, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

private fun formatDate(raw: String): String = runCatching {
    LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
}.getOrDefault(raw)

private fun formatDuration(secs: Long): String {
    if (secs < 3600) return "${(secs / 60).coerceAtLeast(1)} min"
    val halfHours = Math.round(secs / 1800.0)
    val whole = halfHours / 2
    return if (halfHours % 2 == 0L) "$whole h" else "$whole½ h"
}

@Composable
fun FocusedButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    DeckButton(text, onClick, Modifier.focusRequester(fr), primary = primary)
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun FolderPromptDialog(
    systemName: String,
    baseLabel: String?,
    extract: Boolean,
    showExtract: Boolean,
    onToggleExtract: () -> Unit,
    onUseBase: () -> Unit,
    onPickSystem: () -> Unit,
    onPickBase: () -> Unit,
    onDismiss: () -> Unit,
) {
    DeckDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CreateNewFolder, null, tint = DeckColors.Accent) },
        title = { Text("Where should $systemName games go?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose a download folder for $systemName. You can change it any time in Settings › Folders.",
                    color = DeckColors.TextDim,
                )
                val fr = remember { FocusRequester() }
                if (baseLabel != null) {
                    DeckButton("Use $baseLabel", onUseBase, Modifier.fillMaxWidth().focusRequester(fr), icon = Icons.Default.Folder, primary = true)
                    DeckButton("Choose a different folder…", onPickSystem, Modifier.fillMaxWidth(), icon = Icons.Default.FolderOpen)
                } else {
                    DeckButton("Choose folder for $systemName…", onPickSystem, Modifier.fillMaxWidth().focusRequester(fr), icon = Icons.Default.FolderOpen, primary = true)
                    DeckButton("Choose a ROMs base folder (one sub-folder per system)…", onPickBase, Modifier.fillMaxWidth(), icon = Icons.Default.CreateNewFolder)
                }
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                if (showExtract) DeckToggle(
                    label = "Extract .zip / .7z after download",
                    description = "Saved for $systemName along with the folder",
                    checked = extract,
                    onCheckedChange = { onToggleExtract() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
        dismissButton = { DeckButton("Cancel", onDismiss) },
    )
}

@Composable
private fun LauncherRow(label: String?, count: Int, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.SportsEsports, null, tint = DeckColors.Accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Plays with", color = DeckColors.TextDim, fontSize = 12.sp)
            Text(label ?: "No emulator for this system is installed", fontWeight = FontWeight.SemiBold)
        }
        if (count > 1) DeckButton("Change", onChange)
    }
}

private fun formatPlaytime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "$m min"
    }
}

private fun ratingLabel(r: String) = when (r) {
    "playable" -> "Playable"
    "runs" -> "Runs with issues"
    "broken" -> "Broken"
    else -> r
}

private fun ratingColor(r: String) = when (r) {
    "playable" -> DeckColors.Success
    "runs" -> DeckColors.Warning
    else -> DeckColors.Error
}

@Composable
fun RatingChip(rating: String, text: String = ratingLabel(rating)) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(ratingColor(rating).copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = ratingColor(rating), fontSize = 12.sp, fontWeight = FontWeight.Bold,
    )
}

/** How the game runs: official lists, reports from handhelds, and your own rating for the current emulator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompatCard(c: com.gamehub.downloader.api.CompatInfo, emulator: String?, canRate: Boolean, onRate: (String) -> Unit) {
    val rateHere = canRate && emulator != null && c.supportsDeviceReports
    if (c.external.isNullOrEmpty() && c.summary.devices.isEmpty() && !rateHere) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Compatibility", color = DeckColors.TextDim, fontSize = 12.sp)
        c.external.orEmpty().forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.emulator, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                RatingChip(e.rating, e.status)
            }
        }
        c.summary.devices.take(4).forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.emulatorName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        g.reports.take(2).joinToString(" · ") { r -> if (r.device.isNotEmpty()) "${r.authorName} on ${r.device}" else r.authorName },
                        color = DeckColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                RatingChip(g.consensus)
            }
        }
        if (rateHere) {
            Text(
                if (c.mine != null) "Your rating with $emulator on this device" else "How does it run with $emulator on this device?",
                color = DeckColors.TextDim, fontSize = 12.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("playable", "runs", "broken").forEach { r ->
                    DeckButton(ratingLabel(r), { onRate(r) }, primary = c.mine?.rating == r)
                }
            }
        }
    }
}
