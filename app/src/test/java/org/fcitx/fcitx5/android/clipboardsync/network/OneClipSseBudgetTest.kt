/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OneClipSseBudgetTest {
    @Test fun oversizedUnterminatedUtf8LineIsRejectedBeforeDecoding() {
        val source = Buffer().writeUtf8("中".repeat((OneClipSseBudget.MAX_LINE_BYTES / 3 + 1).toInt()))
        assertTrue(runCatching { OneClipSseBudget.readLine(source) }.exceptionOrNull() is IOException)
    }
    @Test fun legalTailAndCrLfArePreserved() {
        val source = Buffer().writeUtf8("data: 一\r\ndata: 二")
        assertEquals("data: 一", OneClipSseBudget.readLine(source))
        assertEquals("data: 二", OneClipSseBudget.readLine(source))
        assertNull(OneClipSseBudget.readLine(source))
    }
    @Test fun manyIndividuallyLegalDataLinesExhaustTheEventBudget() {
        val line = "data: " + "x".repeat(1024)
        var bytes = 0L
        val error = runCatching {
            repeat(4096) {
                bytes += line.toByteArray(Charsets.UTF_8).size + 1L
                OneClipSseBudget.checkEvent(bytes)
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(bytes <= OneClipSseBudget.MAX_EVENT_BYTES + line.length + 1)
    }
}
