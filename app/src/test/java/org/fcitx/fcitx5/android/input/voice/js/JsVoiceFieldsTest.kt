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

    private val dependentFields = JsVoiceFields.parse("""[
        {"kind":"text","key":"resource","default":"seed-hours",
         "defaultWhen":{"key":"auth","values":{"apiKey":"seed-hours","legacy":"big-hours"}}},
        {"kind":"select","key":"auth","default":"apiKey"},
        {"kind":"secret","key":"secret","default":"",
         "defaultWhen":{"key":"auth","values":{"apiKey":"must-not-enter-context"}}}
    ]""")

    @Test
    fun conditionalDefaultsFollowControlsRegardlessOfDeclarationOrder() {
        assertEquals("seed-hours", JsVoiceFields.currentValues(dependentFields, emptyMap())["resource"])
        assertEquals("big-hours", JsVoiceFields.currentValues(dependentFields, mapOf("auth" to "legacy"))["resource"])
        assertFalse(JsVoiceFields.currentValues(dependentFields, emptyMap()).containsKey("secret"))
    }

    @Test
    fun explicitValuesSurviveReloadButChangingTheControlResetsTheDependentDefault() {
        val values = JsVoiceFields.currentValues(dependentFields, mapOf("resource" to "custom")).toMutableMap()
        assertEquals("custom", values["resource"])
        assertEquals(mapOf("resource" to "seed-hours"), JsVoiceFields.defaultsAfterChange(dependentFields, "auth", values))
        assertTrue(JsVoiceFields.defaultsAfterChange(dependentFields, "resource", values).isEmpty())
        values["auth"] = "legacy"
        assertEquals(mapOf("resource" to "big-hours"), JsVoiceFields.defaultsAfterChange(dependentFields, "auth", values))
    }

    @Test
    fun onlyDeclaredOrdinaryFieldsAreWritableFromJavaScript() {
        val fields = JsVoiceFields.parse(declaration)
        assertTrue(JsVoiceFields.writable(fields, "mode"))
        assertTrue(JsVoiceFields.writable(fields, "stripPunctuation"))
        // 密钥项：值只在宿主侧解密使用，插件不能覆盖。
        assertFalse(JsVoiceFields.writable(fields, "apiKey"))
        // 未声明的键：写进去只会变成没人读的垃圾。
        assertFalse(JsVoiceFields.writable(fields, "notDeclared"))
        assertFalse(JsVoiceFields.writable(emptyList(), "mode"))
    }

    @Test
    fun unsupportedKindsAndKeysAlsoDeclaredAsSecretsAreNotWritable() {
        val fields = JsVoiceFields.parse("""[
            {"kind":"text","key":"apiKey"},
            {"kind":"secret","key":"apiKey"},
            {"kind":"unsupported","key":"unknown"},
            {"kind":"number","key":"timeout"}
        ]""")
        assertFalse(JsVoiceFields.writable(fields, "apiKey"))
        assertFalse(JsVoiceFields.writable(fields, "unknown"))
        assertTrue(JsVoiceFields.writable(fields, "timeout"))
    }

    @Test
    fun pluginWritesRespectPerValueAndAggregateBudgets() {
        JsVoiceFields.requireWritableValue(emptyMap(), "value", "x".repeat(JsVoiceFields.MAX_WRITABLE_VALUE_BYTES))
        assertTrue(runCatching {
            JsVoiceFields.requireWritableValue(
                emptyMap(),
                "value",
                "x".repeat(JsVoiceFields.MAX_WRITABLE_VALUE_BYTES + 1),
            )
        }.isFailure)
        val existing = mapOf(
            "a" to "x".repeat(JsVoiceFields.MAX_WRITABLE_VALUE_BYTES),
            "b" to "x".repeat(JsVoiceFields.MAX_WRITABLE_VALUE_BYTES),
            "c" to "x".repeat(JsVoiceFields.MAX_WRITABLE_VALUE_BYTES),
        )
        assertTrue(runCatching {
            JsVoiceFields.requireWritableValue(
                existing,
                "d",
                "x".repeat(JsVoiceFields.MAX_WRITABLE_VALUE_BYTES),
            )
        }.isFailure)
    }
}
