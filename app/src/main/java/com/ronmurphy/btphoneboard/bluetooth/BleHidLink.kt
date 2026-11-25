package com.ronmurphy.btphoneboard.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.ronmurphy.btphoneboard.hid.HidDescriptor
import com.ronmurphy.btphoneboard.hid.mergeMouseReports
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

/**
 * Bluetooth Low Energy keyboard + mouse, also called "HID over GATT".
 *
 * Classic mode asks Android to be a keyboard for us. Here we build the keyboard
 * ourselves out of general BLE features:
 *
 *  - A **GATT server**: a small database the tablet reads. It's organised into
 *    *services* (groups) holding *characteristics* (values). The HID service
 *    holds our report descriptor (the very same bytes as Classic mode) and one
 *    "Report" characteristic per report: keyboard, mouse and media keys.
 *  - **Advertising**: radio broadcasts saying "I'm a HID device, connect to
 *    me". This is what puts the phone in the tablet's "Pair new device" list.
 *  - **Notifications**: every key press becomes a new value of a Report
 *    characteristic, pushed to the tablet.
 *
 * The tablet must pair (bond) with the phone before it can read anything,
 * because the HID values are marked as needing an encrypted connection.
 *
 * Android delivers GATT server callbacks on a background thread. Each callback
 * immediately hands its work to the main thread (mainHandler.post), so all the
 * state below is only touched from one thread.
 */
@SuppressLint("MissingPermission")
class BleHidLink(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onStatus: (HidStatus) -> Unit,
) : HidLink {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var server: BluetoothGattServer? = null
    private var stopped = false
    private var servicesReady = false
    private var advertising = false
    private var advertiseWithName = true

    // ------------------------------------------------------- the GATT database

    // What to answer when the tablet reads a characteristic or descriptor.
    // IdentityHashMap compares objects by identity, because all our Report
    // characteristics share one UUID and only differ by which object they are.
    private val characteristicValues = IdentityHashMap<BluetoothGattCharacteristic, ByteArray>()
    private val descriptorValues = IdentityHashMap<BluetoothGattDescriptor, ByteArray>()

    private val keyboardInput = inputReport(HidDescriptor.KEYBOARD_REPORT_ID, HidDescriptor.KEYBOARD_REPORT_SIZE)
    private val mouseInput = inputReport(HidDescriptor.MOUSE_REPORT_ID, HidDescriptor.MOUSE_REPORT_SIZE)
    private val mediaInput = inputReport(HidDescriptor.CONSUMER_REPORT_ID, HidDescriptor.CONSUMER_REPORT_SIZE)
    private val inputReports = mapOf(
        HidDescriptor.KEYBOARD_REPORT_ID to keyboardInput,
        HidDescriptor.MOUSE_REPORT_ID to mouseInput,
        HidDescriptor.CONSUMER_REPORT_ID to mediaInput,
    )

    private val protocolMode = characteristic(
        UUID_PROTOCOL_MODE,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        READ_ENCRYPTED or WRITE_ENCRYPTED,
        value = byteArrayOf(0x01), // 1 = "report protocol" (the normal mode)
    )

    private val batteryLevel = BluetoothGattCharacteristic(
        UUID_BATTERY_LEVEL,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
    ).apply { addDescriptor(clientConfigDescriptor()) }

    /** Services are added one at a time: Android rejects a new one until the last is done. */
    private val pendingServices = ArrayDeque<BluetoothGattService>()

    private fun buildServices(): List<BluetoothGattService> {
        val deviceInfo = BluetoothGattService(UUID_DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        deviceInfo.addCharacteristic(readOnly(UUID_MANUFACTURER_NAME, "BT Phoneboard".toByteArray(), encrypted = false))
        // PnP ID: vendor ID source 1 (Bluetooth SIG), vendor 0xFFFF (reserved
        // for testing), product 0x0001, version 0x0001. All little-endian.
        deviceInfo.addCharacteristic(
            readOnly(UUID_PNP_ID, byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0x01, 0x00, 0x01, 0x00), encrypted = false),
        )

        val battery = BluetoothGattService(UUID_BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        battery.addCharacteristic(batteryLevel)

        val hid = BluetoothGattService(UUID_HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // HID Information: HID version 1.11, no country code, flags = "normally connectable".
        hid.addCharacteristic(readOnly(UUID_HID_INFORMATION, byteArrayOf(0x11, 0x01, 0x00, 0x02), encrypted = true))
        hid.addCharacteristic(readOnly(UUID_REPORT_MAP, HidDescriptor.BYTES, encrypted = true))
        hid.addCharacteristic(protocolMode)
        hid.addCharacteristic(
            characteristic(UUID_HID_CONTROL_POINT, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, WRITE_ENCRYPTED),
        )
        hid.addCharacteristic(keyboardInput)
        hid.addCharacteristic(mouseInput)
        hid.addCharacteristic(mediaInput)
        hid.addCharacteristic(outputReport(HidDescriptor.KEYBOARD_REPORT_ID))

        return listOf(deviceInfo, battery, hid)
    }

    private fun characteristic(uuid: UUID, properties: Int, permissions: Int, value: ByteArray? = null) =
        BluetoothGattCharacteristic(uuid, properties, permissions).also { c ->
            if (value != null) characteristicValues[c] = value
        }

    private fun readOnly(uuid: UUID, value: ByteArray, encrypted: Boolean) = characteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ,
        if (encrypted) READ_ENCRYPTED else BluetoothGattCharacteristic.PERMISSION_READ,
        value,
    )

    /**
     * An input report: something we send to the tablet. It has two descriptors:
     * the "client config" the tablet writes to turn notifications on, and a
     * "report reference" saying which report ID this characteristic carries.
     */
    private fun inputReport(reportId: Int, size: Int) = characteristic(
        UUID_REPORT,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        READ_ENCRYPTED,
        value = ByteArray(size),
    ).apply {
        addDescriptor(clientConfigDescriptor())
        addDescriptor(reportReference(reportId, REPORT_TYPE_INPUT))
    }

    /** An output report: the tablet sends us the keyboard lights (Caps Lock etc.). */
    private fun outputReport(reportId: Int) = characteristic(
        UUID_REPORT,
        BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        READ_ENCRYPTED or WRITE_ENCRYPTED,
        value = ByteArray(1),
    ).apply {
        addDescriptor(reportReference(reportId, REPORT_TYPE_OUTPUT))
    }

    private fun reportReference(reportId: Int, type: Int) =
        BluetoothGattDescriptor(UUID_REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED).also {
            descriptorValues[it] = byteArrayOf(reportId.toByte(), type.toByte())
        }

    private fun clientConfigDescriptor() = BluetoothGattDescriptor(
        UUID_CLIENT_CONFIG,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )

    // ------------------------------------------------------ connection state

    private val connectedDevices = LinkedHashMap<String, BluetoothDevice>()

    /** For each connected device, the characteristics it turned notifications on for. */
    private val subscriptions = HashMap<String, MutableSet<BluetoothGattCharacteristic>>()

    private class Pending(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
        var value: ByteArray,
        var attempts: Int = 0,
    )

    /** Reports waiting to go out. Android sends one notification at a time. */
    private val sendQueue = ArrayDeque<Pending>()
    private var sending = false

    // ------------------------------------------------------------ public API

    override fun start() {
        if (stopped || server != null) return
        val manager = manager
        if (manager == null || adapter?.bluetoothLeAdvertiser == null) {
            onStatus(HidStatus.Unavailable("This phone can't act as a Bluetooth keyboard in Classic or BLE mode."))
            return
        }
        val gattServer = manager.openGattServer(context, gattCallback)
        if (gattServer == null) {
            onStatus(HidStatus.Unavailable("Couldn't start the BLE keyboard. Try turning Bluetooth off and on."))
            return
        }
        server = gattServer
        onStatus(HidStatus.Starting)
        pendingServices.clear()
        pendingServices.addAll(buildServices())
        addNextService()
    }

    /**
     * In BLE mode the tablet connects to us, not the other way round. A tablet
     * that has paired before reconnects on its own while we're advertising, so
     * "connecting" just makes sure we're advertising.
     */
    override fun connect(address: String) {
        updateStatus()
    }

    override fun disconnect() {
        val host = currentHost() ?: return
        forgetHost(host.address)
        server?.cancelConnection(host)
    }

    override fun sendReport(reportId: Int, data: ByteArray) {
        val characteristic = inputReports[reportId] ?: return
        characteristicValues[characteristic] = data
        for (device in connectedDevices.values) {
            if (isSubscribed(device, characteristic)) enqueue(Pending(device, characteristic, data))
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        stopAdvertising()
        mainHandler.removeCallbacksAndMessages(null)
        val s = server
        server = null
        if (s != null) {
            for (device in connectedDevices.values) s.cancelConnection(device)
            s.close()
        }
        connectedDevices.clear()
        subscriptions.clear()
        sendQueue.clear()
    }

    // ------------------------------------------------------------ setup steps

    private fun addNextService() {
        val s = server ?: return
        val next = pendingServices.removeFirstOrNull()
        if (next == null) {
            servicesReady = true
            updateStatus()
            return
        }
        // The answer arrives in gattCallback.onServiceAdded, which calls us again.
        if (!s.addService(next)) {
            onStatus(HidStatus.Unavailable("Couldn't set up the BLE keyboard service."))
        }
    }

    /** Advertise while nobody is connected; stop once a tablet is using us. */
    private fun updateStatus() {
        if (stopped || !servicesReady) return
        val host = currentHost()
        if (host != null) {
            stopAdvertising()
            onStatus(HidStatus.Connected(host.displayName(), host.address))
        } else {
            startAdvertising()
            onStatus(HidStatus.Advertising(phoneName()))
        }
    }

    private fun startAdvertising() {
        if (advertising || stopped) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0) // keep going until we stop it
            .build()
        // An advertisement holds only 31 bytes, so the HID service ID goes in
        // the advertisement and the phone's name in the "scan response" (a
        // second packet the tablet asks for when it sees us).
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID_HID_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(advertiseWithName)
            .build()
        advertising = true
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: IllegalStateException) {
            // Bluetooth is already off.
        }
    }

    // --------------------------------------------------------- notifications

    private fun enqueue(item: Pending) {
        val last = sendQueue.lastOrNull()
        val merged = if (
            last != null &&
            item.characteristic === mouseInput &&
            last.characteristic === mouseInput &&
            last.device.address == item.device.address
        ) {
            mergeMouseReports(last.value, item.value)
        } else {
            null
        }

        if (last != null && merged != null) {
            last.value = merged
        } else {
            if (sendQueue.size >= MAX_QUEUE) sendQueue.removeFirst() // the tablet stopped listening
            sendQueue.addLast(item)
        }
        if (!sending) sendNext()
    }

    private fun sendNext() {
        val s = server
        val item = sendQueue.removeFirstOrNull()
        if (s == null || item == null) {
            sending = false
            return
        }
        sending = true
        if (notify(s, item)) {
            // Normally onNotificationSent arrives within a few milliseconds.
            // The timeout keeps things moving if it never does.
            mainHandler.postDelayed(notificationTimeout, NOTIFICATION_TIMEOUT_MS)
        } else {
            // The Bluetooth stack was busy. Try this report again shortly so a
            // key release isn't lost (which would leave a key stuck down).
            item.attempts++
            if (item.attempts < MAX_ATTEMPTS) sendQueue.addFirst(item)
            mainHandler.postDelayed({ sendNext() }, RETRY_DELAY_MS)
        }
    }

    private val notificationTimeout = Runnable { sendNext() }

    private fun onNotificationDone() {
        mainHandler.removeCallbacks(notificationTimeout)
        sendNext()
    }

    private fun notify(s: BluetoothGattServer, item: Pending): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(item.device, item.characteristic, false, item.value) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            notifyBeforeAndroid13(s, item)
        }

    // Android 12 and older store the value on the characteristic object first.
    @Suppress("DEPRECATION")
    private fun notifyBeforeAndroid13(s: BluetoothGattServer, item: Pending): Boolean {
        item.characteristic.value = item.value
        return s.notifyCharacteristicChanged(item.device, item.characteristic, false)
    }

    // ---------------------------------------------------------------- hosts

    /** The connected device that's actually using us as a keyboard, if any. */
    private fun currentHost(): BluetoothDevice? =
        connectedDevices.values.firstOrNull { device -> inputReports.values.any { isSubscribed(device, it) } }

    private fun isSubscribed(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic?): Boolean =
        characteristic != null && subscriptions[device.address]?.contains(characteristic) == true

    private fun setSubscribed(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        characteristic ?: return
        val set = subscriptions.getOrPut(device.address) { identitySet() }
        if (enabled) set.add(characteristic) else set.remove(characteristic)
        if (enabled && characteristic in inputReports.values) rememberHost(device.address)
        updateStatus()
    }

    private fun onConnectionChanged(device: BluetoothDevice, newState: Int) {
        if (stopped) return
        Log.d(TAG, "connection ${device.address} -> $newState")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                connectedDevices[device.address] = device
                // Other things (a smartwatch, say) can connect to the phone too,
                // so a device only counts as our host once it turns on
                // notifications. A tablet that paired with us before may skip
                // that step on reconnect, because BLE devices are supposed to
                // remember it. So we remember it for them.
                if (device.address in knownHosts() && device.bondState == BluetoothDevice.BOND_BONDED) {
                    subscriptions[device.address] = identitySet().apply { addAll(inputReports.values) }
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                connectedDevices.remove(device.address)
                subscriptions.remove(device.address)
                sendQueue.removeAll { it.device.address == device.address }
            }
        }
        updateStatus()
    }

    private fun knownHosts(): Set<String> = prefs.getStringSet(KEY_KNOWN_HOSTS, emptySet()).orEmpty()

    private fun rememberHost(address: String) {
        val known = knownHosts()
        if (address !in known) prefs.edit().putStringSet(KEY_KNOWN_HOSTS, known + address).apply()
    }

    private fun forgetHost(address: String) {
        prefs.edit().putStringSet(KEY_KNOWN_HOSTS, knownHosts() - address).apply()
    }

    private fun phoneName(): String =
        try {
            adapter?.name
        } catch (e: SecurityException) {
            null
        } ?: "this phone"

    private fun batteryPercent(): Int {
        val level = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        return level.coerceIn(0, 100)
    }

    private fun identitySet(): MutableSet<BluetoothGattCharacteristic> =
        Collections.newSetFromMap(IdentityHashMap())

    /** Answers a read, starting at [offset] (long values are read in pieces). */
    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        val s = server ?: return
        if (offset > value.size) {
            s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, ByteArray(0))
        } else {
            s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    private fun acknowledge(device: BluetoothDevice, requestId: Int) {
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
    }

    // ------------------------------------------------------------- callbacks

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            mainHandler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    addNextService()
                } else {
                    onStatus(HidStatus.Unavailable("Couldn't set up the BLE keyboard (error $status)."))
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            mainHandler.post { onConnectionChanged(device, newState) }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            device ?: return
            mainHandler.post {
                val value = when {
                    characteristic == null -> ByteArray(0)
                    characteristic === batteryLevel -> byteArrayOf(batteryPercent().toByte())
                    else -> characteristicValues[characteristic] ?: ByteArray(0)
                }
                respond(device, requestId, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            device ?: return
            mainHandler.post {
                // Protocol mode is remembered. The keyboard lights (output
                // report) and suspend/resume (control point) need no action.
                if (characteristic === protocolMode && value != null && value.isNotEmpty()) {
                    characteristicValues[protocolMode] = byteArrayOf(value[0])
                }
                if (responseNeeded) acknowledge(device, requestId)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?,
        ) {
            device ?: return
            mainHandler.post {
                val value = when {
                    descriptor == null -> ByteArray(0)
                    descriptor.uuid == UUID_CLIENT_CONFIG ->
                        if (isSubscribed(device, descriptor.characteristic)) {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        }
                    else -> descriptorValues[descriptor] ?: ByteArray(0)
                }
                respond(device, requestId, offset, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            device ?: return
            mainHandler.post {
                // The tablet writes 01 00 to a "client config" descriptor to
                // turn notifications on, and 00 00 to turn them off.
                if (descriptor != null && descriptor.uuid == UUID_CLIENT_CONFIG) {
                    val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                    setSubscribed(device, descriptor.characteristic, enabled)
                }
                if (responseNeeded) acknowledge(device, requestId)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            device ?: return
            mainHandler.post { acknowledge(device, requestId) }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            mainHandler.post { onNotificationDone() }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            mainHandler.post {
                advertising = false
                when {
                    errorCode == ADVERTISE_FAILED_ALREADY_STARTED -> advertising = true
                    // A long phone name doesn't fit: advertise without it.
                    errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advertiseWithName -> {
                        advertiseWithName = false
                        updateStatus()
                    }
                    else -> onStatus(HidStatus.Unavailable("Couldn't start BLE advertising (error $errorCode)."))
                }
            }
        }
    }

    private companion object {
        const val TAG = "BleHidLink"
        const val KEY_KNOWN_HOSTS = "ble_known_hosts"
        const val MAX_QUEUE = 200
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 15L
        const val NOTIFICATION_TIMEOUT_MS = 250L
        const val REPORT_TYPE_INPUT = 1
        const val REPORT_TYPE_OUTPUT = 2

        const val READ_ENCRYPTED = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        const val WRITE_ENCRYPTED = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED

        /** Standard Bluetooth 16-bit IDs, expanded to full 128-bit UUIDs. */
        fun uuid16(value: Int): UUID =
            UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", value))

        val UUID_HID_SERVICE: UUID = uuid16(0x1812)
        val UUID_HID_INFORMATION: UUID = uuid16(0x2A4A)
        val UUID_REPORT_MAP: UUID = uuid16(0x2A4B)
        val UUID_HID_CONTROL_POINT: UUID = uuid16(0x2A4C)
        val UUID_REPORT: UUID = uuid16(0x2A4D)
        val UUID_PROTOCOL_MODE: UUID = uuid16(0x2A4E)
        val UUID_REPORT_REFERENCE: UUID = uuid16(0x2908)
        val UUID_CLIENT_CONFIG: UUID = uuid16(0x2902)
        val UUID_DEVICE_INFO_SERVICE: UUID = uuid16(0x180A)
        val UUID_MANUFACTURER_NAME: UUID = uuid16(0x2A29)
        val UUID_PNP_ID: UUID = uuid16(0x2A50)
        val UUID_BATTERY_SERVICE: UUID = uuid16(0x180F)
        val UUID_BATTERY_LEVEL: UUID = uuid16(0x2A19)
    }
}
