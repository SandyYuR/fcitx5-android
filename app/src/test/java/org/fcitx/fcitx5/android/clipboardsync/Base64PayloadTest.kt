/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.clipboardsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A9: the ClipCascade receive path must judge a payload's size before decoding it.
 *
 * The estimate has to be an upper bound (so nothing oversized slips through the cap) while still
 * being tight enough that a legitimate payload just under the user's size filter is not rejected.
 */
class Base64PayloadTest {

    /** Standard base64 of `size` bytes: 4 characters per 3 bytes, padded to a multiple of 4. */
    private fun encodedOf(size: Int): String = "A".repeat((size + 2) / 3 * 4)

    @Test
    fun estimateIsAnUpperBoundOfTheDecodedSize() {
        for (size in 1..64) {
            val estimate = Base64Payload.estimatedDecodedBytes(encodedOf(size))
            assertTrue(
                "estimate $estimate must not be below the real size $size",
                estimate >= size
            )
        }
    }

    @Test
    fun estimateIsTightForUnpaddedLengths() {
        // 3 bytes -> exactly 4 characters, no padding: the estimate is exact.
        assertEquals(3L, Base64Payload.estimatedDecodedBytes("AAAA"))
        assertEquals(3072L, Base64Payload.estimatedDecodedBytes("A".repeat(4096)))
    }

    @Test
    fun paddingCostsAtMostTwoBytesOfSlack() {
        // One byte encodes as "AA==", which still estimates as 3 — the documented worst case.
        assertEquals(3L, Base64Payload.estimatedDecodedBytes("AA=="))
        assertEquals(3L, Base64Payload.estimatedDecodedBytes("AAA="))
    }

    @Test
    fun lineBreaksAreNotCountedAsPayload() {
        // MIME base64 wraps at 76 characters. Counting the breaks inflated the estimate by ~1.3%
        // per line, which rejected items sitting just under a size filter.
        val oneLine = "A".repeat(76)
        val wrapped = List(10) { oneLine }.joinToString("\r\n")
        val flat = oneLine.repeat(10)
        assertEquals(
            Base64Payload.estimatedDecodedBytes(flat),
            Base64Payload.estimatedDecodedBytes(wrapped)
        )
    }

    @Test
    fun allWhitespaceFormsAreIgnored() {
        assertEquals(3L, Base64Payload.estimatedDecodedBytes(" A A\tA\nA "))
    }

    @Test
    fun emptyPayloadEstimatesZero() {
        assertEquals(0L, Base64Payload.estimatedDecodedBytes(""))
        assertEquals(0L, Base64Payload.estimatedDecodedBytes("   \n "))
    }

    @Test
    fun droppingWhitespaceNeverPullsAPayloadUnderACap() {
        // Excluding line breaks lowers the estimate, so check the direction that matters: the
        // estimate for the wrapped form still equals the flat one and stays above a cap the flat
        // form exceeds. Deliberately a small cap — a 32MB payload as a JVM String would be 64MB
        // of UTF-16 in the test process.
        val cap = 3_000L
        val flat = "A".repeat(4_096)
        val wrapped = flat.chunked(76).joinToString("\r\n")
        assertTrue(Base64Payload.estimatedDecodedBytes(flat) > cap)
        assertTrue(Base64Payload.estimatedDecodedBytes(wrapped) > cap)
    }
}
