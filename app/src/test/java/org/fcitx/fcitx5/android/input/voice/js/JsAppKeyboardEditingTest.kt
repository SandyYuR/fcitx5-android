/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Use the same forwarded Rime keys and selection operations emitted by keyboard gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppKeyboardEditingTest {
    private fun withEditor(text: String, check: (FcitxInputMethodService, JsAppDialog, EditText) -> Unit) {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val tracker = ReflectionHelpers.getField<CursorTracker>(service, "selection")
        // Deliberately unrelated to the panel: operations must never use the application's cache.
        tracker.resetTo(80, 100)
        val dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null })
        try {
            val field = buildJsonObject {
                put("type", "input"); put("id", "input"); put("multiline", true); put("height", 80); put("value", text)
            }
            dialog.render(buildJsonObject { put("layout", buildJsonObject {
                put("type", "column"); put("children", buildJsonArray { add(field) })
            }) })
            dialog.show()
            shadowOf(Looper.getMainLooper()).idle()
            val edit = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<EditText>().single()
            check(service, dialog, edit)
            assertEquals(80, tracker.latest.start)
            assertEquals(100, tracker.latest.end)
        } finally { dialog.dismiss() }
    }

    private fun forward(service: FcitxInputMethodService, symbol: Int) {
        val event: FcitxEvent<*> = FcitxEvent.KeyEvent(FcitxEvent.KeyEvent.Data(KeySym(symbol), KeyStates.Virtual, 0, false, 0))
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleFcitxEvent", ClassParameter.from(FcitxEvent::class.java, event))
    }

    private fun compose(service: FcitxInputMethodService, text: String, cursor: Int) {
        val value = if (text.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(text), intArrayOf(0), cursor)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updateComposingText", ClassParameter.from(FormattedText::class.java, value))
    }

    @Test fun backspaceDeletesCharactersEmojiAndASelectedRange() = withEditor("你好😀文") { service, _, edit ->
        forward(service, FcitxKeyMapping.FcitxKey_BackSpace)
        assertEquals("你好😀", edit.text.toString())
        forward(service, FcitxKeyMapping.FcitxKey_BackSpace)
        assertEquals("你好", edit.text.toString())
        edit.setSelection(0, 1)
        forward(service, FcitxKeyMapping.FcitxKey_BackSpace)
        assertEquals("好", edit.text.toString())
        edit.setSelection(0)
        forward(service, FcitxKeyMapping.FcitxKey_BackSpace)
        assertEquals("好", edit.text.toString())
    }

    // Framework caret movement needs real text shaping, which the legacy graphics shadow lacks.
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [28, 35])
    fun repeatedSpaceSwipeArrowsMoveTheLiveCaretAndTypingUsesThatPosition() = withEditor("甲乙丙丁") { service, _, edit ->
        repeat(3) { forward(service, FcitxKeyMapping.FcitxKey_Left) }
        assertEquals(1, edit.selectionStart)
        assertEquals(1, service.currentInputSelection.start)
        service.commitText("X")
        assertEquals("甲X乙丙丁", edit.text.toString())
        repeat(20) { forward(service, FcitxKeyMapping.FcitxKey_Right) }
        assertEquals(edit.length(), edit.selectionStart)
        repeat(20) { forward(service, FcitxKeyMapping.FcitxKey_Left) }
        assertEquals(0, edit.selectionStart)
        assertTrue(edit.isFocused)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [28, 35])
    fun verticalSpaceSwipeAndEnterStayInThePanel() = withEditor("甲行\n乙行\n丙行") { service, _, edit ->
        forward(service, FcitxKeyMapping.FcitxKey_Up)
        assertTrue(edit.selectionStart < edit.length())
        forward(service, FcitxKeyMapping.FcitxKey_Down)
        assertEquals(edit.length(), edit.selectionStart)
        forward(service, FcitxKeyMapping.FcitxKey_Down)
        assertEquals(edit.length(), edit.selectionStart)
        forward(service, FcitxKeyMapping.FcitxKey_Return)
        assertEquals("甲行\n乙行\n丙行\n", edit.text.toString())
    }

    @Test fun backspaceSwipeSelectsShrinksAndDeletesUsingTheLocalRange() = withEditor("甲😀乙丙") { service, _, edit ->
        service.applySelectionOffset(-1)
        service.applySelectionOffset(-1)
        assertEquals("乙丙", edit.text.subSequence(edit.selectionStart, edit.selectionEnd).toString())
        service.applySelectionOffset(1)
        assertEquals("丙", edit.text.subSequence(edit.selectionStart, edit.selectionEnd).toString())
        service.deleteSelection()
        assertEquals("甲😀乙", edit.text.toString())
        service.applySelectionOffset(-2)
        assertEquals("😀乙", edit.text.subSequence(edit.selectionStart, edit.selectionEnd).toString())
        service.deleteSelection()
        assertEquals("甲", edit.text.toString())
        service.applySelectionOffset(-10)
        service.cancelSelection()
        assertEquals(1, edit.selectionStart)
        assertEquals(edit.selectionStart, edit.selectionEnd)
    }

    @Test fun composingCursorMovesInsidePreeditAndFinishingDoesNotEraseASelection() = withEditor("前后") { service, _, edit ->
        edit.setSelection(1)
        compose(service, "abcd", 2)
        assertEquals("前abcd后", edit.text.toString())
        assertEquals(3, edit.selectionStart)
        compose(service, "abcd", 1)
        assertEquals(2, edit.selectionStart)
        compose(service, "abXd", 3)
        assertEquals("前abXd后", edit.text.toString())
        assertEquals(4, edit.selectionStart)
        service.finishComposing()
        edit.setSelection(0, 1)
        compose(service, "", -1)
        assertEquals("前abXd后", edit.text.toString())
        assertEquals(0, edit.selectionStart)
        assertEquals(1, edit.selectionEnd)
    }

    @Test fun busyWindowConsumesEditingKeysAndSelectionGesturesWithoutTouchingTheDraft() = withEditor("原文") { service, dialog, edit ->
        dialog.setBusy(true)
        forward(service, FcitxKeyMapping.FcitxKey_BackSpace)
        forward(service, FcitxKeyMapping.FcitxKey_Left)
        service.applySelectionOffset(-2)
        service.deleteSelection()
        service.commitText("不应写入")
        assertEquals("原文", edit.text.toString())
        dialog.setBusy(false)
        service.handleBackspaceDirectly()
        assertEquals("原", edit.text.toString())
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
