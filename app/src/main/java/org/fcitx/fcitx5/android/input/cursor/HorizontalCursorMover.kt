/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.cursor

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Horizontal movement without DPAD focus navigation or positions inside surrogate pairs. */
internal object HorizontalCursorMover {
    fun move(connection: InputConnection, selection: CursorTracker, left: Boolean) {
        // A synchronous editor snapshot also reconciles accepted-but-ignored selection requests.
        // Only positions are used; adjacent text queries below are limited to two UTF-16 units.
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply {
            hintMaxChars = 4
            hintMaxLines = 1
        }, 0)
        if (extracted != null && extracted.startOffset >= 0 &&
            extracted.selectionStart >= 0 && extracted.selectionEnd >= 0) {
            val start = extracted.startOffset.toLong() + extracted.selectionStart
            val end = extracted.startOffset.toLong() + extracted.selectionEnd
            if (start > Int.MAX_VALUE || end > Int.MAX_VALUE) return
            selection.resetTo(start.toInt(), end.toInt())
        } else if (!selection.latest.rangeEquals(selection.current)) {
            // No absolute snapshot: queries cannot be anchored to an unacknowledged prediction.
            return
        }
        val (start, end) = selection.latest
        if (start < 0 || end < 0) return
        val target = if (start != end) {
            if (left) start else end
        } else if (left) {
            if (start == 0) return
            val before = connection.getTextBeforeCursor(2, 0) ?: return
            if (before.isEmpty()) return
            val width = if (Character.isLowSurrogate(before.last())) {
                if (before.length < 2 || !Character.isHighSurrogate(before[before.lastIndex - 1])) return
                2
            } else 1
            (start - width).takeIf { it >= 0 } ?: return
        } else {
            val after = connection.getTextAfterCursor(2, 0) ?: return
            if (after.isEmpty()) return
            val width = if (Character.isHighSurrogate(after[0])) {
                if (after.length < 2 || !Character.isLowSurrogate(after[1])) return
                2
            } else 1
            if (end > Int.MAX_VALUE - width) return
            end + width
        }
        selection.predict(target)
        if (!connection.setSelection(target, target)) selection.resetTo(start, end)
    }
}
