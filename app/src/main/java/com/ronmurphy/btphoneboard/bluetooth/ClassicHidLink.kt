package com.ronmurphy.btphoneboard.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ronmurphy.btphoneboard.hid.HidDescriptor
import java.util.concurrent.Executors

/**
 * Classic Bluetooth keyboard + mouse, using Android's BluetoothHidDevice API.
 *
 * The steps are:
 *  1. Ask Android for the HID_DEVICE "profile proxy" (getProfileProxy).
 *  2. When it arrives, register our app with our HID descriptor (registerApp).
 *  3. Connect to a paired TV/tablet (connect), or wait for it to connect to us.
 *  4. Send reports (sendReport) for every key press and mouse movement.
 *
 * If the phone maker has switched this feature off, step 1 never completes
 * and we call [onUnsupported] so the app can switch to BLE mode instead.
 *
 * All of Android's Bluetooth callbacks are delivered on the main thread, so
 * the fields below are only touched from one thread.
 *
 * MissingPermission is suppressed because Android Studio can't see that
 * BluetoothKeyboard checks BLUETOOTH_CONNECT before starting us.
 */
@SuppressLint("MissingPermission")
class ClassicHidLink(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onStatus: (HidStatus) -> Unit,
    private val onUnsupported: () -> Unit,
) : HidLink {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    // sendReport() talks to the Bluetooth system service. Doing that on its own
    // thread keeps the UI smooth; a single thread keeps reports in order.
    private val sendExecutor = Executors.newSingleThreadExecutor()

    private var hidDevice: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var registered = false
    private var stopped = false

    /** The TV/tablet we are connected to, if any. */
    private var host: BluetoothDevice? = null

    // ------------------------------------------------------------ public API

    /** Safe to call many times (the activity calls it on every resume). */
    override fun start() {
        if (stopped || proxyRequested) return // stopped, already started, or starting
        val adapter = adapter ?: run {
            onUnsupported()
            return
        }
        onStatus(HidStatus.Starting)
        proxyRequested = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        if (proxyRequested) {
            // Phones without HID device support usually never answer at all.
            mainHandler.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
        } else {
            onUnsupported()
        }
    }

    override fun connect(address: String) {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        connect(device)
    }

    override fun disconnect() {
        val current = host ?: return
        hidDevice?.disconnect(current)
        // The user chose to disconnect, so don't auto-reconnect next time.
        prefs.edit().remove(KEY_LAST_HOST).apply()
    }

    override fun sendReport(reportId: Int, data: ByteArray) {
        val hid = hidDevice ?: return
        val target = host ?: return
        if (sendExecutor.isShutdown) return
        sendExecutor.execute { hid.sendReport(target, reportId, data) }
    }

    override fun stop() {
        stopped = true
        releaseProfile()
        sendExecutor.shutdown()
    }

    // -------------------------------------------------------------- internals

    private fun connect(device: BluetoothDevice) {
        val hid = hidDevice ?: return
        if (!registered) return
        val current = host
        if (current != null && current.address != device.address) hid.disconnect(current)
        onStatus(HidStatus.Connecting(device.displayName()))
        if (!hid.connect(device)) onStatus(HidStatus.Ready)
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
            onStatus(
                HidStatus.Unavailable(
                    "Couldn't start. Another keyboard/mouse app may be running; close it, or switch to BLE in Devices.",
                ),
            )
        }
    }

    private fun releaseProfile() {
        mainHandler.removeCallbacksAndMessages(null)
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

    private val proxyTimeout = Runnable {
        if (hidDevice == null && proxyRequested && !stopped) onUnsupported()
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
            if (profile != BluetoothProfile.HID_DEVICE || stopped) return
            releaseProfile()
            if (adapter?.isEnabled == true) {
                // The Bluetooth service restarted. Try again in a moment.
                onStatus(HidStatus.Starting)
                mainHandler.postDelayed({ start() }, 1_000)
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@ClassicHidLink.registered = registered
            if (stopped) return
            if (!registered) {
                host = null
                if (hidDevice != null) {
                    onStatus(HidStatus.Unavailable("Android stopped the keyboard. Reopen the app to restart it."))
                }
                return
            }
            onStatus(HidStatus.Ready)
            // pluggedDevice is a host we were connected to before. If Android
            // doesn't give us one, try the last device we connected to.
            val target = pluggedDevice ?: savedHost()
            if (target != null) connect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            device ?: return
            if (stopped) return
            Log.d(TAG, "connection state ${device.address} -> $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTING ->
                    onStatus(HidStatus.Connecting(device.displayName()))

                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString(KEY_LAST_HOST, device.address).apply()
                    onStatus(HidStatus.Connected(device.displayName(), device.address))
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val current = host
                    if (current == null || current.address == device.address) {
                        host = null
                        if (this@ClassicHidLink.registered) onStatus(HidStatus.Ready)
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

    private companion object {
        const val TAG = "ClassicHidLink"
        const val SDP_NAME = "BT Phoneboard"
        const val SDP_DESCRIPTION = "Phone keyboard and trackpad"
        const val SDP_PROVIDER = "BT Phoneboard"
        const val KEY_LAST_HOST = "last_host_address"
        const val PROXY_TIMEOUT_MS = 5_000L
    }
}

/** The device's Bluetooth name, or its address if the name isn't available. */
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.displayName(): String =
    try {
        name
    } catch (e: SecurityException) {
        null
    } ?: address
