package com.ronmurphy.btphoneboard.hid

import java.util.EnumMap

/** Where finished reports go. In the app this is the Bluetooth connection; in tests it's a list. */
fun interface ReportSink {
    fun send(reportId: Int, data: ByteArray)
}

/**
 * How an on-screen modifier key behaves. Tapping cycles OFF -> ONCE -> LOCKED -> OFF.
 *  - ONCE:   applies to the next key (or click), then turns itself off.
 *  - LOCKED: applies to every key until you tap it again.
 */
enum class ModState { OFF, ONCE, LOCKED }

/**
 * Turns touch events ("key A went down", "finger moved 3.2 px") into HID reports.
 *
 * Everything here is plain Kotlin with no Android code, so it can be unit
 * tested on any computer. See app/src/test for the tests.
 *
 * Modifiers are only sent *together with* a key or a mouse button. Tapping
 * Shift on its own sends nothing; pressing "a" afterwards sends Shift+A. This
 * avoids surprises like a lone Meta tap opening the Start menu on a PC.
 */
class InputModel(private val sink: ReportSink) {

    /** Keys currently held down, in the order they were pressed. */
    private val pressedKeys = LinkedHashSet<Int>()
    private val modifierStates = EnumMap<ModifierKey, ModState>(ModifierKey::class.java).apply {
        ModifierKey.entries.forEach { put(it, ModState.OFF) }
    }
    private var mouseButtons = 0
    private var lastKeyboardReport = ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE)

    // Touch movement comes in fractions of a pixel, but HID only sends whole
    // numbers. We keep the leftover fraction so slow movements still add up.
    private var leftoverX = 0f
    private var leftoverY = 0f
    private var leftoverScroll = 0f

    /** Called whenever a modifier changes state, so the UI can highlight it. */
    var onModifiersChanged: ((Map<ModifierKey, ModState>) -> Unit)? = null

    val modifiers: Map<ModifierKey, ModState>
        get() = modifierStates.toMap()

    // ---------------------------------------------------------------- keyboard

    fun toggleModifier(key: ModifierKey) {
        modifierStates[key] = when (modifierStates.getValue(key)) {
            ModState.OFF -> ModState.ONCE
            ModState.ONCE -> ModState.LOCKED
            ModState.LOCKED -> ModState.OFF
        }
        notifyModifiers()
        sendKeyboardIfChanged()
    }

    fun keyDown(usage: Int) {
        // A HID keyboard report has room for 6 keys; ignore a 7th finger.
        if (pressedKeys.size >= MAX_KEYS) return
        pressedKeys.add(usage)
        sendKeyboardIfChanged()
    }

    fun keyUp(usage: Int) {
        pressedKeys.remove(usage)
        clearOneShotModifiersIfIdle()
        sendKeyboardIfChanged()
    }

    // ------------------------------------------------------------------- mouse

    fun mouseButtonDown(button: Int) {
        mouseButtons = mouseButtons or button
        sendKeyboardIfChanged() // so Shift/Ctrl + click works
        sendMouse(0, 0, 0)
    }

    fun mouseButtonUp(button: Int) {
        mouseButtons = mouseButtons and button.inv()
        sendMouse(0, 0, 0)
        clearOneShotModifiersIfIdle()
        sendKeyboardIfChanged()
    }

    fun click(button: Int) {
        mouseButtonDown(button)
        mouseButtonUp(button)
    }

    /** Move the pointer by (dx, dy). Positive x is right, positive y is down. */
    fun moveMouse(dx: Float, dy: Float) {
        leftoverX += dx
        leftoverY += dy
        var x = leftoverX.toInt() // toInt() rounds toward zero
        var y = leftoverY.toInt()
        leftoverX -= x
        leftoverY -= y
        // Each report can only move -127..127, so split big jumps into steps.
        while (x != 0 || y != 0) {
            val stepX = x.coerceIn(-MAX_DELTA, MAX_DELTA)
            val stepY = y.coerceIn(-MAX_DELTA, MAX_DELTA)
            sendMouse(stepX, stepY, 0)
            x -= stepX
            y -= stepY
        }
    }

    /**
     * Scroll by a number of wheel "notches". Positive scrolls up (away from you),
     * the way a mouse wheel reports it.
     */
    fun scroll(notches: Float) {
        leftoverScroll += notches
        var n = leftoverScroll.toInt()
        leftoverScroll -= n
        while (n != 0) {
            val step = n.coerceIn(-MAX_DELTA, MAX_DELTA)
            sendMouse(0, 0, step)
            n -= step
        }
    }

    // ------------------------------------------------------------- media keys

    fun mediaKeyDown(usage: Int) {
        sink.send(
            HidDescriptor.CONSUMER_REPORT_ID,
            // 16-bit number, little-endian: low byte first, then high byte.
            byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()),
        )
    }

    fun mediaKeyUp() {
        sink.send(HidDescriptor.CONSUMER_REPORT_ID, ByteArray(HidDescriptor.CONSUMER_REPORT_SIZE))
    }

    // ---------------------------------------------------------------- helpers

    /** Builds the 8-byte keyboard report from the current state. */
    fun buildKeyboardReport(): ByteArray {
        val report = ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE)
        if (pressedKeys.isNotEmpty() || mouseButtons != 0) {
            report[0] = activeModifierBits().toByte()
        }
        // report[1] stays 0 (reserved)
        pressedKeys.forEachIndexed { index, usage -> report[2 + index] = usage.toByte() }
        return report
    }

    private fun activeModifierBits(): Int =
        modifierStates.entries
            .filter { it.value != ModState.OFF }
            .fold(0) { bits, entry -> bits or entry.key.bit }

    private fun sendKeyboardIfChanged() {
        val report = buildKeyboardReport()
        if (!report.contentEquals(lastKeyboardReport)) {
            lastKeyboardReport = report
            sink.send(HidDescriptor.KEYBOARD_REPORT_ID, report)
        }
    }

    private fun sendMouse(dx: Int, dy: Int, wheel: Int) {
        sink.send(
            HidDescriptor.MOUSE_REPORT_ID,
            byteArrayOf(mouseButtons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()),
        )
    }

    /** Once every key and button is released, ONCE modifiers have been used up. */
    private fun clearOneShotModifiersIfIdle() {
        if (pressedKeys.isNotEmpty() || mouseButtons != 0) return
        var changed = false
        for (entry in modifierStates.entries) {
            if (entry.value == ModState.ONCE) {
                entry.setValue(ModState.OFF)
                changed = true
            }
        }
        if (changed) notifyModifiers()
    }

    private fun notifyModifiers() {
        onModifiersChanged?.invoke(modifiers)
    }

    private companion object {
        const val MAX_KEYS = 6
        const val MAX_DELTA = 127
    }
}
