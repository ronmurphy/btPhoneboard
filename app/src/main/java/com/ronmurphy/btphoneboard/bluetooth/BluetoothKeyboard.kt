package com.ronmurphy.btphoneboard.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's single entry point to Bluetooth. It:
 *  - checks the basics (Bluetooth exists, permission granted, Bluetooth on),
 *  - runs either a ClassicHidLink or a BleHidLink, and switches to BLE by
 *    itself if this phone doesn't support Classic,
 *  - restarts things when Bluetooth is turned off and on again.
 */
@SuppressLint("MissingPermission")
class BluetoothKeyboard(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow<HidStatus>(HidStatus.Starting)
    val status: StateFlow<HidStatus> = _status.asStateFlow()

    private val _mode = MutableStateFlow(
        LinkMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: LinkMode.CLASSIC,
    )
    val mode: StateFlow<LinkMode> = _mode.asStateFlow()

    private var link: HidLink? = null

    // Goes up every time we throw a link away, so late status updates from an
    // old link can be recognised and ignored.
    private var linkGeneration = 0
    private var stopped = false

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Safe to call many times (the activity calls it on every resume). */
    fun start() {
        if (stopped) return
        val adapter = adapter
        when {
            adapter == null -> _status.value = HidStatus.Unavailable("This phone has no Bluetooth.")
            !hasPermission() -> _status.value = HidStatus.NeedsPermission
            !adapter.isEnabled -> _status.value = HidStatus.BluetoothOff
            else -> (link ?: createLink(_mode.value)).start()
        }
    }

    /** Switch between Classic and BLE. The choice is remembered for next time. */
    fun setMode(mode: LinkMode) {
        if (mode == _mode.value) return
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        releaseLink()
        start()
    }

    fun connect(address: String) {
        link?.connect(address)
    }

    fun disconnect() {
        link?.disconnect()
    }

    fun sendReport(reportId: Int, data: ByteArray) {
        link?.sendReport(reportId, data)
    }

    fun bondedDevices(): List<HostDevice> = try {
        adapter?.bondedDevices.orEmpty()
            .map { HostDevice(it.displayName(), it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** The name other devices see for this phone. */
    fun phoneName(): String =
        try {
            adapter?.name
        } catch (e: SecurityException) {
            null
        } ?: "this phone"

    /** Called when the app is closing. */
    fun stop() {
        stopped = true
        releaseLink()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (ignored: IllegalArgumentException) {
            // wasn't registered
        }
    }

    private fun createLink(mode: LinkMode): HidLink {
        val generation = ++linkGeneration
        val reportStatus: (HidStatus) -> Unit = { status ->
            if (generation == linkGeneration) _status.value = status
        }
        val newLink = when (mode) {
            LinkMode.CLASSIC -> ClassicHidLink(
                context,
                prefs,
                onStatus = reportStatus,
                // Posted rather than called directly, so the Classic link has
                // finished what it was doing before we shut it down.
                onUnsupported = {
                    mainHandler.post { if (generation == linkGeneration) setMode(LinkMode.BLE) }
                },
            )

            LinkMode.BLE -> BleHidLink(context, prefs, onStatus = reportStatus)
        }
        link = newLink
        return newLink
    }

    private fun releaseLink() {
        link?.stop()
        link = null
        linkGeneration++
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> start()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    releaseLink()
                    _status.value = HidStatus.BluetoothOff
                }
            }
        }
    }

    // Last on purpose: Kotlin runs property initializers and init blocks top to
    // bottom, so the receiver above exists by now.
    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ wants us to say whether other apps may send to this
            // receiver. This broadcast only ever comes from the system.
            context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    private companion object {
        const val KEY_MODE = "link_mode"
    }
}
