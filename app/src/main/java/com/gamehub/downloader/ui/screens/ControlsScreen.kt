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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamehub.downloader.ui.ButtonLayout
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.Gamepad
import com.gamehub.downloader.ui.components.ButtonGlyph
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckDialog
import com.gamehub.downloader.ui.components.DeckFocusable
import com.gamehub.downloader.ui.components.Hint
import com.gamehub.downloader.ui.components.HintBar
import com.gamehub.downloader.ui.components.SectionTitle
import com.gamehub.downloader.ui.theme.DeckColors
import kotlinx.coroutines.delay

private const val CAPTURE_SECONDS = 8

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(onBack: () -> Unit) {
    val profiles by Gamepad.profiles.collectAsState()
    val active by Gamepad.active.collectAsState()
    val lastPress by Gamepad.lastPress.collectAsState()
    val capturing by Gamepad.capturing.collectAsState()

    val profile = Gamepad.profileFor(active, profiles)
    val rowFocus = remember { GameAction.entries.associateWith { FocusRequester() } }
    var lastRemapped by remember { mutableStateOf<GameAction?>(null) }
    LaunchedEffect(capturing) {
        val c = capturing
        if (c != null) lastRemapped = c
        else lastRemapped?.let { runCatching { rowFocus.getValue(it).requestFocus() } }
    }
    val bindings = profile.bindings()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Controller mapping", onBack) {
            DeckButton("Reset to auto", { Gamepad.resetToAuto() }, icon = Icons.Default.RestartAlt, enabled = !profile.isAuto)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- Detected controller ----
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SportsEsports, null, tint = DeckColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(active?.name ?: "No controller detected yet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                when {
                                    active == null -> "Press any button on your controller to detect it."
                                    profile.isAuto -> "Auto-mapped. Mappings are saved per controller."
                                    else -> "Custom mapping saved for this controller."
                                },
                                color = DeckColors.TextDim, fontSize = 12.sp,
                            )
                        }
                    }
                    Text("Layout", color = DeckColors.TextDim, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ButtonLayout.entries.forEach { l ->
                            DeckFocusable(
                                onClick = { Gamepad.setLayout(l) },
                                shape = RoundedCornerShape(50),
                                background = if (profile.layout == l) DeckColors.Accent else DeckColors.SurfaceHigh,
                                focusedBackground = if (profile.layout == l) DeckColors.Accent else DeckColors.SurfaceFocus,
                            ) { Text(l.label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                        }
                    }
                }
            }

            // ---- Button tester ----
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Button test", color = DeckColors.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val p = lastPress
                    if (p == null) {
                        Text("Press any button", color = DeckColors.TextDim, fontSize = 13.sp)
                    } else {
                        ButtonGlyph(Gamepad.buttonLabel(p.keyCode), color = DeckColors.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("→ ${p.action?.label ?: "not mapped"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item { SectionTitle("Actions", Modifier.padding(top = 8.dp)) }
            items(GameAction.entries, key = { it.name }) { action ->
                val key = bindings[action]
                DeckFocusable(
                    onClick = { Gamepad.startCapture(action) },
                    modifier = Modifier.fillMaxWidth().focusRequester(rowFocus.getValue(action)),
                    focusedScale = 1.01f,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(action.label, fontWeight = FontWeight.SemiBold)
                            Text(action.description, color = DeckColors.TextDim, fontSize = 12.sp)
                        }
                        val customised = action in profile.custom
                        ButtonGlyph(Gamepad.buttonLabel(key), color = if (customised) DeckColors.Accent else DeckColors.TextDim, size = 28.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Remap", color = DeckColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                Text(
                    "Select an action, then press the button you want for it. If that button was already used, the two actions swap. " +
                        "The D-pad always moves the highlight.",
                    color = DeckColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
            }
        }
        HintBar(listOf(Hint(GameAction.Confirm, "Remap"), Hint(GameAction.Back, "Back")))
    }

    capturing?.let { action -> CaptureDialog(action) }
}

@Composable
private fun CaptureDialog(action: GameAction) {
    var remaining by remember(action) { mutableFloatStateOf(1f) }
    LaunchedEffect(action) {
        val steps = CAPTURE_SECONDS * 10
        for (i in steps downTo 0) {
            remaining = i / steps.toFloat()
            delay(100)
        }
        Gamepad.cancelCapture()
    }
    DeckDialog(
        onDismissRequest = { Gamepad.cancelCapture() },
        containerColor = DeckColors.Surface,
        title = { Text("Press a button for “${action.label}”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Press the controller button to use for ${action.description.lowercase()}. " +
                        "Waiting ${CAPTURE_SECONDS}s, or tap Cancel.",
                    color = DeckColors.TextDim,
                )
                LinearProgressIndicator(
                    progress = { remaining },
                    modifier = Modifier.fillMaxWidth(),
                    color = DeckColors.Accent,
                    trackColor = DeckColors.SurfaceHigh,
                )
            }
        },
        confirmButton = {},
        dismissButton = { DeckButton("Cancel", { Gamepad.cancelCapture() }) },
    )
}
