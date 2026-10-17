package com.gamehub.downloader.ui.screens

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamehub.downloader.GameHubApp
import com.gamehub.downloader.api.GameHubApi
import com.gamehub.downloader.data.Connection
import com.gamehub.downloader.ui.rememberDeckLayout
import com.gamehub.downloader.ui.theme.DeckColors
import com.gamehub.downloader.ui.components.DeckButton
import com.gamehub.downloader.ui.components.DeckDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionEditScreen(connectionId: String?, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as GameHubApp
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(connectionId == null) }
    var existing by remember { mutableStateOf<Connection?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<ConnStatus?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(connectionId) {
        if (connectionId != null) {
            val c = app.settings.current().connections.firstOrNull { it.id == connectionId }
            existing = c
            if (c != null) { name = c.name; url = c.baseUrl; token = c.token }
            loaded = true
        }
    }

    fun draft() = (existing ?: Connection(name = "", baseUrl = "", token = "")).copy(
        name = name.trim().ifBlank { runCatching { GameHubApi.normalizeBaseUrl(url).host }.getOrDefault("GameHub") },
        baseUrl = runCatching { GameHubApi.normalizeBaseUrl(url).toString().trimEnd('/') }.getOrDefault(url.trim()),
        token = token.trim(),
    )

    fun test() {
        status = ConnStatus(null, "Checking…")
        scope.launch { status = testConnection(app, draft()) }
    }

    val urlValid = runCatching { GameHubApi.normalizeBaseUrl(url) }.isSuccess && url.isNotBlank()
    val urlFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    val pairFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }

    /** Buttons stay pressable; if something is missing, say what and jump to it. */
    fun requireUrl(): Boolean {
        if (urlValid) return true
        status = ConnStatus(false, "Enter your GameHub server address first, e.g. http://192.168.1.50:3000")
        runCatching { urlFocus.requestFocus() }
        return false
    }
    fun requireToken(): Boolean {
        if (!requireUrl()) return false
        if (token.isNotBlank()) return true
        status = ConnStatus(false, "No API token yet. Press Pair with QR, or paste a token from GameHub › Account › API tokens.")
        runCatching { pairFocus.requestFocus() }
        return false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (connectionId == null) "Add connection" else "Edit connection", onDone)
        if (!loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it; status = null },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:3000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                // Done hides the keyboard and moves to Pair with QR (or Save once a token exists).
                keyboardActions = KeyboardActions(onDone = {
                    runCatching { if (token.isBlank()) pairFocus.requestFocus() else saveFocus.requestFocus() }
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(urlFocus),
                colors = deckFieldColors(),
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text("Home server") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                colors = deckFieldColors(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it; status = null },
                label = { Text("API token") },
                placeholder = { Text("ghk_…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { runCatching { saveFocus.requestFocus() } }),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(tokenFocus),
                colors = deckFieldColors(),
            )
            Text(
                "Create a token in GameHub under Account › API tokens, or use \"Pair with QR\": scan the code with a phone " +
                    "or PC that's signed in to GameHub and approve this device. No typing needed.",
                color = DeckColors.TextDim, fontSize = 13.sp,
            )

            status?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (s.ok) { true -> Icons.Default.CheckCircle; false -> Icons.Default.Error; null -> Icons.Default.HourglassEmpty },
                        null, Modifier.size(18.dp),
                        tint = when (s.ok) { true -> DeckColors.Success; false -> DeckColors.Error; null -> DeckColors.TextDim },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(s.text, fontSize = 13.sp)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeckButton(
                    "Pair with QR",
                    { if (requireUrl()) pairing = true },
                    Modifier.focusRequester(pairFocus),
                    icon = Icons.Default.QrCode2,
                    primary = token.isBlank(),
                )
                DeckButton("Test", { if (requireToken()) test() }, icon = Icons.Default.CheckCircle)
                DeckButton(
                    "Save",
                    {
                        if (requireToken()) scope.launch {
                            val c = draft()
                            app.settings.upsertConnection(c)
                            if (existing == null) app.settings.setActiveConnection(c.id)
                            onDone()
                        }
                    },
                    Modifier.focusRequester(saveFocus),
                    icon = Icons.Default.Save,
                    primary = token.isNotBlank(),
                )
                if (existing != null) DeckButton("Delete", { confirmDelete = true }, icon = Icons.Default.Delete)
            }
        }
    }

    if (pairing) {
        PairDialog(
            baseUrl = url,
            onToken = { t ->
                token = t
                pairing = false
                test()
            },
            onDismiss = { pairing = false },
        )
    }

    if (confirmDelete) {
        DeckDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DeckColors.Surface,
            title = { Text("Delete ${existing?.name}?") },
            text = { Text("This removes the connection and its folder settings from this device. Downloaded games stay on disk.") },
            confirmButton = {
                FocusedButton("Delete") {
                    scope.launch {
                        existing?.let { app.settings.deleteConnection(it.id); app.history.clear(it.id) }
                        confirmDelete = false
                        onDone()
                    }
                }
            },
            dismissButton = { DeckButton("Cancel", { confirmDelete = false }) },
        )
    }
}

/**
 * GameHub's device pairing: the server issues a short-lived request, the user scans the QR on a
 * signed-in device and approves it, and we poll until a token comes back.
 */
@Composable
private fun PairDialog(baseUrl: String, onToken: (String) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as GameHubApp
    var approvalUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf("Contacting server…") }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        message = "Contacting server…"
        val device = listOf(Build.MANUFACTURER, Build.MODEL).distinct().joinToString(" ").trim().ifBlank { "Android" }
        val start = runCatching { GameHubApi.pairStart(app.http, baseUrl, "$device (GameHub Downloader)") }.getOrElse {
            message = "Couldn't start pairing: ${it.message}"
            failed = true
            return@LaunchedEffect
        }
        val link = GameHubApi.pairApprovalUrl(baseUrl, start.id)
        approvalUrl = link
        qr = qrBitmap(link)
        message = "Waiting for approval…"
        val deadline = System.currentTimeMillis() + start.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val poll = runCatching { GameHubApi.pairPoll(app.http, baseUrl, start.id, start.secret) }.getOrNull() ?: continue
            when (poll.status) {
                "approved" -> if (poll.token != null) { onToken(poll.token); return@LaunchedEffect }
                "denied" -> { message = "Pairing was denied."; failed = true; return@LaunchedEffect }
                "expired", "consumed", "not_found" -> { message = "Pairing request expired."; failed = true; return@LaunchedEffect }
            }
            if (poll.error == "bad_secret") { message = "Pairing failed (bad secret)."; failed = true; return@LaunchedEffect }
        }
        message = "Pairing request expired."
        failed = true
    }

    DeckDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        title = { Text("Pair with GameHub") },
        text = {
            val wide = rememberDeckLayout().widthDp >= 600
            val qrBox: @Composable () -> Unit = {
                Box(
                    Modifier.size(if (wide) 230.dp else 190.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = qr
                    if (bmp != null && !failed) Image(bmp, "Pairing QR code", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                    else if (!failed) CircularProgressIndicator(color = DeckColors.Accent)
                }
            }
            val info: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { align, textAlign ->
                Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Scan this code with a phone or computer that's signed in to GameHub, then tap Approve. " +
                            "Or open the link below in a signed-in browser.",
                        color = DeckColors.TextDim, textAlign = textAlign,
                    )
                    approvalUrl?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = textAlign) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!failed) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = DeckColors.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(message, fontSize = 13.sp, color = if (failed) DeckColors.Error else DeckColors.Text)
                    }
                }
            }
            if (wide) {
                // Side by side so the whole QR fits on short landscape screens like the AYN Thor.
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    qrBox()
                    info(Alignment.Start, TextAlign.Start)
                }
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    qrBox()
                    info(Alignment.CenterHorizontally, TextAlign.Center)
                }
            }
        },
        confirmButton = { if (failed) FocusedButton("Try again") { attempt++ } },
        dismissButton = { DeckButton("Cancel", onDismiss) },
    )
}

private fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
