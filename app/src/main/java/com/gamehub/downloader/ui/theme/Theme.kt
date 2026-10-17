package com.gamehub.downloader.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Steam Deck–inspired palette: deep blue-greys with a bright accent for focus.
object DeckColors {
    val Background = Color(0xFF0E141B)
    val Surface = Color(0xFF1A2330)
    val SurfaceHigh = Color(0xFF243142)
    val SurfaceFocus = Color(0xFF2F4058)
    val Accent = Color(0xFF1A9FFF)
    val AccentSoft = Color(0xFF1A9FFF).copy(alpha = 0.18f)
    val Text = Color(0xFFDCE3EA)
    val TextDim = Color(0xFF8B97A6)
    val Success = Color(0xFF59BF40)
    val Warning = Color(0xFFE8B23A)
    val Error = Color(0xFFE5484D)
}

private val scheme = darkColorScheme(
    primary = DeckColors.Accent,
    onPrimary = Color.White,
    secondary = DeckColors.Accent,
    background = DeckColors.Background,
    onBackground = DeckColors.Text,
    surface = DeckColors.Surface,
    onSurface = DeckColors.Text,
    surfaceVariant = DeckColors.SurfaceHigh,
    onSurfaceVariant = DeckColors.TextDim,
    surfaceContainer = DeckColors.Surface,
    surfaceContainerHigh = DeckColors.SurfaceHigh,
    surfaceContainerHighest = DeckColors.SurfaceHigh,
    error = DeckColors.Error,
)

@Composable
fun DeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(LocalContentColor provides DeckColors.Text, content = content)
    }
}
