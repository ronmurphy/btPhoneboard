package com.ronmurphy.btphoneboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ronmurphy.btphoneboard.ui.PhoneboardScreen
import com.ronmurphy.btphoneboard.ui.theme.PhoneboardTheme

/**
 * The app's only screen. It does the things that need an Activity (asking for
 * permissions, opening system screens) and hands everything else to the
 * ViewModel and the Compose UI.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: PhoneboardViewModel by viewModels()

    /** True when the user tapped "Allow", false for the automatic ask at startup. */
    private var permissionRequestFromUser = false

    // registerForActivityResult must be set up before the Activity starts, which
    // is why it's a property and not created inside a click handler.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            // If the user denied it before, Android stops showing the dialog and
            // shouldShowRequestPermissionRationale returns false. Then the only
            // way is the app's page in Settings.
            if (!granted && permissionRequestFromUser &&
                !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                openAppSettings()
            }
            viewModel.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Stay awake while the keyboard is on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContent {
            PhoneboardTheme {
                PhoneboardScreen(
                    viewModel = viewModel,
                    onRequestPermission = { requestBluetoothPermissions(fromUser = true) },
                    onEnableBluetooth = ::requestEnableBluetooth,
                    onMakeDiscoverable = ::requestDiscoverable,
                    onOpenBluetoothSettings = { safeStartActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onExit = ::finish,
                )
            }
        }

        // savedInstanceState is null on a fresh launch (not after a rotation).
        if (savedInstanceState == null && !viewModel.hasPermission()) {
            requestBluetoothPermissions(fromUser = false)
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers coming back from Settings after granting the permission or
        // turning Bluetooth on. start() does nothing if already running.
        viewModel.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs can bring the system bars back; hide them again.
        if (hasFocus) hideSystemBars()
    }

    /** Full screen: the status and navigation bars slide in only when you swipe from an edge. */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun requestBluetoothPermissions(fromUser: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            viewModel.start() // Android 11 and older grant these at install time
            return
        }
        permissionRequestFromUser = fromUser
        permissionLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
        )
    }

    // These system dialogs need the Bluetooth permission, which the UI only
    // offers once it's been granted.
    @SuppressLint("MissingPermission")
    private fun requestEnableBluetooth() {
        safeStartActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoverable() {
        // Visible to other devices for 2 minutes, so the TV can find the phone.
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        safeStartActivity(intent)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        safeStartActivity(intent)
        Toast.makeText(this, "Allow \"Nearby devices\" under Permissions", Toast.LENGTH_LONG).show()
    }

    private fun safeStartActivity(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "This phone can't open that screen", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth permission is needed first", Toast.LENGTH_SHORT).show()
        }
    }
}
