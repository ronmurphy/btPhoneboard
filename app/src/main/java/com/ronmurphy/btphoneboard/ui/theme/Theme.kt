package com.ronmurphy.btphoneboard.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Always dark, since you'll often use this in a dim TV room. On Android 12+
 * the colors come from your wallpaper ("Material You"); older phones get the
 * default dark palette.
 */
@Composable
fun PhoneboardTheme(content: @Composable () -> Unit) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        darkColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
