package com.ronmurphy.btphoneboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * A laptop-style trackpad.
 *
 *  - 1 finger drag   moves the pointer   -> onMove(dx, dy) in pixels
 *  - 2 finger drag   scrolls             -> onScroll(dy) in dp
 *  - 1 finger tap    left click          -> onTap(1)
 *  - 2 finger tap    right click         -> onTap(2)
 *  - 3 finger tap    middle click        -> onTap(3)
 */
@Composable
fun Trackpad(
    onMove: (dx: Float, dy: Float) -> Unit,
    onScroll: (dyDp: Float) -> Unit,
    onTap: (fingers: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnScroll by rememberUpdatedState(onScroll)
    val currentOnTap by rememberUpdatedState(onTap)

    Box(
        modifier = modifier
            // Ask Android not to treat swipes here as the "back" edge gesture.
            .systemGestureExclusion()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                // awaitEachGesture runs this block once per gesture: from the
                // first finger down until the last finger lifts.
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val startTime = firstDown.uptimeMillis
                    var endTime = startTime
                    var maxFingers = 1
                    var travelled = 0f
                    var dragging = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.filter { it.pressed }
                        if (fingers.isEmpty()) {
                            endTime = event.changes.maxOf { it.uptimeMillis }
                            break
                        }
                        maxFingers = maxOf(maxFingers, fingers.size)

                        if (fingers.size == 1) {
                            val delta = fingers[0].positionChange()
                            travelled += delta.getDistance()
                            if (travelled > viewConfiguration.touchSlop) dragging = true
                            // After a two-finger scroll, ignore the last finger
                            // so the pointer doesn't jump as you lift off.
                            if (dragging && maxFingers == 1) currentOnMove(delta.x, delta.y)
                        } else {
                            val dy = fingers.map { it.positionChange().y }.average().toFloat()
                            travelled += abs(dy)
                            if (travelled > viewConfiguration.touchSlop) dragging = true
                            if (dragging) currentOnScroll(dy / density) // pixels -> dp
                        }
                        event.changes.forEach { it.consume() }
                    }

                    // A short touch that didn't move is a tap (a click).
                    if (!dragging && endTime - startTime < TAP_TIMEOUT_MS) {
                        currentOnTap(maxFingers)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Trackpad\n1 finger: move · tap: click\n2 fingers: scroll · tap: right-click",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private const val TAP_TIMEOUT_MS = 300L
