package com.ronmurphy.btphoneboard.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MouseReportsTest {

    private fun report(buttons: Int, x: Int, y: Int, wheel: Int = 0) =
        byteArrayOf(buttons.toByte(), x.toByte(), y.toByte(), wheel.toByte())

    @Test
    fun movementsAddUp() {
        assertArrayEquals(report(0, 7, -3), mergeMouseReports(report(0, 5, -1), report(0, 2, -2)))
    }

    @Test
    fun movementWhileHoldingAButtonStillMerges() {
        assertArrayEquals(report(1, 10, 0), mergeMouseReports(report(1, 4, 0), report(1, 6, 0)))
    }

    @Test
    fun differentButtonsAreNotMerged() {
        assertNull(mergeMouseReports(report(0, 1, 0), report(1, 0, 0)))
    }

    @Test
    fun overflowIsNotMerged() {
        assertNull(mergeMouseReports(report(0, 100, 0), report(0, 100, 0)))
        assertNull(mergeMouseReports(report(0, 0, -100), report(0, 0, -100)))
    }

    @Test
    fun scrollingAddsUpToo() {
        assertArrayEquals(report(0, 0, 0, -3), mergeMouseReports(report(0, 0, 0, -1), report(0, 0, 0, -2)))
    }

    @Test
    fun wrongSizedReportsAreNotMerged() {
        assertNull(mergeMouseReports(byteArrayOf(0, 1, 2), report(0, 1, 1)))
    }
}
