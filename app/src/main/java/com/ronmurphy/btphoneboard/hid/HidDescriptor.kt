package com.ronmurphy.btphoneboard.hid

/**
 * The HID *report descriptor*: a small list of bytes that tells the TV or tablet
 * what kind of device we are and how to read the reports we send it.
 *
 * Real keyboards and mice have one of these built into their firmware. We send
 * ours to Android when we register as a HID device, and Android hands it to
 * any host (TV, tablet, PC) that pairs with the phone. That is why the host
 * needs no special app: it just sees a normal keyboard + mouse.
 *
 * We describe three devices in one, each with its own report ID:
 *
 *   ID 1  keyboard  8 bytes: [modifiers, reserved, key1..key6]
 *   ID 2  mouse     4 bytes: [buttons, x, y, wheel]
 *   ID 3  media     2 bytes: one 16-bit "consumer control" usage, little-endian
 *
 * Each item below is a prefix byte followed by 0, 1 or 2 data bytes. The
 * reference for all of this is the USB "Device Class Definition for HID" and
 * the "HID Usage Tables" PDF on usb.org.
 */
object HidDescriptor {
    const val KEYBOARD_REPORT_ID = 1
    const val MOUSE_REPORT_ID = 2
    const val CONSUMER_REPORT_ID = 3

    /** Sizes of the reports we send, NOT counting the report ID byte. */
    const val KEYBOARD_REPORT_SIZE = 8
    const val MOUSE_REPORT_SIZE = 4
    const val CONSUMER_REPORT_SIZE = 2

    val BYTES: ByteArray = bytesOf(
        // ---------------- Keyboard (report ID 1) ----------------
        0x05, 0x01,                    // Usage Page (Generic Desktop)
        0x09, 0x06,                    // Usage (Keyboard)
        0xA1, 0x01,                    // Collection (Application)
        0x85, KEYBOARD_REPORT_ID,      //   Report ID (1)
        //   Byte 0: eight modifier keys, one bit each (Ctrl, Shift, Alt, Meta x left/right)
        0x05, 0x07,                    //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,                    //   Usage Minimum (Left Control)
        0x29, 0xE7,                    //   Usage Maximum (Right GUI)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1 bit)
        0x95, 0x08,                    //   Report Count (8)
        0x81, 0x02,                    //   Input (Data, Variable, Absolute)
        //   Byte 1: reserved, always zero
        0x75, 0x08,                    //   Report Size (8)
        0x95, 0x01,                    //   Report Count (1)
        0x81, 0x01,                    //   Input (Constant)
        //   Output report: the host tells us which LEDs (Caps Lock etc.) are on
        0x05, 0x08,                    //   Usage Page (LEDs)
        0x19, 0x01,                    //   Usage Minimum (Num Lock)
        0x29, 0x05,                    //   Usage Maximum (Kana)
        0x75, 0x01,                    //   Report Size (1)
        0x95, 0x05,                    //   Report Count (5)
        0x91, 0x02,                    //   Output (Data, Variable, Absolute)
        0x75, 0x03,                    //   Report Size (3)
        0x95, 0x01,                    //   Report Count (1)
        0x91, 0x01,                    //   Output (Constant) - pads LEDs to a full byte
        //   Bytes 2-7: up to six keys held down at once
        0x05, 0x07,                    //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,                    //   Usage Minimum (0)
        0x29, 0x65,                    //   Usage Maximum (0x65, Application key)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x65,                    //   Logical Maximum (0x65)
        0x75, 0x08,                    //   Report Size (8)
        0x95, 0x06,                    //   Report Count (6)
        0x81, 0x00,                    //   Input (Data, Array)
        0xC0,                          // End Collection

        // ---------------- Mouse (report ID 2) ----------------
        0x05, 0x01,                    // Usage Page (Generic Desktop)
        0x09, 0x02,                    // Usage (Mouse)
        0xA1, 0x01,                    // Collection (Application)
        0x85, MOUSE_REPORT_ID,         //   Report ID (2)
        0x09, 0x01,                    //   Usage (Pointer)
        0xA1, 0x00,                    //   Collection (Physical)
        //     Byte 0: three buttons (left, right, middle) + 5 padding bits
        0x05, 0x09,                    //     Usage Page (Button)
        0x19, 0x01,                    //     Usage Minimum (Button 1)
        0x29, 0x03,                    //     Usage Maximum (Button 3)
        0x15, 0x00,                    //     Logical Minimum (0)
        0x25, 0x01,                    //     Logical Maximum (1)
        0x75, 0x01,                    //     Report Size (1)
        0x95, 0x03,                    //     Report Count (3)
        0x81, 0x02,                    //     Input (Data, Variable, Absolute)
        0x75, 0x05,                    //     Report Size (5)
        0x95, 0x01,                    //     Report Count (1)
        0x81, 0x01,                    //     Input (Constant)
        //     Bytes 1-3: X, Y and wheel, each a signed byte relative to the last report
        0x05, 0x01,                    //     Usage Page (Generic Desktop)
        0x09, 0x30,                    //     Usage (X)
        0x09, 0x31,                    //     Usage (Y)
        0x09, 0x38,                    //     Usage (Wheel)
        0x15, 0x81,                    //     Logical Minimum (-127)
        0x25, 0x7F,                    //     Logical Maximum (127)
        0x75, 0x08,                    //     Report Size (8)
        0x95, 0x03,                    //     Report Count (3)
        0x81, 0x06,                    //     Input (Data, Variable, Relative)
        0xC0,                          //   End Collection
        0xC0,                          // End Collection

        // ---------------- Media keys (report ID 3) ----------------
        0x05, 0x0C,                    // Usage Page (Consumer)
        0x09, 0x01,                    // Usage (Consumer Control)
        0xA1, 0x01,                    // Collection (Application)
        0x85, CONSUMER_REPORT_ID,      //   Report ID (3)
        0x19, 0x00,                    //   Usage Minimum (0)
        0x2A, 0xFF, 0x03,              //   Usage Maximum (0x3FF) - 2 data bytes, little-endian
        0x15, 0x00,                    //   Logical Minimum (0)
        0x26, 0xFF, 0x03,              //   Logical Maximum (0x3FF)
        0x75, 0x10,                    //   Report Size (16)
        0x95, 0x01,                    //   Report Count (1)
        0x81, 0x00,                    //   Input (Data, Array)
        0xC0,                          // End Collection
    )

    /** How many bytes an input report with this ID has, or null if we don't have one. */
    fun inputReportSize(reportId: Int): Int? = when (reportId) {
        KEYBOARD_REPORT_ID -> KEYBOARD_REPORT_SIZE
        MOUSE_REPORT_ID -> MOUSE_REPORT_SIZE
        CONSUMER_REPORT_ID -> CONSUMER_REPORT_SIZE
        else -> null
    }

    // Kotlin's byteArrayOf() wants Byte values, and 0xA1 etc. are too big for a
    // signed Byte literal. Taking Ints and converting keeps the table readable.
    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
