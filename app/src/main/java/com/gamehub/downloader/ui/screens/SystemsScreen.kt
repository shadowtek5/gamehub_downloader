package com.gamehub.downloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.Platform
import com.gamehub.downloader.data.SystemVisibility
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckToggle
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Choose which systems appear in the library for a connection. Everything is shown by default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemsScreen(initialConnectionId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val settings by app.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val connection = settings?.connections?.let { list -> list.firstOrNull { it.id == initialConnectionId } ?: settings?.activeConnection }

    var platforms by remember { mutableStateOf<List<Platform>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connection?.id) {
        val c = connection ?: return@LaunchedEffect
        loading = true
        runCatching { app.api(c).platforms() }
            .onSuccess { platforms = it; error = null }
            .onFailure { error = it.message ?: "Couldn't load systems" }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Show / hide systems", onBack)
        if (connection == null) {
            EmptyState("No connections", "Add a GameHub connection first.", "Back", onBack)
            return@Column
        }
        val hidden = connection.hiddenSystems
        val shownCount = platforms.count { it.slug !in hidden }
        val byVendor = platforms.groupBy { it.vendor.ifBlank { "Other" } }
        val fmt = remember { NumberFormat.getIntegerInstance() }

        fun setVisible(slugs: Collection<String>, visible: Boolean) =
            scope.launch { app.settings.setSystemsVisible(connection.id, slugs, visible) }

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
                        if (loading) "Loading systems…" else "Showing $shownCount of ${platforms.size} systems on ${connection.name}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Hidden systems don't appear in the library, L1/R1 cycling, \"All games\" or search.",
                        color = DeckColors.TextDim, fontSize = 13.sp,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusedButtonWithIcon("Nintendo, Sega & Sony only", Icons.Default.FilterAlt) {
                            val keep = platforms.filter { SystemVisibility.inPreset(it.vendor) }.map { it.slug }.toSet()
                            scope.launch { app.settings.setHiddenSystems(connection.id, platforms.map { it.slug }.toSet() - keep) }
                        }
                        DeckButton("Show all", { scope.launch { app.settings.setHiddenSystems(connection.id, emptySet()) } }, icon = Icons.Default.Visibility)
                        DeckButton("Hide all", { setVisible(platforms.map { it.slug }, false) }, icon = Icons.Default.VisibilityOff)
                    }
                }
            }

            when {
                loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DeckColors.Accent) }
                }
                error != null -> item { Text(error ?: "", color = DeckColors.Error) }
                else -> byVendor.forEach { (vendor, systems) ->
                    val vendorShown = systems.count { it.slug !in hidden }
                    item(key = "vendor-$vendor") {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(vendor, Modifier.weight(1f))
                            Text("$vendorShown/${systems.size} shown", color = DeckColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                            if (vendorShown < systems.size) DeckButton("Show all", { setVisible(systems.map { it.slug }, true) })
                            else DeckButton("Hide all", { setVisible(systems.map { it.slug }, false) })
                        }
                    }
                    items(systems, key = { it.slug }) { p ->
                        DeckToggle(
                            label = p.name,
                            description = listOf(p.shortName.takeIf { it.isNotBlank() && it != p.name }, "${fmt.format(p.games)} games")
                                .filterNotNull().joinToString(" · "),
                            checked = p.slug !in hidden,
                            onCheckedChange = { on -> setVisible(listOf(p.slug), on) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        HintBar(listOf(Hint(GameAction.Confirm, "Toggle"), Hint(GameAction.Back, "Back")))
    }
}

/** A button that takes focus when the screen opens (so the controller starts on the preset). */
@Composable
private fun FocusedButtonWithIcon(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    DeckButton(text, onClick, Modifier.focusRequester(fr), icon = icon, primary = true)
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}
