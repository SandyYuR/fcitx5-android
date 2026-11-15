/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 固定住旧式平铺对话框的翻译规则：老插件不写 `layout` 也要能渲染。 */
class JsAppLegacyLayoutTest {
    private fun spec(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.child(index: Int) = (this["children"] as JsonArray)[index].jsonObject

    @Test fun flatSpecBecomesAColumnWithTitleFieldsAndButtons() {
        val layout = JsAppLegacyLayout.convert(spec("""
            {"title":"Rewrite",
             "fields":[
               {"id":"input","label":"Text","type":"multiline","value":"原文"},
               {"id":"purpose","type":"select","options":["polish","translate"],"value":"polish"}],
             "buttons":[{"id":"send","label":"Send"},{"id":"close"}]}
        """))!!
        assertEquals("column", layout.text("type"))
        assertEquals(4, (layout["children"] as JsonArray).size)
        val title = layout.child(0)
        assertEquals("text", title.text("type"))
        assertEquals("Rewrite", title.text("text"))
        assertEquals(15, (title["size"] as JsonPrimitive).int)
        assertTrue((title["bold"] as JsonPrimitive).boolean)
        val input = layout.child(1)
        assertEquals("input", input.text("type"))
        assertEquals("Text", input.text("label"))
        assertEquals("原文", input.text("value"))
        assertTrue((input["multiline"] as JsonPrimitive).boolean)
        val select = layout.child(2)
        assertEquals("select", select.text("type"))
        assertEquals("purpose", select.text("label"))
        assertEquals(2, (select["options"] as JsonArray).size)
        val row = layout.child(3)
        assertEquals("actions", row.text("type"))
        val buttons = (row["children"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("Send", "close"), buttons.map { it.text("label") })
        assertTrue(buttons.all { it.text("type") == "button" })
    }

    @Test fun declaredLayoutWinsAndEmptySpecsStayRejected() {
        assertNull(JsAppLegacyLayout.convert(spec("""{"layout":{"type":"column"}}""")))
        assertNull(JsAppLegacyLayout.convert(spec("""{}""")))
        assertNull(JsAppLegacyLayout.convert(spec("""{"fields":[],"buttons":[]}""")))
        // 只有标题也算可渲染内容。
        assertEquals(1, (JsAppLegacyLayout.convert(spec("""{"title":"Only"}"""))!!["children"] as JsonArray).size)
    }

    @Test fun fieldValuesFallBackToTheCurrentValues() {
        val layout = JsAppLegacyLayout.convert(
            spec("""{"fields":[{"id":"input"},{"id":"empty","value":""}]}"""),
            mapOf("input" to "draft", "empty" to "stale"),
        )!!
        assertEquals("draft", layout.child(0).text("value"))
        // 显式写了 value（哪怕是空串）就以它为准，与 Foxy 的 optString 语义一致。
        assertEquals("", layout.child(1).text("value"))
    }

    @Test fun limitsMatchFoxy() {
        val fields = (1..20).joinToString(",") { """{"id":"f$it"}""" }
        val buttons = (1..12).joinToString(",") { """{"id":"b$it"}""" }
        val layout = JsAppLegacyLayout.convert(spec("""{"fields":[$fields],"buttons":[$buttons]}"""))!!
        // 12 个字段 + 1 个完整按钮换行区域
        assertEquals(13, (layout["children"] as JsonArray).size)
        val row = layout.child(12)
        assertEquals(8, (row["children"] as JsonArray).size)
    }
}
