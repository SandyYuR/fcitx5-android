/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WanxiangDownloadProgressTest {
    @Test fun knownLengthsStartAtZeroAndAdvanceWithoutRoundingUp() {
        assertEquals(0, WanxiangDownloadProgress.percent(0, 100))
        assertEquals(25, WanxiangDownloadProgress.percent(25, 100))
        for (value in 0L..100L) assertEquals(value.toInt(), WanxiangDownloadProgress.percent(value, 100))
        assertEquals(33, WanxiangDownloadProgress.percent(1, 3))
        assertEquals(66, WanxiangDownloadProgress.percent(2, 3))
        assertEquals(99, WanxiangDownloadProgress.percent(999, 1000))
        assertEquals(100, WanxiangDownloadProgress.percent(1000, 1000))
        assertEquals(100, WanxiangDownloadProgress.percent(1, 1))
    }

    @Test fun unknownOrInvalidLengthsHaveNoPercentage() {
        for ((bytes, total) in listOf(
            0L to 0L,
            25L to 0L,
            25L to -1L,
            0L to Long.MIN_VALUE,
            -1L to 100L,
            Long.MIN_VALUE to Long.MAX_VALUE,
            101L to 100L,
            Long.MAX_VALUE to 100L
        )) {
            assertNull("$bytes of $total must remain indeterminate", WanxiangDownloadProgress.percent(bytes, total))
        }
    }

    @Test fun largeLengthsCannotOverflowOrFinishBeforeTheLastByte() {
        val total = 4_000_000_000_000_000_000L
        assertEquals(25, WanxiangDownloadProgress.percent(total / 4, total))
        assertEquals(75, WanxiangDownloadProgress.percent(total / 4 * 3, total))
        assertEquals(0, WanxiangDownloadProgress.percent(1, Long.MAX_VALUE))
        assertEquals(99, WanxiangDownloadProgress.percent(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(100, WanxiangDownloadProgress.percent(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
