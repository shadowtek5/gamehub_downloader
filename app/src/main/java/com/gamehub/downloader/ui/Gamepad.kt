package com.gamehub.downloader.ui

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Everything a controller button can do in the app. */
enum class GameAction(val label: String, val description: String) {
    Confirm("Select", "Open or press the highlighted item"),
    Back("Back", "Go back / close"),
    Downloads("Downloads", "Open the downloads queue"),
    Search("Search", "Search the library"),
    PrevSystem("Previous system", "Library: previous system"),
    NextSystem("Next system", "Library: next system"),
    Settings("Settings", "Open settings"),
    SwitchConnection("Switch server", "Switch GameHub connection"),
}

/** Preset face-button arrangement. Nintendo-style swaps A↔B and X↔Y. */
@Serializable
enum class ButtonLayout(val label: String) {
    Standard("Standard (A confirms)"),
    Nintendo("Nintendo-style (A↔B, X↔Y)"),
}

/** Saved mapping for one physical controller. Empty [custom] means fully auto-mapped. */
@Serializable
data class ControllerProfile(
    val key: String,
    val name: String,
    val layout: ButtonLayout = ButtonLayout.Standard,
    val custom: Map<GameAction, Int> = emptyMap(),
) {
    val isAuto: Boolean get() = custom.isEmpty() && layout == ButtonLayout.Standard

    fun bindings(): Map<GameAction, Int> = Gamepad.defaults(layout) + custom
}

data class DetectedController(val key: String, val name: String)

data class ButtonPress(val keyCode: Int, val action: GameAction?, val controller: String)

private val Context.controlsStore by preferencesDataStore(name = "controls")

/**
 * Controller input for the whole app: recognises each controller, applies its saved (or auto)
 * mapping, and turns button presses into [GameAction]s that screens collect via [OnAction].
 */
object Gamepad {
    private val _events = MutableSharedFlow<GameAction>(extraBufferCapacity = 8)
    val events: SharedFlow<GameAction> = _events.asSharedFlow()

    private val _profiles = MutableStateFlow<Map<String, ControllerProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, ControllerProfile>> = _profiles.asStateFlow()

    /** The controller that pressed a button most recently. */
    private val _active = MutableStateFlow<DetectedController?>(null)
    val active: StateFlow<DetectedController?> = _active.asStateFlow()

    /** Fires the first time a controller is seen this session (for the "connected" toast). */
    private val _connected = MutableSharedFlow<DetectedController>(extraBufferCapacity = 4)
    val connected: SharedFlow<DetectedController> = _connected.asSharedFlow()
    private val seen = mutableSetOf<String>()

    private val _lastPress = MutableStateFlow<ButtonPress?>(null)
    val lastPress: StateFlow<ButtonPress?> = _lastPress.asStateFlow()

    /** While non-null, the next button press is assigned to this action instead of performing it. */
    private val _capturing = MutableStateFlow<GameAction?>(null)
    val capturing: StateFlow<GameAction?> = _capturing.asStateFlow()
    private var swallowUpFor: Int? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val storeKey = stringPreferencesKey("profiles")
    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope

    fun init(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        this.scope = scope
        scope.launch {
            val saved = appContext.controlsStore.data.first()[storeKey]
                ?.let { runCatching { json.decodeFromString(ListSerializer(ControllerProfile.serializer()), it) }.getOrNull() }
                .orEmpty()
            _profiles.value = saved.associateBy { it.key }
            // Persist every later change.
            _profiles.drop(1).collect { map ->
                appContext.controlsStore.edit {
                    it[storeKey] = json.encodeToString(ListSerializer(ControllerProfile.serializer()), map.values.toList())
                }
            }
        }
    }

    fun defaults(layout: ButtonLayout): Map<GameAction, Int> {
        val nintendo = layout == ButtonLayout.Nintendo
        return mapOf(
            GameAction.Confirm to if (nintendo) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A,
            GameAction.Back to if (nintendo) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B,
            GameAction.Downloads to if (nintendo) KeyEvent.KEYCODE_BUTTON_Y else KeyEvent.KEYCODE_BUTTON_X,
            GameAction.Search to if (nintendo) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y,
            GameAction.PrevSystem to KeyEvent.KEYCODE_BUTTON_L1,
            GameAction.NextSystem to KeyEvent.KEYCODE_BUTTON_R1,
            GameAction.Settings to KeyEvent.KEYCODE_BUTTON_START,
            GameAction.SwitchConnection to KeyEvent.KEYCODE_BUTTON_SELECT,
        )
    }

    /** Controller buttons we handle (D-pad stays as normal focus navigation; Mode/Home stays with the system). */
    fun isMappable(keyCode: Int): Boolean =
        KeyEvent.isGamepadButton(keyCode) && keyCode != KeyEvent.KEYCODE_BUTTON_MODE

    /** Identity of a real gamepad; keyboards and virtual devices return null (they reuse the active profile). */
    private fun keyFor(device: InputDevice?): DetectedController? {
        device ?: return null
        val isPad = device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK)
        if (!isPad || device.isVirtual) return null
        val key = "%04x:%04x:%s".format(device.vendorId, device.productId, device.name)
        return DetectedController(key, device.name)
    }

    /** Saved profile for [controller] (or the "default" one used before any gamepad is detected), else auto. */
    fun profileFor(controller: DetectedController?, saved: Map<String, ControllerProfile> = _profiles.value): ControllerProfile {
        val key = controller?.key ?: "default"
        return saved[key] ?: ControllerProfile(key, controller?.name ?: "Controller")
    }

    /** Bindings for the controller in use right now (auto defaults until one is customised). */
    fun currentBindings(): Map<GameAction, Int> = profileFor(_active.value).bindings()

    /**
     * Routes one controller key event: records which controller is in use, completes a pending
     * remap, or returns the mapped action. Unmapped buttons are consumed so they do nothing.
     */
    fun resolve(event: KeyEvent): Resolution {
        val code = event.keyCode
        val pad = keyFor(event.device)
        if (pad != null && _active.value?.key != pad.key) {
            _active.value = pad
        }
        if (pad != null && seen.add(pad.key) && event.action == KeyEvent.ACTION_DOWN) {
            _connected.tryEmit(pad)
        }
        val controller = pad ?: _active.value

        // Remap in progress: this press becomes the new binding.
        val target = _capturing.value
        if (target != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            assign(controller, target, code)
            _capturing.value = null
            swallowUpFor = code
            return Resolution.Consumed
        }
        if (event.action == KeyEvent.ACTION_UP && swallowUpFor == code) {
            swallowUpFor = null
            return Resolution.Consumed
        }

        val action = profileFor(controller).bindings().entries.firstOrNull { it.value == code }?.key
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            _lastPress.value = ButtonPress(code, action, controller?.name ?: "Unknown")
        }
        return if (action == null) Resolution.Consumed else Resolution.Action(action)
    }

    sealed interface Resolution {
        data object Consumed : Resolution
        data class Action(val action: GameAction) : Resolution
    }

    fun emit(action: GameAction) = _events.tryEmit(action)

    fun startCapture(action: GameAction) { _capturing.value = action }
    fun cancelCapture() { _capturing.value = null }

    /** Binds [keyCode] to [action]; if another action used that button, the two swap. */
    private fun assign(controller: DetectedController?, action: GameAction, keyCode: Int) {
        val base = profileFor(controller)
        val current = base.bindings()
        val previousKey = current[action]
        val clash = current.entries.firstOrNull { it.value == keyCode && it.key != action }?.key
        val updated = current.toMutableMap().apply {
            this[action] = keyCode
            if (clash != null && previousKey != null) this[clash] = previousKey
        }
        val defaults = defaults(base.layout)
        val custom = updated.filter { (a, k) -> defaults[a] != k }
        _profiles.update { it + (base.key to base.copy(custom = custom)) }
    }

    fun setLayout(layout: ButtonLayout) {
        val base = profileFor(_active.value)
        _profiles.update { it + (base.key to base.copy(layout = layout, custom = emptyMap())) }
    }

    fun resetToAuto() {
        val base = profileFor(_active.value)
        _profiles.update { it - base.key }
    }

    fun buttonLabel(keyCode: Int?): String = when (keyCode) {
        null -> "—"
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_C -> "C"
        KeyEvent.KEYCODE_BUTTON_Z -> "Z"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_")
    }
}

/** Collects controller shortcut actions while this composable is on screen. */
@Composable
fun OnAction(handler: (GameAction) -> Unit) {
    val current = rememberUpdatedState(handler)
    LaunchedEffect(Unit) { Gamepad.events.collect { current.value(it) } }
}

/** Label of the button currently bound to [action] (e.g. "A"), updating live when remapped. */
@Composable
fun boundButtonLabel(action: GameAction): String {
    val profiles by Gamepad.profiles.collectAsState()
    val active by Gamepad.active.collectAsState()
    val key = Gamepad.profileFor(active, profiles).bindings()[action]
    return Gamepad.buttonLabel(key)
}
