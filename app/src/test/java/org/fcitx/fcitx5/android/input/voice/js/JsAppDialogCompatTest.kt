/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 旧式平铺对话框与 `app` 节点走的是同一条渲染/事件路径，这里固定住端到端行为。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppDialogCompatTest {
    private val flat = Json.parseToJsonElement("""
        {"title":"Rewrite",
         "fields":[
           {"id":"input","label":"Text","type":"multiline","value":"原文"},
           {"id":"purpose","type":"select","options":["polish","translate"],"value":"polish"}],
         "buttons":[{"id":"send","label":"Send"}]}
    """).jsonObject

    @Test fun flatSpecsRenderAndSubmitLikeDeclarativeOnes() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val events = mutableListOf<Pair<String, JsonObject>>()
        val dialog = JsAppDialog(service, { id, values -> events += id to values }, { service.jsAppInputConnection = null })
        try {
            dialog.render(flat)
            dialog.show()
            val views = descendants(dialog.findViewById(android.R.id.content))
            assertTrue(views.filterIsInstance<TextView>().any { it.text == "Rewrite" })
            assertEquals(2, views.filterIsInstance<TextInputLayout>().size)
            views.filterIsInstance<MaterialButton>().single { it.text == "Send" }.performClick()
            assertEquals("send", events.single().first)
            assertEquals("原文", events.single().second.text("input"))
            assertEquals("polish", events.single().second.text("purpose"))
        } finally { dialog.dismiss() }
    }

    @Test fun rerenderingAFlatSpecKeepsWhatTheUserTyped() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null })
        // 字段不写 value 时才回落到上一轮的值；写了 value 就以声明为准（与 Foxy 的 optString 一致）。
        val noValue = Json.parseToJsonElement("""{"fields":[{"id":"input"}],"buttons":[{"id":"send"}]}""").jsonObject
        try {
            dialog.render(noValue)
            service.commitText("草稿")
            assertEquals("草稿", dialog.values().text("input"))
            dialog.render(noValue)
            assertEquals("草稿", dialog.values().text("input"))
        } finally { dialog.dismiss() }
    }

    @Test fun appNodesDispatchTheirCommand() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val commands = mutableListOf<String>()
        val dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null }, command = { commands += it })
        try {
            dialog.render(Json.parseToJsonElement("""{"layout":{"type":"column","children":[
                {"type":"app","command":"js.rewrite","label":"Rewrite"},
                {"type":"app","command":"js.other"}]}}""").jsonObject)
            dialog.show()
            val buttons = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
            buttons.single { it.text == "Rewrite" }.performClick()
            // 没有 label 时用命令本身当按钮文字，和 Foxy 的 label 缺省规则一致。
            buttons.single { it.text == "js.other" }.performClick()
            assertEquals(listOf("js.rewrite", "js.other"), commands)
        } finally { dialog.dismiss() }
    }

    @Test fun flatButtonsRemainReachableAtNarrowWidthsWithLargeText() = checkFlatButtons(View.LAYOUT_DIRECTION_LTR)

    @Test fun flatButtonsRemainReachableInRtl() = checkFlatButtons(View.LAYOUT_DIRECTION_RTL)

    private fun checkFlatButtons(direction: Int) {
        RuntimeEnvironment.setFontScale(1.5f)
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val events = mutableListOf<String>()
        val dialog = JsAppDialog(service, { id, _ -> events += id }, { service.jsAppInputConnection = null })
        try {
            val buttons = (1..8).joinToString(",") { """{"id":"action$it","label":"Operation $it"}""" }
            dialog.render(Json.parseToJsonElement("""{"title":"Actions","buttons":[$buttons]}""").jsonObject)
            dialog.show()
            val rendered = descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
            assertEquals(8, rendered.size)
            val parent = rendered.first().parent as ViewGroup
            parent.layoutDirection = direction
            val density = service.resources.displayMetrics.density
            parent.measure(View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            assertTrue("Whole buttons must wrap instead of squeezing away later actions",
                rendered.map { it.top }.distinct().size > 1)
            rendered.forEach { button ->
                assertTrue("Button lost its touch area: ${button.text}",
                    button.width > 0 && button.height >= (48 * density).toInt())
                assertTrue("Button escaped its action area: ${button.text}",
                    button.left >= 0 && button.right <= parent.width && button.top >= 0 && button.bottom <= parent.height)
                button.performClick()
            }
            assertEquals((1..8).map { "action$it" }, events)
        } finally {
            dialog.dismiss()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
}
