package com.ronmurphy.btphoneboard.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ronmurphy.btphoneboard.PhoneboardViewModel
import com.ronmurphy.btphoneboard.bluetooth.HidStatus
import com.ronmurphy.btphoneboard.bluetooth.HostDevice
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

    if (showDevices) {
        // remember {} with no key runs once each time the dialog opens, so the
        // list is fresh if you paired something new in the meantime.
        val devices = remember { viewModel.bondedDevices() }
        DevicesDialog(
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
            onMakeDiscoverable = onMakeDiscoverable,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            onDismiss = { showDevices = false },
        )
    }
}

@Composable
private fun TopBar(
    status: HidStatus,
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
    devices: List<HostDevice>,
    connectedAddress: String?,
    onConnect: (HostDevice) -> Unit,
    onDisconnect: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to a TV or tablet") },
        text = {
            // A scrolling Column, not a LazyColumn: lazy lists inside dialogs
            // can crash because dialogs measure their content in a special way.
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    "To pair a new device, tap \"Make discoverable\", then on the TV open " +
                        "Settings → Remotes & Accessories → Pair accessory and pick this phone. " +
                        "If the TV doesn't find it, pair from your phone's Bluetooth settings " +
                        "instead, then come back and pick the TV here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            Row {
                if (connectedAddress != null) {
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                TextButton(onClick = onOpenBluetoothSettings) { Text("Bluetooth settings") }
                TextButton(onClick = onMakeDiscoverable) { Text("Make discoverable") }
            }
        },
    )
}

private fun statusText(status: HidStatus): String = when (status) {
    HidStatus.Starting -> "Starting…"
    HidStatus.NeedsPermission -> "Needs the Nearby devices permission"
    HidStatus.BluetoothOff -> "Bluetooth is off"
    is HidStatus.Unavailable -> status.reason
    HidStatus.Ready -> "Not connected. Tap Devices"
    is HidStatus.Connecting -> "Connecting to ${status.deviceName}…"
    is HidStatus.Connected -> status.deviceName
}

private fun statusColor(status: HidStatus): Color = when (status) {
    is HidStatus.Connected -> Color(0xFF4CAF50)  // green
    is HidStatus.Connecting, HidStatus.Starting -> Color(0xFFFFC107) // amber
    HidStatus.Ready -> Color(0xFF9E9E9E)         // grey
    else -> Color(0xFFF44336)                    // red: something needs fixing
}

/** 1.0 -> "1", 1.5 -> "1.5" */
private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
