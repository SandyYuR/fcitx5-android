/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.fcitx.fcitx5.android.utils.settingsColor
import org.robolectric.shadows.ShadowListPopupWindow
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppDialogTest {
    @Test fun keyboardCommitsGoIntoPluginTextFieldAndDismissNotifiesOnce() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        var closed = 0
        val dialog = JsAppDialog(service, { _, _ -> }, { closed++; service.jsAppInputConnection = null })
        dialog.render(Json.parseToJsonElement("""{"layout":{"type":"column","children":[
            {"type":"input","id":"input","multiline":true,"value":"原文","height":80},
            {"type":"button","id":"send","label":"发送"}]}}""").jsonObject)
        assertNotNull(service.jsAppInputConnection)
        assertNull(service.applicationInputConnection)
        service.commitText("新内容")
        assertEquals("原文新内容", dialog.values().text("input"))
        dialog.dismiss(); dialog.dismiss()
        assertEquals(1, closed)
        assertNull(service.jsAppInputConnection)
    }
    @Test fun dismissingAPermissionPromptCannotReattachTheHiddenEditor() = runBlocking {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null })
        dialog.render(Json.parseToJsonElement("""{"layout":{"type":"column","children":[
            {"type":"input","id":"input","value":"draft"}]}}""").jsonObject)
        val approved = async(start = CoroutineStart.UNDISPATCHED) {
            dialog.confirm("Permission", "Connect to relay?", "Allow", "Cancel")
        }
        dialog.dismiss()
        assertFalse(approved.await())
        assertNull(service.jsAppInputConnection)
    }

    private val form = Json.parseToJsonElement("""{"maxWidth":340,"maxHeight":360,"layout":{"type":"column","children":[
        {"type":"select","id":"purpose","label":"用途","value":"polish","height":36,"options":[
            {"label":"润色","value":"polish"},{"label":"翻译","value":"translate"}]},
        {"type":"input","id":"input","label":"文本","labelPlacement":"top","multiline":true,"value":"原文","height":68},
        {"type":"row","children":[{"type":"button","id":"close","label":"关闭"},
            {"type":"button","id":"send","label":"发送","style":"filled"}]}]}}""").jsonObject

    @Test fun materialControlsDisplayLabelsAndKeepSerializedValues() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null })
        try {
            dialog.render(form)
            val views = descendants(dialog.findViewById(android.R.id.content))
            val select = views.filterIsInstance<MaterialAutoCompleteTextView>().single()
            val boxes = views.filterIsInstance<TextInputLayout>()
            assertEquals(2, boxes.size)
            assertTrue(boxes.all { it.boxBackgroundMode == TextInputLayout.BOX_BACKGROUND_OUTLINE })
            assertEquals("润色", select.text.toString())
            assertEquals("polish", dialog.values().text("purpose"))
            dialog.setValue("purpose", "translate")
            assertEquals("翻译", select.text.toString())
            assertEquals("translate", dialog.values().text("purpose"))
            val send = views.filterIsInstance<MaterialButton>().single { it.text == "发送" }
            val close = views.filterIsInstance<MaterialButton>().single { it.text == "关闭" }
            assertEquals(send.context.settingsColor(androidx.appcompat.R.attr.colorPrimary), send.backgroundTintList!!.defaultColor)
            assertEquals(0, send.strokeWidth)
            assertTrue(close.strokeWidth > 0)
        } finally { dialog.dismiss() }
    }

    @Test fun dropdownKeepsEditorFocusAndDispatchesTheSelectedValue() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val events = mutableListOf<Pair<String, JsonObject>>()
        val dialog = JsAppDialog(service, { id, values -> events += id to values }, { service.jsAppInputConnection = null })
        try {
            dialog.render(form)
            dialog.show()
            val select = descendants(dialog.findViewById(android.R.id.content))
                .filterIsInstance<MaterialAutoCompleteTextView>().single()
            select.showDropDown()
            val popup = ShadowListPopupWindow.getLatestListPopupWindow()
            assertNotNull(popup)
            assertTrue(select.isPopupShowing)
            assertFalse("Dropdown must keep the original IME session", popup.isModal)
            assertNotNull(service.jsAppInputConnection)
            assertNull(service.applicationInputConnection)
            assertTrue(popup.performItemClick(1))
            assertEquals("purpose", events.single().first)
            assertEquals("translate", events.single().second.text("purpose"))
            assertEquals("翻译", select.text.toString())
            assertFalse(select.isPopupShowing)
            service.commitText("继续输入")
            assertEquals("原文继续输入", dialog.values().text("input"))
        } finally { dialog.dismiss() }
    }

    @Test fun busyAndExplicitlyDisabledEditorsCannotReceiveKeyboardText() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val events = mutableListOf<String>()
        val dialog = JsAppDialog(service, { id, _ -> events += id }, { service.jsAppInputConnection = null })
        try {
            dialog.render(form)
            val buttons = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
            val send = buttons.single { it.text == "发送" }
            val close = buttons.single { it.text == "关闭" }
            dialog.setBusy(true)
            service.commitText("不应写入")
            assertEquals("原文", dialog.values().text("input"))
            assertFalse(send.isEnabled)
            assertTrue(close.isEnabled)
            close.performClick()
            assertEquals(listOf("close"), events)
            dialog.setBusy(false)
            service.commitText("追加")
            assertEquals("原文追加", dialog.values().text("input"))
            dialog.setEnabled("input", false)
            dialog.setBusy(true); dialog.setBusy(false)
            service.commitText("仍不应写入")
            assertEquals("原文追加", dialog.values().text("input"))
            dialog.setEnabled("input", true)
            service.commitText("恢复")
            assertEquals("原文追加恢复", dialog.values().text("input"))
        } finally { dialog.dismiss() }
    }

    @Test fun permissionPromptHidesFixedControlsAndRestoresTheDraft() = runBlocking {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null })
        try {
            dialog.render(Json.parseToJsonElement("""{"layout":{"type":"column","children":[
                {"type":"header","children":[{"type":"text","text":"Assistant"},
                    {"type":"button","id":"close","label":"Close"}]},
                {"type":"input","id":"input","value":"draft"},
                {"type":"actions","children":[{"type":"button","id":"send","label":"Send"}]}]}}""").jsonObject)
            dialog.show()
            dialog.setBusy(true)
            val controls = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
            val response = async(start = CoroutineStart.UNDISPATCHED) { dialog.confirm("Permission", "Connect?", "Allow", "Deny") }
            assertTrue(controls.all { !it.isShown })
            descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.text == "Deny" }.performClick()
            assertFalse(response.await())
            dialog.setBusy(false)
            assertTrue(controls.all { it.isShown })
            assertEquals("draft", dialog.values().text("input"))
            service.commitText(" preserved")
            assertEquals("draft preserved", dialog.values().text("input"))
        } finally { dialog.dismiss() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

}
