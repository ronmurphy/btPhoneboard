package com.ronmurphy.btphoneboard

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.ronmurphy.btphoneboard.bluetooth.HidController
import com.ronmurphy.btphoneboard.bluetooth.HidStatus
import com.ronmurphy.btphoneboard.bluetooth.HostDevice
import com.ronmurphy.btphoneboard.hid.InputModel
import com.ronmurphy.btphoneboard.hid.ModState
import com.ronmurphy.btphoneboard.hid.ModifierKey
import com.ronmurphy.btphoneboard.hid.MouseButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bridge between the screen and the Bluetooth code.
 *
 * A ViewModel outlives the Activity when Android recreates it (for example
 * when you flip the phone to the other landscape direction), so the Bluetooth
 * connection isn't dropped. It is cleared only when the user really leaves the
 * app, and that's when we unregister the keyboard (onCleared).
 *
 * The UI reads StateFlows (values that can change over time and notify the
 * screen) and calls the plain functions when the user touches something.
 */
class PhoneboardViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("phoneboard", Context.MODE_PRIVATE)
    private val controller = HidController(application, prefs)
    private val input = InputModel { id, data -> controller.sendReport(id, data) }

    val status: StateFlow<HidStatus> = controller.status

    private val _modifiers = MutableStateFlow(input.modifiers)
    val modifiers: StateFlow<Map<ModifierKey, ModState>> = _modifiers.asStateFlow()

    private val _fnActive = MutableStateFlow(false)
    val fnActive: StateFlow<Boolean> = _fnActive.asStateFlow()

    private val _mouseSpeed = MutableStateFlow(prefs.getFloat(KEY_MOUSE_SPEED, 1f))
    val mouseSpeed: StateFlow<Float> = _mouseSpeed.asStateFlow()

    init {
        input.onModifiersChanged = { _modifiers.value = it }
    }

    // Bluetooth
    fun hasPermission() = controller.hasPermission()
    fun start() = controller.start()
    fun bondedDevices(): List<HostDevice> = controller.bondedDevices()
    fun connect(device: HostDevice) = controller.connect(device.address)
    fun disconnect() = controller.disconnect()

    // Keyboard
    fun keyDown(usage: Int) = input.keyDown(usage)
    fun keyUp(usage: Int) = input.keyUp(usage)
    fun toggleModifier(key: ModifierKey) = input.toggleModifier(key)
    fun toggleFn() {
        _fnActive.value = !_fnActive.value
    }

    // Media keys
    fun mediaKeyDown(usage: Int) = input.mediaKeyDown(usage)
    fun mediaKeyUp() = input.mediaKeyUp()

    // Trackpad
    fun moveMouse(dx: Float, dy: Float) {
        val speed = _mouseSpeed.value
        input.moveMouse(dx * speed, dy * speed)
    }

    /**
     * [dyDp] is how far two fingers moved, in dp. The content follows your
     * fingers, like scrolling on the phone itself: dragging down sends positive
     * wheel notches ("scroll up"), which moves the page down.
     */
    fun scroll(dyDp: Float) = input.scroll(dyDp / SCROLL_DP_PER_NOTCH)

    fun tap(fingers: Int) = input.click(
        when (fingers) {
            1 -> MouseButton.LEFT
            2 -> MouseButton.RIGHT
            else -> MouseButton.MIDDLE
        },
    )

    fun mouseButtonDown(button: Int) = input.mouseButtonDown(button)
    fun mouseButtonUp(button: Int) = input.mouseButtonUp(button)

    fun cycleMouseSpeed() {
        val index = MOUSE_SPEEDS.indexOf(_mouseSpeed.value)
        val next = MOUSE_SPEEDS[(index + 1) % MOUSE_SPEEDS.size]
        _mouseSpeed.value = next
        prefs.edit().putFloat(KEY_MOUSE_SPEED, next).apply()
    }

    override fun onCleared() {
        controller.stop()
    }

    companion object {
        val MOUSE_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f)

        private const val KEY_MOUSE_SPEED = "mouse_speed"
        private const val SCROLL_DP_PER_NOTCH = 16f
    }
}
