package com.ronmurphy.btphoneboard.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ronmurphy.btphoneboard.hid.ModState
import com.ronmurphy.btphoneboard.hid.ModifierKey
import com.ronmurphy.btphoneboard.ui.theme.PhoneboardTheme

/**
 * The on-screen keyboard. It holds no state of its own: it's told which
 * modifiers are on and whether Fn is active, and reports touches through the
 * callbacks. That makes it easy to preview (see the bottom of this file).
 */
@Composable
fun Keyboard(
    modifierStates: Map<ModifierKey, ModState>,
    fnActive: Boolean,
    onKeyDown: (usage: Int) -> Unit,
    onKeyUp: (usage: Int) -> Unit,
    onModifierTap: (ModifierKey) -> Unit,
    onFnTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shifted = modifierStates[ModifierKey.SHIFT] != ModState.OFF

    Column(modifier) {
        for (row in KeyboardLayout.rows) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (spec in row) {
                    // With Fn on, keys that have an Fn version swap to it.
                    val key = if (fnActive) spec.fnKey ?: spec else spec
                    val label = if (shifted) key.shiftLabel ?: key.label else key.label
                    val keyModifier = Modifier.weight(key.width)

                    when (val action = key.action) {
                        is KeyAction.Press -> KeyCap(
                            label = label,
                            modifier = keyModifier,
                            onDown = { onKeyDown(action.usage) },
                            onUp = { onKeyUp(action.usage) },
                        )

                        is KeyAction.Toggle -> KeyCap(
                            label = label,
                            modifier = keyModifier,
                            highlight = modifierStates[action.modifier] ?: ModState.OFF,
                            onDown = { onModifierTap(action.modifier) },
                        )

                        KeyAction.Fn -> KeyCap(
                            label = label,
                            modifier = keyModifier,
                            highlight = if (fnActive) ModState.LOCKED else ModState.OFF,
                            onDown = onFnTap,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single key. [onDown] runs when a finger touches it and [onUp] when the
 * finger lifts, so holding a key holds it on the TV too (and the TV repeats
 * it, just like a real keyboard).
 *
 * Each KeyCap handles its own touches, so you can hold one key with one finger
 * and press another with a second finger (for example Up while holding Del).
 */
@Composable
fun KeyCap(
    label: String,
    modifier: Modifier = Modifier,
    highlight: ModState = ModState.OFF,
    onDown: () -> Unit = {},
    onUp: () -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current

    // pointerInput(Unit) below starts once and keeps running, so it would keep
    // calling the *first* onDown/onUp it saw. rememberUpdatedState gives it a
    // way to always read the latest ones (e.g. after Fn changes "1" to "F1").
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)

    val colors = MaterialTheme.colorScheme
    val background = when {
        pressed -> colors.primary
        highlight == ModState.LOCKED -> colors.tertiary
        highlight == ModState.ONCE -> colors.primaryContainer
        else -> colors.surfaceVariant
    }
    val textColor = when {
        pressed -> colors.onPrimary
        highlight == ModState.LOCKED -> colors.onTertiary
        highlight == ModState.ONCE -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Remember which release action belongs to *this* press.
                        // If Fn is toggled while the key is held, we still
                        // release the key that was actually pressed.
                        val release = currentOnUp
                        pressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnDown()
                        tryAwaitRelease() // suspends until the finger lifts
                        pressed = false
                        release()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = if (label.length > 1) 13.sp else 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// Android Studio shows this in the "Split" or "Design" view of this file, so
// you can see the layout without running the app on a phone.
@Preview(widthDp = 700, heightDp = 330)
@Composable
private fun KeyboardPreview() {
    PhoneboardTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Keyboard(
                modifierStates = mapOf(ModifierKey.SHIFT to ModState.ONCE),
                fnActive = false,
                onKeyDown = {},
                onKeyUp = {},
                onModifierTap = {},
                onFnTap = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
