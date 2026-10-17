package com.gamehub.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gamehub.downloader.ui.AppRoot
import com.gamehub.downloader.ui.GameAction
import com.gamehub.downloader.ui.Gamepad
import com.gamehub.downloader.ui.theme.DeckTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Set when launched from a download notification so the UI jumps to the queue. */
    val openDownloads = MutableStateFlow(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            DeckTheme { AppRoot(openDownloads) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) openDownloads.value = true
    }

    /**
     * Controller input: every gamepad button goes through [Gamepad], which applies the per-controller
     * mapping (auto or custom). Confirm becomes a D-pad-center press so focused items click;
     * the D-pad itself is left alone for focus navigation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!Gamepad.isMappable(event.keyCode)) return super.dispatchKeyEvent(event)
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        return when (val r = Gamepad.resolve(event)) {
            Gamepad.Resolution.Consumed -> true
            is Gamepad.Resolution.Action -> when (r.action) {
                GameAction.Confirm -> super.dispatchKeyEvent(
                    KeyEvent(
                        event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                        event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                    )
                )
                GameAction.Back -> {
                    // Like the system Back key: if the on-screen keyboard is up, B just closes it.
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backClosesKeyboard = isKeyboardVisible()
                    if (event.action == KeyEvent.ACTION_UP) {
                        if (backClosesKeyboard) hideKeyboard() else onBackPressedDispatcher.onBackPressed()
                        backClosesKeyboard = false
                    }
                    true
                }
                else -> {
                    if (firstDown) Gamepad.emit(r.action)
                    true
                }
            }
        }
    }

    private var backClosesKeyboard = false

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun hideKeyboard() {
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
