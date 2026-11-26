package com.ronmurphy.btphoneboard.bluetooth

/**
 * The two ways the phone can be a Bluetooth keyboard.
 *
 *  - CLASSIC uses Android's built-in BluetoothHidDevice feature (Android 9+).
 *    Simple, but some phone makers switch it off.
 *  - BLE builds a Bluetooth Low Energy keyboard ("HID over GATT") out of
 *    general BLE features that nearly every phone allows.
 */
enum class LinkMode(val label: String) {
    CLASSIC("Classic"),
    BLE("BLE"),
}

/** What the UI shows in the status bar. */
sealed interface HidStatus {
    data object Starting : HidStatus
    data object NeedsPermission : HidStatus
    data object BluetoothOff : HidStatus
    data class Unavailable(val reason: String) : HidStatus

    /** Classic mode: registered as a keyboard/mouse, but nothing is connected. */
    data object Ready : HidStatus

    /** BLE mode: broadcasting "I'm a keyboard called [name]" and waiting for a tablet. */
    data class Advertising(val name: String) : HidStatus

    /** A tablet started pairing; both screens need to confirm. */
    data class Pairing(val deviceName: String) : HidStatus

    /** The last pairing attempt didn't finish (cancelled, timed out, or rejected). */
    data class PairingFailed(val deviceName: String) : HidStatus

    data class Connecting(val deviceName: String) : HidStatus
    data class Connected(val deviceName: String, val address: String) : HidStatus
}

/** A paired device the user can pick in the Devices dialog. */
data class HostDevice(val name: String, val address: String)

/**
 * One way of being a Bluetooth keyboard. ClassicHidLink and BleHidLink both
 * implement this, so the rest of the app doesn't care which one is in use.
 * Each link reports its status through a callback given to its constructor.
 */
interface HidLink {
    fun start()

    /** Shuts the link down for good. A new link is created to start again. */
    fun stop()

    fun connect(address: String)
    fun disconnect()

    /** [data] is the report without the report ID byte. */
    fun sendReport(reportId: Int, data: ByteArray)
}
