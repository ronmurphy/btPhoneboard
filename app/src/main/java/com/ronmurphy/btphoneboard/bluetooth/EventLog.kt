package com.ronmurphy.btphoneboard.bluetooth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, human-readable list of recent Bluetooth events, shown in the
 * Devices dialog. When something doesn't work, a screenshot of this list says
 * a lot more than "it got stuck", because without a computer attached there's
 * no other way to see what happened.
 *
 * `object` makes this a singleton: there's exactly one log for the whole app.
 */
object EventLog {
    private const val MAX_LINES = 60

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // @Synchronized lets callbacks on different threads add lines safely.
    @Synchronized
    fun add(message: String) {
        Log.d("Phoneboard", message) // also visible with `adb logcat` if a computer is attached
        _lines.value = (_lines.value + "${timeFormat.format(Date())}  $message").takeLast(MAX_LINES)
    }
}
