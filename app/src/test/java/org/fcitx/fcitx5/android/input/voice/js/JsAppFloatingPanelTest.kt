/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import org.robolectric.shadows.ShadowListPopupWindow
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], qualifiers = "w393dp-h851dp-xhdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class JsAppFloatingPanelTest {
    private val form = Json.parseToJsonElement("""{"maxWidth":340,"maxHeight":360,"layout":{"type":"column","children":[
        {"type":"header","children":[{"type":"text","text":"AI 助手","bold":true},
            {"type":"button","id":"close","label":"关闭","style":"text","icon":"close","iconOnly":true}]},
        {"type":"input","id":"input","label":"文本","labelPlacement":"top","multiline":true,"height":68,"value":"原文"},
        {"type":"actions","children":[{"type":"button","id":"send","label":"发送","style":"filled"}]}]}}""").jsonObject

    private fun withPanel(keyboardHeightDp: Int = 300, renderBeforeShow: Boolean = true,
        beforeShow: (JsAppDialog) -> Unit = {}, check: (JsAppDialog, FcitxInputMethodService, View) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val keyboard = View(activity)
        activity.setContentView(FrameLayout(activity).apply {
            addView(keyboard, FrameLayout.LayoutParams(-1,
                (keyboardHeightDp * resources.displayMetrics.density).toInt(), Gravity.BOTTOM))
        })
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null }, { keyboard })
        try {
            if (renderBeforeShow) dialog.render(form)
            beforeShow(dialog)
            // The common IME dialog host sets these before calling show().
            dialog.window!!.attributes = dialog.window!!.attributes.apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            dialog.window!!.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            dialog.show()
            shadowOf(Looper.getMainLooper()).idle()
            check(dialog, service, keyboard)
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun floatingWindowDoesNotDimOrCaptureKeyboardTouches() = withPanel { dialog, _, keyboard ->
        val attrs = dialog.window!!.attributes
        assertEquals("The live IME encoding must draw above the attached panel",
            WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA, attrs.type)
        assertEquals(0f, attrs.dimAmount, 0f)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertAboveKeyboard(dialog, keyboard)
    }

    @Test fun localEditorKeepsItsInputAndCursorAcrossWindowFocusLossAndBusyState() = withPanel { dialog, service, keyboard ->
        val editor = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<JsAppEditText>().single()
        makeEditorWindowVisibleWithoutFocus(editor)
        assertFalse("Local editing must not claim real window focus", editor.hasWindowFocus())
        assertTrue(editor.isFocused)
        assertTrue(editor.isLocalCursorActive)
        assertFalse("The local caret must not compete with the platform's window-dependent caret", editor.isCursorVisible)
        shadowOf(Looper.getMainLooper()).idleFor(1500, TimeUnit.MILLISECONDS)
        assertTrue(editor.isLocalCursorActive)
        dialog.setBusy(true)
        assertFalse("A blocked field must not present an editing cursor", editor.isLocalCursorActive)
        service.commitText("忙碌时不能输入")
        assertEquals("原文", editor.text.toString())
        dialog.setBusy(false)
        assertTrue(editor.isLocalCursorActive)
        service.commitText("继续输入")
        assertEquals("原文继续输入", editor.text.toString())
        assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertAboveKeyboard(dialog, keyboard)
        dialog.dismiss()
        assertFalse(editor.isLocalCursorActive)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [28, 35])
    fun unfocusedLocalCaretActuallyDrawsBlinksAndPausesWithTheEditor() = withPanel { dialog, service, _ ->
        val editor = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<JsAppEditText>().single()
        makeEditorWindowVisibleWithoutFocus(editor)
        assertFalse(editor.hasWindowFocus())
        assertTrue(cursorPixels(editor) > 0)
        shadowOf(Looper.getMainLooper()).idleFor(550, TimeUnit.MILLISECONDS)
        assertEquals("The off frame must contain no cursor pixels", 0, cursorPixels(editor))
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertTrue(cursorPixels(editor) > 0)
        service.commitText("😀")
        assertEquals("原文😀", editor.text.toString())
        assertTrue("Typing must immediately show the caret at the updated selection", cursorPixels(editor) > 0)
        dialog.setBusy(true)
        shadowOf(Looper.getMainLooper()).idleFor(1500, TimeUnit.MILLISECONDS)
        assertEquals(0, cursorPixels(editor))
        dialog.setBusy(false)
        assertTrue(cursorPixels(editor) > 0)
        editor.onScreenStateChanged(View.SCREEN_STATE_OFF)
        assertEquals(0, cursorPixels(editor))
        editor.onScreenStateChanged(View.SCREEN_STATE_ON)
        assertTrue(cursorPixels(editor) > 0)
        editor.selectAll()
        assertEquals("A selected range must not draw an insertion caret", 0, cursorPixels(editor))
        editor.setSelection(editor.length())
        assertTrue(cursorPixels(editor) > 0)
        editor.visibility = View.GONE
        assertEquals(0, cursorPixels(editor))
        editor.visibility = View.VISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(cursorPixels(editor) > 0)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [28, 35])
    fun caretUsesTheLiveLayoutForEmptyTextRtlAndScrolledMultilineInput() = withPanel { dialog, _, _ ->
        val editor = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<JsAppEditText>().single()
        makeEditorWindowVisibleWithoutFocus(editor)
        for (text in listOf("", "שלום", "第一行😀\n第二行\n第三行")) {
            editor.setText(text)
            editor.setSelection(editor.length())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("The caret must be painted at the actual layout position for '$text'", cursorPixels(editor) > 0)
        }
    }

    private fun cursorPixels(editor: EditText): Int {
        val bitmap = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(-editor.scrollX.toFloat(), -editor.scrollY.toFloat())
            editor.draw(canvas)
            val layout = requireNotNull(editor.layout)
            val x = (editor.totalPaddingLeft - editor.scrollX + layout.getPrimaryHorizontal(editor.selectionStart)).toInt()
            val line = layout.getLineForOffset(editor.selectionStart)
            val top = (editor.totalPaddingTop - editor.scrollY + layout.getLineTop(line)).coerceAtLeast(0)
            val bottom = (editor.totalPaddingTop - editor.scrollY + layout.getLineBottom(line)).coerceAtMost(bitmap.height)
            val color = editor.context.settingsColor(androidx.appcompat.R.attr.colorPrimary)
            var count = 0
            for (py in top until bottom) for (px in (x - 4).coerceAtLeast(0) until (x + 8).coerceAtMost(bitmap.width)) {
                if (bitmap.getPixel(px, py) == color) count++
            }
            return count
        } finally { bitmap.recycle() }
    }

    private fun makeEditorWindowVisibleWithoutFocus(editor: EditText) {
        // Robolectric attaches Dialog children with GONE window visibility; WindowManager
        // delivers VISIBLE on a real IME subwindow. Reproduce that lifecycle before drawing.
        val attachInfo = ReflectionHelpers.getField<Any>(editor, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        ReflectionHelpers.setField(attachInfo, "mHasWindowFocus", false)
        editor.dispatchWindowVisibilityChanged(View.VISIBLE)
        editor.onWindowFocusChanged(false)
    }

    @Test fun switchingFieldsAndSelectingReadOnlyTextUpdatesTheLocalCursor() = withPanel { dialog, service, _ ->
        val layout = form["layout"]!!.jsonObject
        val children = layout["children"]!!.jsonArray.toMutableList()
        children.add(2, buildJsonObject {
            put("type", "input"); put("id", "second"); put("value", "第二段")
        })
        children.add(3, buildJsonObject {
            put("type", "input"); put("id", "reply"); put("value", "只读回复"); put("readOnly", true)
        })
        dialog.render(JsonObject(form + ("layout" to JsonObject(layout + ("children" to JsonArray(children))))))
        shadowOf(Looper.getMainLooper()).idle()
        val editors = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<JsAppEditText>()
        val first = editors.single { it.text.toString() == "原文" }
        val second = editors.single { it.text.toString() == "第二段" }
        val reply = editors.single { it.keyListener == null }
        makeEditorWindowVisibleWithoutFocus(first)
        assertTrue(first.isLocalCursorActive)
        assertFalse(second.isLocalCursorActive)
        assertFalse(reply.isLocalCursorActive)
        second.requestFocus()
        assertFalse(first.isLocalCursorActive)
        assertTrue(second.isLocalCursorActive)
        service.commitText("追加")
        assertEquals("第二段追加", second.text.toString())
        reply.requestFocus()
        assertFalse(second.isLocalCursorActive)
        assertFalse(reply.isLocalCursorActive)
        assertTrue("AI replies must still support selecting text", reply.isTextSelectable)
        service.commitText("不能写入回复")
        assertEquals("只读回复", reply.text.toString())
        first.requestFocus()
        assertTrue(first.isLocalCursorActive)
    }

    @Test fun asyncFormIsHiddenUntilTheFirstFrameHasItsFinalPosition() {
        val frames = mutableListOf<Rect>()
        withPanel(renderBeforeShow = false, beforeShow = { recordVisibleFrames(it, frames) }) { dialog, _, keyboard ->
            assertEquals("An empty initializing panel must not flash", 0f, dialog.window!!.attributes.alpha, 0f)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(frames.isEmpty())
            dialog.render(form)
            settleFrames()
            assertEquals(1f, dialog.window!!.attributes.alpha, 0f)
            assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(0, dialog.window!!.attributes.windowAnimations)
            assertTrue(frames.isNotEmpty())
            assertEquals("The first visible frame must already be at the final position", screenBounds(dialog), frames.first())
            assertTrue(frames.all { it == frames.first() })
            assertAboveKeyboard(dialog, keyboard)
        }
    }

    @Test fun firstFrameWaitsForTheKeyboardBoundsInsteadOfUsingTheScreenBottom() {
        val frames = mutableListOf<Rect>()
        withPanel(keyboardHeightDp = 0, beforeShow = { recordVisibleFrames(it, frames) }) { dialog, _, keyboard ->
            assertEquals(0f, dialog.window!!.attributes.alpha, 0f)
            assertTrue(frames.isEmpty())
            keyboard.layoutParams = keyboard.layoutParams.apply { height = (300 * resourcesDensity(keyboard)).toInt() }
            settleFrames()
            assertEquals(1f, dialog.window!!.attributes.alpha, 0f)
            assertTrue(frames.isNotEmpty())
            assertEquals(screenBounds(dialog), frames.first())
            assertAboveKeyboard(dialog, keyboard)
        }
    }

    @Test fun visiblePanelDoesNotDisappearWhileTheKeyboardPageRequestsLayout() = withPanel { dialog, _, keyboard ->
        assertEquals(1f, dialog.window!!.attributes.alpha, 0f)
        val bounds = screenBounds(dialog)
        keyboard.requestLayout()
        assertTrue(keyboard.isLayoutRequested)
        ReflectionHelpers.callInstanceMethod<Boolean>(dialog, "prepareDraw")
        assertEquals("A keyboard page switch must not make the existing panel transparent",
            1f, dialog.window!!.attributes.alpha, 0f)
        assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        settleFrames()
        assertEquals(bounds, screenBounds(dialog))
    }

    private fun recordVisibleFrames(dialog: JsAppDialog, frames: MutableList<Rect>) {
        val original = dialog.window!!.callback
        dialog.window!!.callback = object : Window.Callback by original {
            override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
                original.onWindowAttributesChanged(attrs)
                if (attrs.alpha > 0f) frames.add(screenBounds(dialog))
            }
        }
    }

    private fun screenBounds(dialog: JsAppDialog): Rect {
        val root = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val location = IntArray(2).also(root::getLocationOnScreen)
        return Rect(location[0], location[1], location[0] + root.width, location[1] + root.height)
    }

    private fun resourcesDensity(view: View) = view.resources.displayMetrics.density
    private fun settleFrames() { shadowOf(Looper.getMainLooper()).idleFor(64, TimeUnit.MILLISECONDS) }

    @Test fun titleCanMoveWithinTheAvailableAreaAndTypingStillReachesTheAssistant() = withPanel { dialog, service, keyboard ->
        val root = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val startY = dialog.window!!.attributes.y
        drag(root, 24f, 24f, -10000f, -10000f)
        shadowOf(Looper.getMainLooper()).idle()
        val top = dialog.window!!.attributes.y
        assertTrue("Dragging the title should move the panel: start=$startY current=$top", top < startY)
        assertTrue(top >= 0)
        assertTrue(dialog.window!!.attributes.x >= 0)
        drag(root, 24f, 24f, 10000f, 10000f)
        shadowOf(Looper.getMainLooper()).idle()
        assertAboveKeyboard(dialog, keyboard)
        val beforeUpdate = dialog.window!!.attributes.let { it.x to it.y }
        dialog.render(form)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Changing purpose/provider must keep the dragged position", beforeUpdate,
            dialog.window!!.attributes.let { it.x to it.y })
        service.commitText("继续输入")
        assertEquals("原文继续输入", dialog.values().text("input"))
    }

    @Test fun editorGesturesDoNotMoveThePanelAndTallKeyboardsConstrainItsHeight() = withPanel(650) { dialog, _, keyboard ->
        val root = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val editor = descendants(root).filterIsInstance<EditText>().single()
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        val editLocation = IntArray(2).also(editor::getLocationOnScreen)
        val before = dialog.window!!.attributes.let { it.x to it.y }
        drag(root, (editLocation[0] - rootLocation[0] + 16).toFloat(),
            (editLocation[1] - rootLocation[1] + 16).toFloat(), 0f, -100f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, dialog.window!!.attributes.let { it.x to it.y })
        assertAboveKeyboard(dialog, keyboard)
    }

    @Test fun dropdownRemainsOutsideTheKeyboardAndDoesNotTakeFocus() = withPanel { dialog, _, keyboard ->
        val layout = form["layout"]!!.jsonObject
        val children = layout["children"]!!.jsonArray.toMutableList()
        children.add(1, buildJsonObject {
            put("type", "select"); put("id", "purpose"); put("label", "用途"); put("height", 36); put("value", "用途1")
            put("options", JsonArray((1..20).map { JsonPrimitive("用途$it") }))
        })
        dialog.render(JsonObject(form + ("layout" to JsonObject(layout + ("children" to JsonArray(children))))))
        shadowOf(Looper.getMainLooper()).idle()
        val root = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val select = descendants(root).filterIsInstance<MaterialAutoCompleteTextView>().single()
        select.showDropDown()
        shadowOf(Looper.getMainLooper()).idle()
        val popup = ShadowListPopupWindow.getLatestListPopupWindow()
        assertTrue(select.isPopupShowing)
        assertFalse(popup.isModal)
        assertEquals("The dropdown must draw above the panel and below the live encoding",
            1004, // TYPE_APPLICATION_MEDIA_OVERLAY, between the panel and its parent IME.
            ReflectionHelpers.getField<PopupWindow>(popup, "mPopup").windowLayoutType)
        val row = checkNotNull(popup.listView).getChildAt(0).findViewById<TextView>(android.R.id.text1)
        assertEquals("A normal-font option must use the compact 36 dp row, not system preferred height",
            (36 * resourcesDensity(select)).toInt(), row.height)
        assertEquals((4 * resourcesDensity(select)).toInt(), row.paddingTop)
        assertEquals((4 * resourcesDensity(select)).toInt(), row.paddingBottom)
        assertFalse("The native dropdown must preserve wrapping instead of forcing horizontal scrolling",
            ReflectionHelpers.getField<Boolean>(row, "mHorizontallyScrolling"))
        val location = IntArray(2).also(select::getLocationOnScreen)
        val keyboardTop = IntArray(2).also(keyboard::getLocationOnScreen)[1]
        val bottom = location[1] + select.height + popup.verticalOffset + popup.height
        assertTrue("Dropdown must not cover keys", bottom <= keyboardTop)
        dialog.setBusy(true)
        assertFalse(select.isPopupShowing)
    }

    @Test fun errorsCannotExpandThePanelIntoTheKeyboard() = withPanel(720) { dialog, _, keyboard ->
        dialog.showError("错误信息。".repeat(100))
        shadowOf(Looper.getMainLooper()).idle()
        assertAboveKeyboard(dialog, keyboard)
    }

    @Test fun longPermissionTextScrollsWithoutDraggingThePanel() = withPanel(650) { dialog, _, keyboard ->
        runBlocking {
            val approved = async(start = CoroutineStart.UNDISPATCHED) {
                dialog.confirm("网络访问授权", "说明文字。\n".repeat(80), "允许", "取消")
            }
            shadowOf(Looper.getMainLooper()).idle()
            val root = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val scroll = descendants(root).filterIsInstance<ScrollView>().single()
            assertTrue(scroll.canScrollVertically(1))
            val message = descendants(root).filterIsInstance<TextView>().single { it.text.startsWith("说明文字") }
            val before = dialog.window!!.attributes.let { it.x to it.y }
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            val messageLocation = IntArray(2).also(message::getLocationOnScreen)
            val x = (messageLocation[0] - rootLocation[0] + 20).toFloat()
            val y = (messageLocation[1] - rootLocation[1] + 40).toFloat()
            val now = android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN to y, MotionEvent.ACTION_MOVE to y - 50,
                MotionEvent.ACTION_MOVE to y - 100, MotionEvent.ACTION_UP to y - 100).forEachIndexed { index, (action, py) ->
                val event = MotionEvent.obtain(now, now + index * 20L, action, x, py, 0)
                root.dispatchTouchEvent(event)
                event.recycle()
            }
            assertTrue("Permission text must remain scrollable", scroll.scrollY > 0)
            assertEquals(before, dialog.window!!.attributes.let { it.x to it.y })
            assertAboveKeyboard(dialog, keyboard)
            dialog.dismiss()
            assertFalse(approved.await())
        }
    }

    private fun assertAboveKeyboard(dialog: JsAppDialog, keyboard: View) {
        val root = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val panelLocation = IntArray(2).also(root::getLocationOnScreen)
        val keyboardLocation = IntArray(2).also(keyboard::getLocationOnScreen)
        assertTrue("Panel must be measured", root.height > 0)
        assertTrue("Panel must stay above the keyboard", panelLocation[1] + root.height <= keyboardLocation[1])
        val screenWidth = keyboard.resources.displayMetrics.widthPixels
        assertTrue(panelLocation[0] >= 0)
        assertTrue(panelLocation[0] + root.width <= screenWidth)
    }

    private fun drag(view: View, x: Float, y: Float, dx: Float, dy: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, x, y), Triple(MotionEvent.ACTION_MOVE, x + dx, y + dy),
            Triple(MotionEvent.ACTION_UP, x + dx, y + dy)).forEachIndexed { index, (action, px, py) ->
            val event = MotionEvent.obtain(now, now + index * 20L, action, px, py, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
}
