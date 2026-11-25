package com.ronmurphy.btphoneboard.hid

/**
 * Combines two mouse reports ([buttons, x, y, wheel]) into one that moves the
 * same total distance, or returns null if they can't be combined.
 *
 * BLE can only push one update at a time, and the trackpad produces reports
 * faster than that when you swipe quickly. Rather than let a backlog build up
 * (which makes the pointer lag behind your finger), the BLE link merges
 * waiting movement reports.
 *
 * Reports with different buttons are never merged, so clicks and drags are
 * kept in order. Neither are reports whose sum won't fit in -127..127.
 */
fun mergeMouseReports(first: ByteArray, second: ByteArray): ByteArray? {
    if (first.size != HidDescriptor.MOUSE_REPORT_SIZE || second.size != HidDescriptor.MOUSE_REPORT_SIZE) return null
    if (first[0] != second[0]) return null // a button changed in between

    // Adding two Bytes in Kotlin gives an Int, so overflow is easy to check.
    val x = first[1] + second[1]
    val y = first[2] + second[2]
    val wheel = first[3] + second[3]
    if (x !in -127..127 || y !in -127..127 || wheel !in -127..127) return null

    return byteArrayOf(first[0], x.toByte(), y.toByte(), wheel.toByte())
}
