package com.ronmurphy.btphoneboard.ui

import com.ronmurphy.btphoneboard.hid.Keys
import com.ronmurphy.btphoneboard.hid.ModifierKey

/** What a key does when pressed. */
sealed interface KeyAction {
    /** A normal key: held down while your finger is on it. */
    data class Press(val usage: Int) : KeyAction

    /** Shift/Ctrl/Alt/Meta: tapping cycles off -> once -> locked. */
    data class Toggle(val modifier: ModifierKey) : KeyAction

    /** Switches the number row to F1-F12 and the arrows to Home/End/PgUp/PgDn. */
    data object Fn : KeyAction
}

/**
 * One key on the on-screen keyboard.
 *
 * [width] is relative: 1 is a normal key, 1.5 is half again as wide. Every row
 * adds up to 14.5 so the rows line up.
 */
data class KeySpec(
    val label: String,
    val action: KeyAction,
    val width: Float = 1f,
    val shiftLabel: String? = null,
    val fnKey: KeySpec? = null,
)

private fun key(
    label: String,
    usage: Int,
    width: Float = 1f,
    shift: String? = null,
    fn: KeySpec? = null,
) = KeySpec(label, KeyAction.Press(usage), width, shift, fn)

private fun letter(c: Char) = key(c.toString(), Keys.letter(c), shift = c.uppercase())

private fun digit(d: Int, shift: String, fnNumber: Int) =
    key(d.toString(), Keys.digit(d), shift = shift, fn = key("F$fnNumber", Keys.function(fnNumber)))

private fun toggle(label: String, modifier: ModifierKey, width: Float) =
    KeySpec(label, KeyAction.Toggle(modifier), width)

/** A US QWERTY layout. The host's keyboard-layout setting decides what each key types. */
object KeyboardLayout {
    val rows: List<List<KeySpec>> = listOf(
        // ` 1 2 3 4 5 6 7 8 9 0 - = Backspace
        listOf(key("`", Keys.GRAVE, shift = "~")) +
            listOf("!", "@", "#", "$", "%", "^", "&", "*", "(").mapIndexed { i, s -> digit(i + 1, s, i + 1) } +
            listOf(
                digit(0, ")", 10),
                key("-", Keys.MINUS, shift = "_", fn = key("F11", Keys.function(11))),
                key("=", Keys.EQUALS, shift = "+", fn = key("F12", Keys.function(12))),
                key("⌫", Keys.BACKSPACE, width = 1.5f),
            ),

        // Tab q w e r t y u i o p [ ] \
        listOf(key("Tab", Keys.TAB, width = 1.5f)) +
            "qwertyuiop".map(::letter) +
            listOf(
                key("[", Keys.LEFT_BRACKET, shift = "{"),
                key("]", Keys.RIGHT_BRACKET, shift = "}"),
                key("\\", Keys.BACKSLASH, shift = "|"),
            ),

        // Esc a s d f g h j k l ; ' Enter   (Esc works as "Back" on Android)
        listOf(key("Esc", Keys.ESCAPE, width = 1.75f)) +
            "asdfghjkl".map(::letter) +
            listOf(
                key(";", Keys.SEMICOLON, shift = ":"),
                key("'", Keys.APOSTROPHE, shift = "\""),
                key("Enter", Keys.ENTER, width = 1.75f),
            ),

        // Shift z x c v b n m , . / Up Del
        listOf(toggle("Shift", ModifierKey.SHIFT, 2.5f)) +
            "zxcvbnm".map(::letter) +
            listOf(
                key(",", Keys.COMMA, shift = "<"),
                key(".", Keys.PERIOD, shift = ">"),
                key("/", Keys.SLASH, shift = "?"),
                key("↑", Keys.UP, fn = key("PgUp", Keys.PAGE_UP)),
                key("Del", Keys.DELETE, fn = key("Ins", Keys.INSERT)),
            ),

        // Ctrl Fn Meta Alt Space Left Down Right
        listOf(
            toggle("Ctrl", ModifierKey.CTRL, 1.5f),
            KeySpec("Fn", KeyAction.Fn),
            toggle("Meta", ModifierKey.META, 1.25f),
            toggle("Alt", ModifierKey.ALT, 1.25f),
            key("space", Keys.SPACE, width = 6.5f),
            key("←", Keys.LEFT, fn = key("Home", Keys.HOME)),
            key("↓", Keys.DOWN, fn = key("PgDn", Keys.PAGE_DOWN)),
            key("→", Keys.RIGHT, fn = key("End", Keys.END)),
        ),
    )
}
