package com.ronmurphy.btphoneboard.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ronmurphy.btphoneboard.hid.HidDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** What the UI shows in the status bar. */
sealed interface HidStatus {
    data object Starting : HidStatus
    data object NeedsPermission : HidStatus
    data object BluetoothOff : HidStatus
    data class Unavailable(val reason: String) : HidStatus
    /** Registered as a keyboard/mouse, but no TV or tablet is connected. */
    data object Ready : HidStatus
    data class Connecting(val deviceName: String) : HidStatus
    data class Connected(val deviceName: String, val address: String) : HidStatus
}

/** A paired device the user can pick in the Devices dialog. */
data class HostDevice(val name: String, val address: String)

/**
 * Wraps Android's BluetoothHidDevice API, which lets the phone act as a
 * Bluetooth keyboard + mouse.
 *
 * The steps are:
 *  1. Ask Android for the HID_DEVICE "profile proxy" (getProfileProxy).
 *  2. When it arrives, register our app with our HID descriptor (registerApp).
 *  3. Connect to a paired TV/tablet (connect), or wait for it to connect to us.
 *  4. Send reports (sendReport) for every key press and mouse movement.
 *
 * All of Android's Bluetooth callbacks are delivered on the main thread, so
 * the fields below are only touched from one thread.
 *
 * MissingPermission is suppressed because Android Studio can't see that we
 * check BLUETOOTH_CONNECT in start() before calling any of these APIs.
 */
@SuppressLint("MissingPermission")
class HidController(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    // sendReport() talks to the Bluetooth system service. Doing that on its own
    // thread keeps the UI smooth; a single thread keeps reports in order.
    private val sendExecutor = Executors.newSingleThreadExecutor()

    private val _status = MutableStateFlow<HidStatus>(HidStatus.Starting)
    val status: StateFlow<HidStatus> = _status.asStateFlow()

    private var hidDevice: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var registered = false
    private var stopped = false

    /** The TV/tablet we are connected to, if any. */
    private var host: BluetoothDevice? = null

    // ------------------------------------------------------------ public API

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Safe to call many times (the activity calls it on every resume). */
    fun start() {
        if (stopped) return
        val adapter = adapter
        if (adapter == null) {
            _status.value = HidStatus.Unavailable("This phone has no Bluetooth.")
            return
        }
        if (!hasPermission()) {
            _status.value = HidStatus.NeedsPermission
            return
        }
        if (!adapter.isEnabled) {
            _status.value = HidStatus.BluetoothOff
            return
        }
        if (proxyRequested) return // already started, or starting

        _status.value = HidStatus.Starting
        proxyRequested = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        if (proxyRequested) {
            // Phones without HID device support sometimes never answer at all.
            mainHandler.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
        } else {
            _status.value = HidStatus.Unavailable(NOT_SUPPORTED)
        }
    }

    fun bondedDevices(): List<HostDevice> = try {
        adapter?.bondedDevices.orEmpty()
            .map { HostDevice(it.displayName(), it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (e: SecurityException) {
        emptyList()
    }

    fun connect(address: String) {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        connect(device)
    }

    fun disconnect() {
        val current = host ?: return
        hidDevice?.disconnect(current)
        // The user chose to disconnect, so don't auto-reconnect next time.
        prefs.edit().remove(KEY_LAST_HOST).apply()
    }

    fun sendReport(reportId: Int, data: ByteArray) {
        val hid = hidDevice ?: return
        val target = host ?: return
        if (sendExecutor.isShutdown) return
        sendExecutor.execute { hid.sendReport(target, reportId, data) }
    }

    /** Called when the app is closing. */
    fun stop() {
        stopped = true
        releaseProfile()
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (ignored: IllegalArgumentException) {
            // wasn't registered
        }
        sendExecutor.shutdown()
    }

    // -------------------------------------------------------------- internals

    private fun connect(device: BluetoothDevice) {
        val hid = hidDevice ?: return
        if (!registered) return
        val current = host
        if (current != null && current.address != device.address) hid.disconnect(current)
        _status.value = HidStatus.Connecting(device.displayName())
        if (!hid.connect(device)) _status.value = HidStatus.Ready
    }

    private fun register(hid: BluetoothHidDevice) {
        // SDP = Service Discovery Protocol: the info a host reads to learn what
        // we are. SUBCLASS1_COMBO says "keyboard and pointing device in one".
        val sdp = BluetoothHidDeviceAppSdpSettings(
            SDP_NAME,
            SDP_DESCRIPTION,
            SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidDescriptor.BYTES,
        )
        val ok = hid.registerApp(sdp, null, null, context.mainExecutor, hidCallback)
        if (!ok) {
            _status.value = HidStatus.Unavailable(
                "Couldn't start. Another keyboard/mouse app may be running; close it and try again.",
            )
        }
    }

    private fun releaseProfile() {
        mainHandler.removeCallbacks(proxyTimeout)
        val hid = hidDevice
        if (hid != null) {
            if (registered) hid.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        hidDevice = null
        host = null
        registered = false
        proxyRequested = false
    }

    private fun savedHost(): BluetoothDevice? {
        val address = prefs.getString(KEY_LAST_HOST, null) ?: return null
        return adapter?.bondedDevices?.firstOrNull { it.address == address }
    }

    private fun BluetoothDevice.displayName(): String =
        try {
            name
        } catch (e: SecurityException) {
            null
        } ?: address

    private val proxyTimeout = Runnable {
        if (hidDevice == null && proxyRequested) {
            _status.value = HidStatus.Unavailable(NOT_SUPPORTED)
        }
    }

    // ------------------------------------------------------------- callbacks

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            mainHandler.removeCallbacks(proxyTimeout)
            if (stopped || !proxyRequested) {
                // We gave up on it (app closing or Bluetooth turned off) before it arrived.
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                return
            }
            hidDevice = proxy
            register(proxy)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            releaseProfile()
            if (adapter?.isEnabled == true) {
                // The Bluetooth service restarted. Try again in a moment.
                _status.value = HidStatus.Starting
                mainHandler.postDelayed({ start() }, 1_000)
            } else {
                _status.value = HidStatus.BluetoothOff
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidController.registered = registered
            if (!registered) {
                host = null
                if (hidDevice != null && !stopped) {
                    _status.value = HidStatus.Unavailable("Android stopped the keyboard. Reopen the app to restart it.")
                }
                return
            }
            _status.value = HidStatus.Ready
            // pluggedDevice is a host we were connected to before. If Android
            // doesn't give us one, try the last device we connected to.
            val target = pluggedDevice ?: savedHost()
            if (target != null) connect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            device ?: return
            Log.d(TAG, "connection state ${device.address} -> $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTING ->
                    _status.value = HidStatus.Connecting(device.displayName())

                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString(KEY_LAST_HOST, device.address).apply()
                    _status.value = HidStatus.Connected(device.displayName(), device.address)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val current = host
                    if (current == null || current.address == device.address) {
                        host = null
                        if (this@HidController.registered) _status.value = HidStatus.Ready
                    }
                }
            }
        }

        // The host can ask "what's your current report?" We answer with an
        // all-zero report (nothing pressed), or an error for IDs we don't have.
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            device ?: return
            val hid = hidDevice ?: return
            val size = HidDescriptor.inputReportSize(id.toInt())
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && size != null) {
                hid.replyReport(device, type, id, ByteArray(size))
            } else {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        // The host sends us data (for example the Caps Lock light). We just
        // acknowledge it; "reportError(SUCCESS)" is how the API says "OK".
        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            device ?: return
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> start()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    releaseProfile()
                    _status.value = HidStatus.BluetoothOff
                }
            }
        }
    }

    // This init block comes last on purpose: Kotlin runs property initializers
    // and init blocks top to bottom, so everything above exists by now.
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
        const val TAG = "HidController"
        const val SDP_NAME = "BT Phoneboard"
        const val SDP_DESCRIPTION = "Phone keyboard and trackpad"
        const val SDP_PROVIDER = "BT Phoneboard"
        const val KEY_LAST_HOST = "last_host_address"
        const val PROXY_TIMEOUT_MS = 5_000L
        const val NOT_SUPPORTED = "This phone doesn't let apps act as a Bluetooth keyboard."
    }
}
