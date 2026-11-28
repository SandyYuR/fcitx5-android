/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import okio.BufferedSource
import java.io.IOException

internal object OneClipSseBudget {
    internal const val MAX_LINE_BYTES = 1024L * 1024
    internal const val MAX_EVENT_BYTES = 2L * 1024 * 1024

    fun readLine(source: BufferedSource): String? {
        // Bound the search itself: readUtf8Line() would buffer until an arbitrary LF/EOF.
        val newline = source.indexOf(10.toByte(), 0, MAX_LINE_BYTES + 1)
        if (newline >= 0) return source.readUtf8LineStrict(MAX_LINE_BYTES)
        if (source.buffer.size > MAX_LINE_BYTES) throw IOException("OneClip SSE line exceeds byte limit")
        return if (source.buffer.size == 0L) null else source.readUtf8()
    }

    fun checkEvent(bytes: Long) {
        if (bytes > MAX_EVENT_BYTES) throw IOException("OneClip SSE event exceeds byte limit")
    }
}
