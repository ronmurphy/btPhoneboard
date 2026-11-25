package com.ronmurphy.btphoneboard.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputModelTest {

    /** Each sent report, as "id:hex bytes", so failures are easy to read. */
    private val sent = mutableListOf<String>()
    private lateinit var model: InputModel

    @Before
    fun setUp() {
        sent.clear()
        model = InputModel { id, data -> sent += "$id:" + data.joinToString(" ") { "%02x".format(it) } }
    }

    private val a = Keys.letter('a') // 0x04
    private val b = Keys.letter('b') // 0x05

    @Test
    fun typingALetterSendsKeyDownThenAllReleased() {
        model.keyDown(a)
        model.keyUp(a)
        assertEquals(
            listOf("1:00 00 04 00 00 00 00 00", "1:00 00 00 00 00 00 00 00"),
            sent,
        )
    }

    @Test
    fun tappingAModifierAloneSendsNothing() {
        model.toggleModifier(ModifierKey.SHIFT)
        assertTrue(sent.isEmpty())
        assertEquals(ModState.ONCE, model.modifiers[ModifierKey.SHIFT])
    }

    @Test
    fun oneShotShiftAppliesToNextKeyOnly() {
        model.toggleModifier(ModifierKey.SHIFT)
        model.keyDown(a); model.keyUp(a)
        model.keyDown(b); model.keyUp(b)
        assertEquals(
            listOf(
                "1:02 00 04 00 00 00 00 00", // Shift + A
                "1:00 00 00 00 00 00 00 00",
                "1:00 00 05 00 00 00 00 00", // plain B
                "1:00 00 00 00 00 00 00 00",
            ),
            sent,
        )
        assertEquals(ModState.OFF, model.modifiers[ModifierKey.SHIFT])
    }

    @Test
    fun lockedModifierStaysOn() {
        model.toggleModifier(ModifierKey.CTRL) // ONCE
        model.toggleModifier(ModifierKey.CTRL) // LOCKED
        model.keyDown(a); model.keyUp(a)
        model.keyDown(b); model.keyUp(b)
        assertEquals("1:01 00 04 00 00 00 00 00", sent[0])
        assertEquals("1:01 00 05 00 00 00 00 00", sent[2])
        assertEquals(ModState.LOCKED, model.modifiers[ModifierKey.CTRL])
    }

    @Test
    fun modifierCallbackFiresWhenOneShotIsUsedUp() {
        val seen = mutableListOf<ModState?>()
        model.onModifiersChanged = { seen += it[ModifierKey.ALT] }
        model.toggleModifier(ModifierKey.ALT)
        model.keyDown(a); model.keyUp(a)
        assertEquals(listOf(ModState.ONCE, ModState.OFF), seen)
    }

    @Test
    fun sixKeysAtOnceMaxAndSeventhIsIgnored() {
        "abcdefg".forEach { model.keyDown(Keys.letter(it)) }
        assertEquals("1:00 00 04 05 06 07 08 09", sent.last())
        assertEquals(6, sent.size) // the 7th key produced no report
    }

    @Test
    fun ctrlClickWrapsTheClickInTheModifier() {
        model.toggleModifier(ModifierKey.CTRL)
        model.click(MouseButton.LEFT)
        assertEquals(
            listOf(
                "1:01 00 00 00 00 00 00 00", // Ctrl down
                "2:01 00 00 00",             // left button down
                "2:00 00 00 00",             // left button up
                "1:00 00 00 00 00 00 00 00", // Ctrl up
            ),
            sent,
        )
    }

    @Test
    fun bigMovementsAreSplitIntoSteps() {
        model.moveMouse(300f, -10f)
        assertEquals(
            listOf("2:00 7f f6 00", "2:00 7f 00 00", "2:00 2e 00 00"), // 127 + 127 + 46 = 300
            sent,
        )
    }

    @Test
    fun fractionalMovementsAddUp() {
        model.moveMouse(0.4f, 0f)
        model.moveMouse(0.4f, 0f)
        assertTrue(sent.isEmpty())
        model.moveMouse(0.4f, 0f) // 1.2 in total
        assertEquals(listOf("2:00 01 00 00"), sent)
    }

    @Test
    fun scrollSendsWheelNotches() {
        model.scroll(-2.5f)
        assertEquals(listOf("2:00 00 00 fe"), sent) // -2, with -0.5 left over
        model.scroll(-0.5f)
        assertEquals("2:00 00 00 ff", sent.last())
    }

    @Test
    fun movingWhileHoldingLeftButtonKeepsTheButtonDown() {
        model.mouseButtonDown(MouseButton.LEFT)
        model.moveMouse(5f, 0f)
        assertEquals("2:01 05 00 00", sent.last())
    }

    @Test
    fun mediaKeysAreLittleEndian() {
        model.mediaKeyDown(MediaKeys.BACK) // 0x0224
        model.mediaKeyUp()
        assertEquals(listOf("3:24 02", "3:00 00"), sent)
    }
}
