package com.ronmurphy.btphoneboard.hid

/**
 * HID "usage IDs" for keyboard keys (Usage Page 0x07 in the HID Usage Tables).
 * These are the numbers a real keyboard sends. They identify the *physical key*,
 * not the character: "Shift + 1" is sent as Shift held plus the "1" key, and the
 * host turns that into "!" using its own keyboard layout setting.
 */
object Keys {
    /** 'a'..'z' are 0x04..0x1D in alphabetical order. */
    fun letter(c: Char): Int = 0x04 + (c.lowercaseChar() - 'a')

    /** 1..9 are 0x1E..0x26, and 0 comes after 9 at 0x27 (like on a keyboard). */
    fun digit(d: Int): Int = if (d == 0) 0x27 else 0x1D + d

    /** F1..F12 are 0x3A..0x45. */
    fun function(n: Int): Int = 0x3A + (n - 1)

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val EQUALS = 0x2E
    const val LEFT_BRACKET = 0x2F
    const val RIGHT_BRACKET = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val APOSTROPHE = 0x34
    const val GRAVE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
}

/**
 * The modifier keys. They don't go in the six key slots; each one is a single
 * bit in byte 0 of the keyboard report. We only use the left-hand versions.
 */
enum class ModifierKey(val bit: Int) {
    CTRL(0x01),
    SHIFT(0x02),
    ALT(0x04),
    META(0x08), // the Windows / Command / Search key
}

/** Mouse buttons are bits in byte 0 of the mouse report. */
object MouseButton {
    const val LEFT = 0x01
    const val RIGHT = 0x02
    const val MIDDLE = 0x04
}

/**
 * "Consumer control" usages (Usage Page 0x0C): the media and navigation keys
 * found on TV remotes and multimedia keyboards. Android maps AC Home and
 * AC Back to its own Home and Back buttons.
 */
object MediaKeys {
    const val NEXT_TRACK = 0x00B5
    const val PREVIOUS_TRACK = 0x00B6
    const val PLAY_PAUSE = 0x00CD
    const val MUTE = 0x00E2
    const val VOLUME_UP = 0x00E9
    const val VOLUME_DOWN = 0x00EA
    const val HOME = 0x0223 // "AC Home"
    const val BACK = 0x0224 // "AC Back"
}
