package com.ronmurphy.btphoneboard.hid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reads the descriptor bytes the way a host would and checks that the report
 * sizes it describes match the sizes InputModel actually sends. If someone
 * edits the descriptor and gets a count wrong, this test catches it.
 */
class HidDescriptorTest {

    private class Parsed(
        val inputBits: Map<Int, Int>,
        val outputBits: Map<Int, Int>,
        val openCollections: Int,
    )

    private fun parse(bytes: ByteArray): Parsed {
        val inputBits = mutableMapOf<Int, Int>()
        val outputBits = mutableMapOf<Int, Int>()
        var reportId = 0
        var reportSize = 0
        var reportCount = 0
        var depth = 0
        var i = 0
        while (i < bytes.size) {
            val prefix = bytes[i].toInt() and 0xFF
            // Low 2 bits = number of data bytes (3 means 4), next 2 = type, top 4 = tag.
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            val type = (prefix shr 2) and 0x03
            val tag = prefix shr 4
            var value = 0
            for (j in 0 until size) value = value or ((bytes[i + 1 + j].toInt() and 0xFF) shl (8 * j))
            when (type) {
                0 -> when (tag) { // Main items
                    0x8 -> inputBits.merge(reportId, reportSize * reportCount, Int::plus)
                    0x9 -> outputBits.merge(reportId, reportSize * reportCount, Int::plus)
                    0xA -> depth++
                    0xC -> depth--
                }
                1 -> when (tag) { // Global items
                    0x7 -> reportSize = value
                    0x8 -> reportId = value
                    0x9 -> reportCount = value
                }
            }
            i += 1 + size
        }
        return Parsed(inputBits, outputBits, depth)
    }

    @Test
    fun collectionsAreBalanced() {
        assertEquals(0, parse(HidDescriptor.BYTES).openCollections)
    }

    @Test
    fun inputReportSizesMatchWhatWeSend() {
        val parsed = parse(HidDescriptor.BYTES)
        assertEquals(
            mapOf(
                HidDescriptor.KEYBOARD_REPORT_ID to HidDescriptor.KEYBOARD_REPORT_SIZE * 8,
                HidDescriptor.MOUSE_REPORT_ID to HidDescriptor.MOUSE_REPORT_SIZE * 8,
                HidDescriptor.CONSUMER_REPORT_ID to HidDescriptor.CONSUMER_REPORT_SIZE * 8,
            ),
            parsed.inputBits,
        )
    }

    @Test
    fun keyboardHasOneByteLedOutputReport() {
        assertEquals(mapOf(HidDescriptor.KEYBOARD_REPORT_ID to 8), parse(HidDescriptor.BYTES).outputBits)
    }
}
