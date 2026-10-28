/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsVoiceFieldsTest {

    private val declaration = """
        [
          {"kind":"secret","key":"apiKey","label":"API key","default":""},
          {"kind":"select","key":"mode","label":"Mode","default":"streaming",
           "options":[{"label":"Streaming","value":"streaming"},{"label":"Flash","value":"flash"}]},
          {"kind":"switch","key":"stripPunctuation","label":"Strip","default":"true"},
          {"kind":"text","key":"stripCharacters","label":"Chars","default":".",
           "visibleWhen":{"key":"stripPunctuation","value":"true"}},
          {"kind":"select","key":"itn","label":"ITN","default":"false","visibleWhen":{"all":[
            {"key":"mode","value":"flash"},{"key":"platform","value":"qwen-ai"}]}}
        ]
    """.trimIndent()

    @Test
    fun parsesDeclaredFields() {
        val fields = JsVoiceFields.parse(declaration)
        assertEquals(5, fields.size)
        assertEquals(JsVoiceField.KIND_SECRET, fields[0].kind)
        assertEquals(listOf("streaming", "flash"), fields[1].options.map { it.value })
        assertTrue(fields[0].isSecret)
    }

    @Test
    fun skipsMalformedEntriesInsteadOfFailing() {
        val fields = JsVoiceFields.parse("""[{"kind":"text"},{"kind":"text","key":"ok","label":"OK"}]""")
        assertEquals(1, fields.size)
        assertEquals("ok", fields[0].key)
        assertEquals("OK", fields[0].label)
    }

    @Test
    fun toleratesNonArrayAndInvalidJson() {
        assertTrue(JsVoiceFields.parse("{}").isEmpty())
        assertTrue(JsVoiceFields.parse("not json").isEmpty())
    }

    @Test
    fun visibilityFollowsSingleCondition() {
        val fields = JsVoiceFields.parse(declaration)
        val chars = fields.first { it.key == "stripCharacters" }
        assertTrue(JsVoiceFields.isVisible(chars, mapOf("stripPunctuation" to "true")))
        assertFalse(JsVoiceFields.isVisible(chars, mapOf("stripPunctuation" to "false")))
        // 依赖的值没被跟踪时保持可见，不要静默藏掉一项用户无法使其出现的设置
        assertTrue(JsVoiceFields.isVisible(chars, emptyMap<String, String>()))
    }

    @Test
    fun visibilityFollowsAllConditions() {
        val fields = JsVoiceFields.parse(declaration)
        val itn = fields.first { it.key == "itn" }
        assertTrue(JsVoiceFields.isVisible(itn, mapOf("mode" to "flash", "platform" to "qwen-ai")))
        assertFalse(JsVoiceFields.isVisible(itn, mapOf("mode" to "flash", "platform" to "bailian")))
        assertFalse(JsVoiceFields.isVisible(itn, mapOf("mode" to "streaming", "platform" to "qwen-ai")))
    }

    @Test
    fun currentValuesMergeDefaultsAndSkipSecrets() {
        val fields = JsVoiceFields.parse(declaration)
        val values = JsVoiceFields.currentValues(fields, mapOf("mode" to "flash"))
        assertEquals("flash", values["mode"])
        assertEquals("true", values["stripPunctuation"])
        assertEquals(".", values["stripCharacters"])
        assertFalse(values.containsKey("apiKey"))
    }

    @Test
    fun storedValuesWinOverDefaults() {
        val fields = JsVoiceFields.parse(declaration)
        val values = JsVoiceFields.currentValues(
            fields,
            mapOf("mode" to "flash", "stripPunctuation" to "false"),
        )
        assertEquals("flash", values["mode"])
        assertEquals("false", values["stripPunctuation"])
    }
}
