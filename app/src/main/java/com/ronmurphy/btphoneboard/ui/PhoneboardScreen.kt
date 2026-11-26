package com.ronmurphy.btphoneboard.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ronmurphy.btphoneboard.PhoneboardViewModel
import com.ronmurphy.btphoneboard.bluetooth.EventLog
import com.ronmurphy.btphoneboard.bluetooth.HidStatus
import com.ronmurphy.btphoneboard.bluetooth.HostDevice
import com.ronmurphy.btphoneboard.bluetooth.LinkMode
import com.ronmurphy.btphoneboard.hid.MediaKeys
import com.ronmurphy.btphoneboard.hid.MouseButton

/** Media/navigation buttons shown across the top. Back and Home work on Android TV. */
private val MEDIA_BUTTONS = listOf(
    "Back" to MediaKeys.BACK,
    "Home" to MediaKeys.HOME,
    "Prev" to MediaKeys.PREVIOUS_TRACK,
    "Play" to MediaKeys.PLAY_PAUSE,
    "Next" to MediaKeys.NEXT_TRACK,
    "Vol−" to MediaKeys.VOLUME_DOWN,
    "Mute" to MediaKeys.MUTE,
    "Vol+" to MediaKeys.VOLUME_UP,
)

/**
 * The whole screen:
 *
 *   [status]  [Back Home Prev Play Next Vol− Mute Vol+]  [Speed] [Devices]
 *   [          keyboard           ] [   trackpad   ]
 *   [                             ] [ Left | Right ]
 */
@Composable
fun PhoneboardScreen(
    viewModel: PhoneboardViewModel,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onExit: () -> Unit,
) {
    // collectAsStateWithLifecycle turns each StateFlow into Compose state, so
    // the screen redraws whenever the value changes.
    val status by viewModel.status.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val modifierStates by viewModel.modifiers.collectAsStateWithLifecycle()
    val fnActive by viewModel.fnActive.collectAsStateWithLifecycle()
    val mouseSpeed by viewModel.mouseSpeed.collectAsStateWithLifecycle()
    var showDevices by rememberSaveable { mutableStateOf(false) }

    // The Back button (or an edge swipe) would close the app and drop the
    // connection, so it takes two presses to exit.
    val context = LocalContext.current
    var lastBackPress by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackPress < 2_000) {
            onExit()
        } else {
            lastBackPress = now
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(4.dp),
        ) {
            TopBar(
                status = status,
                mode = mode,
                mouseSpeed = mouseSpeed,
                onRequestPermission = onRequestPermission,
                onEnableBluetooth = onEnableBluetooth,
                onMediaDown = viewModel::mediaKeyDown,
                onMediaUp = viewModel::mediaKeyUp,
                onSpeedClick = viewModel::cycleMouseSpeed,
                onDevicesClick = { showDevices = true },
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 4.dp),
            ) {
                Keyboard(
                    modifierStates = modifierStates,
                    fnActive = fnActive,
                    onKeyDown = viewModel::keyDown,
                    onKeyUp = viewModel::keyUp,
                    onModifierTap = viewModel::toggleModifier,
                    onFnTap = viewModel::toggleFn,
                    modifier = Modifier
                        .weight(0.7f)
                        .fillMaxHeight(),
                )

                Column(
                    Modifier
                        .weight(0.3f)
                        .fillMaxHeight()
                        .padding(start = 6.dp),
                ) {
                    Trackpad(
                        onMove = viewModel::moveMouse,
                        onScroll = viewModel::scroll,
                        onTap = viewModel::tap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    // Hold Left and drag on the trackpad with another finger to
                    // select text or drag things.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(top = 4.dp),
                    ) {
                        KeyCap(
                            label = "Left",
                            modifier = Modifier.weight(1f),
                            onDown = { viewModel.mouseButtonDown(MouseButton.LEFT) },
                            onUp = { viewModel.mouseButtonUp(MouseButton.LEFT) },
                        )
                        KeyCap(
                            label = "Right",
                            modifier = Modifier.weight(1f),
                            onDown = { viewModel.mouseButtonDown(MouseButton.RIGHT) },
                            onUp = { viewModel.mouseButtonUp(MouseButton.RIGHT) },
                        )
                    }
                }
            }
        }
    }

    // While a tablet is pairing, explain where to accept it on the phone:
    // Android often puts the request in a notification, which is easy to miss
    // because this app hides the status bar.
    val pairing = status as? HidStatus.Pairing
    var pairingHelpDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pairing == null) {
        if (pairing == null) pairingHelpDismissed = false // ready for the next attempt
    }
    if (pairing != null && !pairingHelpDismissed) {
        PairingHelpDialog(pairing.deviceName, onDismiss = { pairingHelpDismissed = true })
    }

    if (showDevices) {
        // remember {} with no key runs once each time the dialog opens, so the
        // list is fresh if you paired something new in the meantime.
        val devices = remember { viewModel.bondedDevices() }
        val phoneName = remember { viewModel.phoneName() }
        DevicesDialog(
            mode = mode,
            phoneName = phoneName,
            devices = devices,
            connectedAddress = (status as? HidStatus.Connected)?.address,
            onConnect = { device ->
                viewModel.connect(device)
                showDevices = false
            },
            onDisconnect = {
                viewModel.disconnect()
                showDevices = false
            },
            onSwitchMode = {
                viewModel.setMode(if (mode == LinkMode.CLASSIC) LinkMode.BLE else LinkMode.CLASSIC)
                showDevices = false
            },
            onMakeDiscoverable = onMakeDiscoverable,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            onDismiss = { showDevices = false },
        )
    }
}

@Composable
private fun TopBar(
    status: HidStatus,
    mode: LinkMode,
    mouseSpeed: Float,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMediaDown: (Int) -> Unit,
    onMediaUp: () -> Unit,
    onSpeedClick: () -> Unit,
    onDevicesClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .padding(start = 6.dp)
                .size(10.dp)
                .background(statusColor(status), CircleShape),
        )
        // A small outlined tag saying which Bluetooth mode is in use.
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            text = statusText(status),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (status) {
            HidStatus.NeedsPermission -> TextButton(onClick = onRequestPermission) { Text("Allow") }
            HidStatus.BluetoothOff -> TextButton(onClick = onEnableBluetooth) { Text("Turn on") }
            else -> {}
        }
        for ((label, usage) in MEDIA_BUTTONS) {
            KeyCap(
                label = label,
                modifier = Modifier.width(52.dp),
                onDown = { onMediaDown(usage) },
                onUp = onMediaUp,
            )
        }
        TextButton(onClick = onSpeedClick) { Text("Speed ${formatSpeed(mouseSpeed)}×") }
        Button(onClick = onDevicesClick) { Text("Devices") }
    }
}

@Composable
private fun DevicesDialog(
    mode: LinkMode,
    phoneName: String,
    devices: List<HostDevice>,
    connectedAddress: String?,
    onConnect: (HostDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSwitchMode: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect a tablet or TV (${mode.label} mode)") },
        text = {
            // A scrolling Column, not a LazyColumn: lazy lists inside dialogs
            // can crash because dialogs measure their content in a special way.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (mode) {
                    LinkMode.CLASSIC -> ClassicDevicesContent(devices, connectedAddress, onConnect)
                    LinkMode.BLE -> BleDevicesContent(phoneName)
                }

                Spacer(Modifier.height(8.dp))
                if (connectedAddress != null) {
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                if (mode == LinkMode.CLASSIC) {
                    TextButton(onClick = onMakeDiscoverable) { Text("Make this phone discoverable") }
                }
                TextButton(onClick = onOpenBluetoothSettings) { Text("Open Bluetooth settings") }
                TextButton(onClick = onSwitchMode) {
                    Text(if (mode == LinkMode.CLASSIC) "Switch to BLE mode" else "Switch to Classic mode")
                }

                BluetoothLog()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ClassicDevicesContent(
    devices: List<HostDevice>,
    connectedAddress: String?,
    onConnect: (HostDevice) -> Unit,
) {
    if (devices.isEmpty()) {
        Text("No paired devices yet.")
    }
    for (device in devices) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onConnect(device) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(device.name, Modifier.weight(1f))
            if (device.address == connectedAddress) {
                Text("Connected", color = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider()
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "To pair a new device, tap \"Make this phone discoverable\", then on the TV open " +
            "Settings → Remotes & Accessories → Pair accessory and pick this phone. " +
            "If the TV doesn't find it, pair from your phone's Bluetooth settings " +
            "instead, then come back and pick the TV here.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun BleDevicesContent(phoneName: String) {
    Text("In BLE mode the tablet or TV connects to the phone, so pairing starts over there:")
    Spacer(Modifier.height(8.dp))
    Text(
        "1. On the tablet: Settings → Connected devices → Pair new device.\n" +
            "    On a TV: Settings → Remotes & Accessories → Pair accessory.\n" +
            "2. Pick \"$phoneName\" from the list.\n" +
            "3. Accept on both screens. If this phone shows no pop-up, swipe down from the top " +
            "(twice, since the app is full screen) and accept the Bluetooth pairing notification.",
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "After that it reconnects by itself whenever this app is open. If pairing fails, " +
            "remove (\"forget\") the phone on the tablet and the tablet on the phone, then try again.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun PairingHelpDialog(deviceName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pairing with $deviceName") },
        text = {
            Text(
                "1. On the tablet, check the code and tap Pair.\n\n" +
                    "2. On this phone, a pairing request should pop up. If it doesn't, swipe down " +
                    "from the top edge of the screen (twice, because this app is full screen) and " +
                    "tap Pair or Accept on the Bluetooth pairing notification.\n\n" +
                    "Be quick: the request times out after about half a minute.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

/** The recent Bluetooth events (see EventLog), hidden until you ask for them. */
@Composable
private fun BluetoothLog() {
    val lines by EventLog.lines.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide Bluetooth log" else "Show Bluetooth log")
    }
    if (expanded) {
        Text(
            text = if (lines.isEmpty()) "(nothing yet)" else lines.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun statusText(status: HidStatus): String = when (status) {
    HidStatus.Starting -> "Starting…"
    HidStatus.NeedsPermission -> "Needs the Nearby devices permission"
    HidStatus.BluetoothOff -> "Bluetooth is off"
    is HidStatus.Unavailable -> status.reason
    HidStatus.Ready -> "Not connected. Tap Devices"
    is HidStatus.Advertising -> "Waiting: pair \"${status.name}\" from your tablet or TV"
    is HidStatus.Pairing -> "Pairing with ${status.deviceName}: accept on both screens"
    is HidStatus.PairingFailed -> "Pairing with ${status.deviceName} didn't finish. Try again"
    is HidStatus.Connecting -> "Connecting to ${status.deviceName}…"
    is HidStatus.Connected -> status.deviceName
}

private fun statusColor(status: HidStatus): Color = when (status) {
    is HidStatus.Connected -> Color(0xFF4CAF50)  // green
    is HidStatus.Connecting, is HidStatus.Pairing, HidStatus.Starting -> Color(0xFFFFC107) // amber
    HidStatus.Ready, is HidStatus.Advertising -> Color(0xFF9E9E9E) // grey: waiting
    else -> Color(0xFFF44336)                    // red: something needs fixing
}

/** 1.0 -> "1", 1.5 -> "1.5" */
private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
