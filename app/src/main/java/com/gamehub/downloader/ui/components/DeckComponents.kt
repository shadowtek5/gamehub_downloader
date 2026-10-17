package com.gamehub.downloader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.boundButtonLabel
import com.gamehub.downloader.ui.theme.DeckColors

/**
 * A clickable surface that is reachable with the D-pad and shows a clear accent ring when
 * focused — the core building block for controller-first navigation.
 */
@Composable
fun DeckFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    background: Color = DeckColors.Surface,
    focusedBackground: Color = DeckColors.SurfaceFocus,
    focusedScale: Float = 1.04f,
    enabled: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    val click by rememberUpdatedState(onClick)
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(if (focused) focusedBackground else background)
            .border(if (focused) 3.dp else 0.dp, if (focused) DeckColors.Accent else Color.Transparent, shape)
            // Always focusable (unlike clickable, which isn't in touch mode), so screens can place
            // the highlight programmatically and the controller never starts with nothing selected.
            .onKeyEvent { e ->
                val isSelect = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (enabled && isSelect) {
                    if (e.type == KeyEventType.KeyUp) click()
                    true
                } else false
            }
            .focusable(enabled = enabled, interactionSource = interaction)
            .pointerInput(enabled) { detectTapGestures { if (enabled) click() } }
    ) {
        content(focused)
    }
}

/** A Deck-style button: icon + label, accent ring when focused. */
@Composable
fun DeckButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    DeckFocusable(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        background = if (primary) DeckColors.Accent.copy(alpha = 0.85f) else DeckColors.SurfaceHigh,
        focusedBackground = if (primary) DeckColors.Accent else DeckColors.SurfaceFocus,
        focusedScale = 1.03f,
        enabled = enabled,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) DeckColors.Text else DeckColors.TextDim)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = if (enabled) DeckColors.Text else DeckColors.TextDim,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
}

/** Controller glyph like the Deck's on-screen button hints: a circle, or a pill for longer names. */
@Composable
fun ButtonGlyph(label: String, color: Color = DeckColors.TextDim, size: Dp = 22.dp) {
    Box(
        Modifier.height(size).widthIn(min = size).clip(RoundedCornerShape(50)).background(color)
            .padding(horizontal = if (label.length > 2) 7.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = DeckColors.Background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** One hint: the button currently bound to [action], then [label] (may be empty). */
data class Hint(val action: GameAction, val label: String)

/** Bottom bar of controller hints that follows the user's button mapping. */
@Composable
fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(DeckColors.Background).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { h ->
            ButtonGlyph(boundButtonLabel(h.action))
            Spacer(Modifier.width(6.dp))
            if (h.label.isNotEmpty()) {
                Text(h.label, color = DeckColors.TextDim, fontSize = 13.sp)
                Spacer(Modifier.width(18.dp))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = DeckColors.TextDim,
        style = MaterialTheme.typography.labelLarge,
        letterSpacing = 1.5.sp,
    )
}

/**
 * Dialog drawn inside the activity window (not a separate window like AlertDialog), so controller
 * buttons keep flowing through the app's mapping. Focus stays inside it; Back dismisses it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeckDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = DeckColors.Surface,
) {
    BackHandler(onBack = onDismissRequest)
    // Pull focus into the dialog (children that request focus themselves run after this and win).
    val group = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { group.requestFocus() } }
    Box(
        Modifier.fillMaxSize().zIndex(10f).background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDismissRequest() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier.padding(24.dp).widthIn(max = 600.dp).clip(RoundedCornerShape(24.dp)).background(DeckColors.Surface)
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps so they don't dismiss
                .focusRequester(group)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (icon != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { icon() }
                Spacer(Modifier.height(12.dp))
            }
            if (title != null) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                Spacer(Modifier.height(14.dp))
            }
            if (text != null) {
                Box(Modifier.weight(1f, fill = false)) {
                    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)) { text() }
                }
                Spacer(Modifier.height(20.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/** Focusable on/off row (A toggles), used for per-system settings like "extract after download". */
@Composable
fun DeckToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    DeckFocusable(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        background = DeckColors.SurfaceHigh,
        focusedScale = 1.01f,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (description != null) Text(description, fontSize = 12.sp, color = DeckColors.TextDim)
            }
            Spacer(Modifier.width(14.dp))
            // Simple switch drawn to match the Deck theme.
            Box(
                Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(50))
                    .background(if (checked) DeckColors.Accent else DeckColors.Background),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(3.dp).size(16.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}
