/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.cursor

import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], manifest = Config.NONE)
class HorizontalCursorMoverTest {
    private class Editor(text: String, start: Int, end: Int = start) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val content = SpannableStringBuilder(text).also { Selection.setSelection(it, start, end) }
        val requests = mutableListOf<Pair<Int, Int>>()
        var reject = false
        var ignore = false
        var snapshot = true
        var adjacentText = true
        var keyEvents = 0
        override fun getEditable() = content
        override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText? {
            assertEquals(4, request.hintMaxChars)
            assertEquals(1, request.hintMaxLines)
            return if (!snapshot) null else ExtractedText().apply {
                text = content
                startOffset = 0
                selectionStart = Selection.getSelectionStart(content)
                selectionEnd = Selection.getSelectionEnd(content)
            }
        }
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            assertTrue(n <= 2)
            return if (adjacentText) super.getTextBeforeCursor(n, flags) else null
        }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            assertTrue(n <= 2)
            return if (adjacentText) super.getTextAfterCursor(n, flags) else null
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            requests += start to end
            if (reject) return false
            if (!ignore) Selection.setSelection(content, start, end)
            return true
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean { keyEvents++; return true }
    }

    private fun tracker(start: Int, end: Int = start) = CursorTracker().apply { resetTo(start, end) }

    @Test fun bothDirectionsSkipAnEntireSupplementaryCodePoint() {
        val editor = Editor("A😀B", 3)
        val selection = tracker(3)
        HorizontalCursorMover.move(editor, selection, true)
        assertEquals(1, Selection.getSelectionStart(editor.content))
        HorizontalCursorMover.move(editor, selection, false)
        assertEquals(3, Selection.getSelectionStart(editor.content))
        assertFalse(editor.requests.any { it.first == 2 })
    }

    @Test fun repeatedMovesUseTheActualEditorWithoutWaitingForCallbacks() {
        val editor = Editor("A😀B", 4)
        val selection = tracker(4)
        repeat(8) { HorizontalCursorMover.move(editor, selection, true) }
        assertEquals(listOf(3 to 3, 1 to 1, 0 to 0), editor.requests)
        repeat(8) { HorizontalCursorMover.move(editor, selection, false) }
        assertEquals(4, Selection.getSelectionStart(editor.content))
        assertEquals(0, editor.keyEvents)
    }

    @Test fun repeatedEndOfTextArrowsLeaveNoPhantomPredictionBeforeReversal() {
        val editor = Editor("abc", 3)
        val selection = tracker(3)
        repeat(4) { HorizontalCursorMover.move(editor, selection, false) }
        assertTrue(editor.requests.isEmpty())
        assertEquals(3, selection.latest.start)
        assertTrue(selection.consume(3))
        HorizontalCursorMover.move(editor, selection, true)
        assertEquals(listOf(2 to 2), editor.requests)
        assertEquals(2, selection.latest.start)
    }

    @Test fun rejectedAndIgnoredRequestsAreReconciledBeforeReversal() {
        for (reject in listOf(true, false)) {
            val editor = Editor("abc", 1).apply { this.reject = reject; ignore = !reject }
            val selection = tracker(1)
            HorizontalCursorMover.move(editor, selection, false)
            if (reject) assertEquals(1, selection.latest.start)
            editor.reject = false
            editor.ignore = false
            HorizontalCursorMover.move(editor, selection, true)
            assertEquals(0, Selection.getSelectionStart(editor.content))
            assertEquals(0, selection.latest.start)
        }
    }

    @Test fun selectionCollapsesToItsDirectionalEdge() {
        for ((start, end) in listOf(1 to 3, 3 to 1)) {
            for (left in listOf(true, false)) {
                val editor = Editor("A😀B", start, end)
                val selection = tracker(start, end)
                HorizontalCursorMover.move(editor, selection, left)
                assertEquals(if (left) 1 else 3, selection.latest.start)
                assertEquals(selection.latest.start, selection.latest.end)
            }
        }
    }

    @Test fun missingSnapshotsUseConfirmedPositionsAndMissingTextNeverNavigatesFocus() {
        val editor = Editor("A😀B", 1).apply { snapshot = false }
        val selection = tracker(1)
        HorizontalCursorMover.move(editor, selection, false)
        assertEquals(3, selection.latest.start)
        selection.consume(3)
        editor.adjacentText = false
        HorizontalCursorMover.move(editor, selection, false)
        assertEquals(3, selection.latest.start)
        assertEquals(listOf(3 to 3), editor.requests)
        assertEquals(0, editor.keyEvents)
    }
}
