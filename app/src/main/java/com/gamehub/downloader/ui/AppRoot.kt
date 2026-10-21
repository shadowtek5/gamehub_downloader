package com.gamehub.downloader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gamehub.downloader.data.FolderStorage
import com.gamehub.downloader.ui.screens.ConnectionEditScreen
import com.gamehub.downloader.ui.screens.ControlsScreen
import com.gamehub.downloader.ui.screens.DownloadsScreen
import com.gamehub.downloader.ui.screens.FoldersScreen
import com.gamehub.downloader.ui.screens.GameScreen
import com.gamehub.downloader.ui.screens.LibraryScreen
import com.gamehub.downloader.ui.screens.SettingsScreen
import com.gamehub.downloader.ui.screens.SystemsScreen
import com.gamehub.downloader.ui.screens.Vita3kScreen
import com.gamehub.downloader.ui.screens.SaveSyncScreen
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Layout class for the current window. The AYN Thor's main screen is 1080p landscape, but the
 * app also rotates to portrait and runs on the Thor's small second screen, so screens branch on this.
 */
data class DeckLayout(val widthDp: Int, val heightDp: Int) {
    /** Side rail + grid when there's horizontal room; otherwise tabs on top. */
    val useRail: Boolean get() = widthDp >= 600 && widthDp > heightDp
    val isPortrait: Boolean get() = heightDp > widthDp
    /** Narrow screens (phones in portrait, the Thor's lower screen in some modes). */
    val compact: Boolean get() = widthDp < 480
    /** Short landscape screens like the AYN Thor's main display (~832x468dp). */
    val short: Boolean get() = heightDp < 560
}

@Composable
fun rememberDeckLayout(): DeckLayout {
    val cfg = LocalConfiguration.current
    return remember(cfg.screenWidthDp, cfg.screenHeightDp) { DeckLayout(cfg.screenWidthDp, cfg.screenHeightDp) }
}

/** Launches the system folder picker and persists write access to whatever the user picks. */
@Composable
fun rememberFolderPicker(onPicked: (Uri) -> Unit): (Uri?) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            FolderStorage.persist(context, uri)
            onPicked(uri)
        }
    }
    return { initial -> launcher.launch(initial) }
}

object Routes {
    const val LIBRARY = "library"
    const val GAME = "game/{connId}/{romId}"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val CONNECTION = "connection?id={id}"
    const val FOLDERS = "folders?conn={conn}"
    const val CONTROLS = "controls"
    const val VITA3K = "vita3k"
    const val SAVE_SYNC = "savesync"
    const val BIOS = "bios"
    const val SYSTEMS = "systems?conn={conn}"
    fun systems(connId: String? = null) = if (connId == null) "systems" else "systems?conn=$connId"

    fun game(connId: String, romId: Long) = "game/$connId/$romId"
    fun connection(id: String? = null) = if (id == null) "connection" else "connection?id=$id"
    fun folders(connId: String? = null) = if (connId == null) "folders" else "folders?conn=$connId"
}

@Composable
fun AppRoot(openDownloads: MutableStateFlow<Boolean>, openSaveSync: MutableStateFlow<Boolean>) {
    val nav = rememberNavController()
    val jumpToDownloads by openDownloads.collectAsState()
    LaunchedEffect(jumpToDownloads) {
        if (jumpToDownloads) {
            nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
            openDownloads.value = false
        }
    }
    val jumpToSaveSync by openSaveSync.collectAsState()
    LaunchedEffect(jumpToSaveSync) {
        if (jumpToSaveSync) {
            nav.navigate(Routes.SAVE_SYNC) { launchSingleTop = true }
            openSaveSync.value = false
        }
    }

    Box(Modifier.fillMaxSize().background(DeckColors.Background).safeDrawingPadding()) {
        NavHost(navController = nav, startDestination = Routes.LIBRARY) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenGame = { c, id -> nav.navigate(Routes.game(c, id)) },
                    onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onAddConnection = { nav.navigate(Routes.connection()) },
                    onOpenSystems = { nav.navigate(Routes.systems()) },
                )
            }
            composable(
                Routes.GAME,
                arguments = listOf(
                    navArgument("connId") { type = NavType.StringType },
                    navArgument("romId") { type = NavType.LongType },
                ),
            ) { entry ->
                GameScreen(
                    connectionId = entry.arguments!!.getString("connId")!!,
                    romId = entry.arguments!!.getLong("romId"),
                    onBack = { nav.popBackStack() },
                    onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                    onOpenGame = { id -> nav.navigate(Routes.game(entry.arguments!!.getString("connId")!!, id)) },
                )
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onEditConnection = { nav.navigate(Routes.connection(it)) },
                    onOpenFolders = { nav.navigate(Routes.folders(it)) },
                    onOpenControls = { nav.navigate(Routes.CONTROLS) },
                    onOpenSystems = { nav.navigate(Routes.systems(it)) },
                    onOpenVita3k = { nav.navigate(Routes.VITA3K) },
                    onOpenSaveSync = { nav.navigate(Routes.SAVE_SYNC) },
                    onOpenBios = { nav.navigate(Routes.BIOS) },
                )
            }
            composable(
                Routes.CONNECTION,
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                ConnectionEditScreen(
                    connectionId = entry.arguments?.getString("id"),
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                Routes.SYSTEMS,
                arguments = listOf(navArgument("conn") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                SystemsScreen(initialConnectionId = entry.arguments?.getString("conn"), onBack = { nav.popBackStack() })
            }
            composable(Routes.SAVE_SYNC) {
                SaveSyncScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.BIOS) {
                com.gamehub.downloader.ui.screens.BiosScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.VITA3K) {
                Vita3kScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.CONTROLS) {
                ControlsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                Routes.FOLDERS,
                arguments = listOf(navArgument("conn") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                FoldersScreen(
                    initialConnectionId = entry.arguments?.getString("conn"),
                    onBack = { nav.popBackStack() },
                )
            }
        }

        UpdatePrompt()
        RatingPrompt()

        var toast by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            (context.applicationContext as com.gamehub.downloader.GameHubApp).messages.collect { toast = it }
        }
        LaunchedEffect(Unit) {
            Gamepad.connected.collect { c ->
                val auto = Gamepad.profiles.value[c.key]?.isAuto ?: true
                toast = "${c.name} detected · ${if (auto) "auto-mapped" else "using your saved mapping"}"
            }
        }
        LaunchedEffect(toast) { if (toast != null) { delay(3000); toast = null } }
        toast?.let {
            Text(
                it,
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(20.dp))
                    .background(DeckColors.SurfaceHigh).padding(horizontal = 18.dp, vertical = 10.dp),
                fontSize = 14.sp,
            )
        }
    }
}

/** Offers a newer release from GitHub (checked at start-up, or from Settings › Updates). */
@Composable
private fun UpdatePrompt() {
    val app = LocalContext.current.applicationContext as com.gamehub.downloader.GameHubApp
    val state by app.updater.state.collectAsState()
    var later by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val update = state.available ?: return
    if (later == update.version && state.progress == null) return
    com.gamehub.downloader.ui.components.DeckDialog(
        onDismissRequest = { if (state.progress == null) later = update.version },
        title = { Text("Update to ${update.version}?") },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.androidx_verticalScroll(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Text("You have ${com.gamehub.downloader.BuildConfig.VERSION_NAME}.", color = DeckColors.TextDim)
                if (update.notes.isNotBlank()) Text(update.notes.take(1500), fontSize = 13.sp)
                state.progress?.let { (done, total) ->
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = DeckColors.Accent,
                        trackColor = DeckColors.SurfaceHigh,
                    )
                }
                state.error?.let { Text(it, color = DeckColors.Error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            com.gamehub.downloader.ui.components.DeckButton(
                if (state.progress != null) "Downloading…" else "Update",
                { scope.launch { app.updater.install() } },
                primary = true,
                enabled = state.progress == null,
            )
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                com.gamehub.downloader.ui.components.DeckButton("Skip this version", { scope.launch { app.updater.skip() } }, enabled = state.progress == null)
                com.gamehub.downloader.ui.components.DeckButton("Later", { later = update.version }, enabled = state.progress == null)
            }
        },
    )
}

@Composable
private fun Modifier.androidx_verticalScroll(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

/** "How did it run?" after a game started with Play has run for a few minutes (once per game and emulator). */
@Composable
private fun RatingPrompt() {
    val app = LocalContext.current.applicationContext as com.gamehub.downloader.GameHubApp
    val req by app.compat.request.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val r = req ?: return
    fun answer(rating: String) = scope.launch {
        val err = app.compat.answer(r, rating)
        app.showMessage(err ?: "Thanks — saved to GameHub")
    }
    com.gamehub.downloader.ui.components.DeckDialog(
        onDismissRequest = { app.compat.dismiss() },
        title = { Text("How did ${r.title} run?") },
        text = {
            Text(
                "With ${r.emulatorName} on this device. Your answer shows on the game's page in GameHub and helps pick the right emulator next time.",
                color = DeckColors.TextDim,
            )
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                val first = remember { androidx.compose.ui.focus.FocusRequester() }
                com.gamehub.downloader.ui.components.DeckButton("Playable", { answer("playable") }, Modifier.focusRequester(first), primary = true)
                com.gamehub.downloader.ui.components.DeckButton("Runs with issues", { answer("runs") })
                com.gamehub.downloader.ui.components.DeckButton("Broken", { answer("broken") })
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            }
        },
        dismissButton = { com.gamehub.downloader.ui.components.DeckButton("Not now", { app.compat.dismiss() }) },
    )
}
