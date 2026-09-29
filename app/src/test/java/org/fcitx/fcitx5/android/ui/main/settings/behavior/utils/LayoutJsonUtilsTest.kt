/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.MacroKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the runtime layout parser.
 *
 * These paths run while the keyboard is being built, so a malformed key must degrade to
 * "skip this key" rather than throwing: an exception here used to propagate out of
 * BaseKeyboard's initializer and crash on every attempt to show the keyboard.
 */
class LayoutJsonUtilsTest {

    private fun row(json: String): JsonArray =
        Json.parseToJsonElement(json) as JsonArray

    /** A1: a row containing non-object elements must not throw. */
    @Test
    fun malformedRowElementsAreSkipped() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [
                  "a",
                  42,
                  null,
                  ["nested"],
                  {"type": "AlphabetKey", "main": "q", "alt": "1"}
                ]
                """.trimIndent()
            )
        )
        assertEquals("only the well-formed key survives", 1, parsed.size)
        assertEquals("AlphabetKey", parsed[0].type)
        assertEquals("q", parsed[0].main)
    }

    /** A1: an entirely malformed row yields an empty list rather than an exception. */
    @Test
    fun rowOfOnlyGarbageYieldsEmptyList() {
        assertTrue(LayoutJsonUtils.parseKeyJsonArray(row("""["a", 1, null]""")).isEmpty())
    }

    /** A1: a key object without a type is dropped (parseKeyJson returns null). */
    @Test
    fun keyWithoutTypeIsSkipped() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(row("""[{"main": "q"}]"""))
        assertTrue(parsed.isEmpty())
    }

    /** LanguageKey filtering must still work while skipping malformed entries. */
    @Test
    fun languageKeyIsFilteredWhenDisabled() {
        val json = """[{"type": "LanguageKey"}, {"type": "AlphabetKey", "main": "q", "alt": "1"}]"""
        assertEquals(2, LayoutJsonUtils.parseKeyJsonArray(row(json), showLangSwitch = true).size)
        assertEquals(1, LayoutJsonUtils.parseKeyJsonArray(row(json), showLangSwitch = false).size)
    }

    /** A2: a MacroKey without a tap action is skipped instead of throwing. */
    @Test
    fun macroKeyWithoutTapIsSkipped() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "MacroKey", "label": "M"}]""")
        )
        assertEquals(1, keys.size)
        assertNull("createKeyDef reports the key as unusable", LayoutJsonUtils.createKeyDef(keys[0]))
    }

    /** A2: a MacroKey with a tap action still resolves. */
    @Test
    fun macroKeyWithTapIsCreated() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  "label": "M",
                  "tap": {"macro": [{"type": "text", "text": "hi"}]}
                }]
                """.trimIndent()
            )
        )
        assertEquals(1, keys.size)
        val def = LayoutJsonUtils.createKeyDef(keys[0])
        assertTrue("a MacroKey with tap produces a KeyDef", def != null)
    }

    /** A2: a malformed macro step is dropped, and does not take the whole key with it. */
    @Test
    fun malformedMacroStepIsDropped() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  "label": "M",
                  "tap": {"macro": [{"type": "nonsense"}, {"type": "text", "text": "hi"}]}
                }]
                """.trimIndent()
            )
        )
        assertEquals(1, keys.size)
        assertEquals("only the valid step remains", 1, keys[0].tap?.steps?.size)
    }

    /** C23: "main": null must not become the literal string "null". */
    @Test
    fun jsonNullDoesNotBecomeLiteralNullString() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": null, "alt": null, "label": null}]""")
        )
        assertEquals(1, keys.size)
        assertNull(keys[0].main)
        assertNull(keys[0].alt)
        assertNull(keys[0].label)
    }

    /** C23: a genuine string value is still read. */
    @Test
    fun stringValuesArePreserved() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": "q", "alt": "1", "label": "Q"}]""")
        )
        assertEquals("q", keys[0].main)
        assertEquals("1", keys[0].alt)
        assertEquals("Q", keys[0].label)
    }

    /** parseLayoutRows (editor path) skips the same malformed elements. */
    @Test
    fun parseLayoutRowsSkipsMalformedElements() {
        val rows = LayoutJsonUtils.parseLayoutRows(
            Json.parseToJsonElement(
                """[["a", {"type": "AlphabetKey", "main": "q", "alt": "1"}, null]]"""
            ) as JsonArray
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].size)
        assertEquals("AlphabetKey", rows[0][0]["type"])
    }

    /** Sanity check that the fixture helper really produces objects. */
    @Test
    fun wellFormedRowParsesEveryKey() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [
                  {"type": "AlphabetKey", "main": "q", "alt": "1"},
                  {"type": "AlphabetKey", "main": "w", "alt": "2"}
                ]
                """.trimIndent()
            )
        )
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.type == "AlphabetKey" })
    }

    /**
     * 空格键的划动动作必须能完整往返：解析出 `swipe`/`swipeLabel`，再序列化回去也不丢。
     *
     * 漏掉任一侧都会让用户在布局编辑器里配好的空格划动动作在保存/重载后静默消失，
     * 而键盘本身照常工作，很难从现象上判断。
     */
    @Test
    fun spaceKeySwipeActionSurvivesRoundTrip() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "SpaceKey",
                  "weight": 0.3,
                  "swipeLabel": "⌫",
                  "swipe": {"macro": [{"type": "tap", "keys": [{"fcitx": "BackSpace"}]}]}
                }]
                """.trimIndent()
            )
        )
        assertEquals(1, parsed.size)
        assertEquals("SpaceKey", parsed[0].type)
        assertEquals("⌫", parsed[0].swipeLabel)
        assertEquals("swipe is parsed", 1, parsed[0].swipe?.steps?.size)

        val def = LayoutJsonUtils.createKeyDef(parsed[0])
        assertTrue("a SpaceKey with a swipe action produces a KeyDef", def is SpaceKey)
        val spaceKey = def as SpaceKey
        assertEquals("the swipe action reaches the KeyDef", 1, spaceKey.swipe?.steps?.size)
        assertEquals("the swipe label reaches the KeyDef", "⌫", spaceKey.swipeLabel)

        val json = LayoutJsonUtils.keyDefToJson(spaceKey)
        assertEquals("SpaceKey", json["type"])
        assertEquals("⌫", json["swipeLabel"])
        assertEquals("swipe is written back on save", 1, (json["swipe"] as? Map<*, *>)?.get("macro").let {
            (it as? List<*>)?.size
        })
    }

    /** 未配置划动动作的空格键不应写出 `swipe`/`swipeLabel` 字段。 */
    @Test
    fun plainSpaceKeyWritesNoSwipeFields() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(row("""[{"type": "SpaceKey"}]"""))
        val def = LayoutJsonUtils.createKeyDef(parsed[0]) as SpaceKey
        val json = LayoutJsonUtils.keyDefToJson(def)
        assertNull(json["swipe"])
        assertNull(json["swipeLabel"])
    }

    private fun macroKey(label: String, displayText: String?): MacroKey {
        val displayFragment = displayText?.let { """"displayText": "$it",""" }.orEmpty()
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  $displayFragment
                  "label": "$label",
                  "tap": {"macro": [{"type": "text", "text": "hi"}]}
                }]
                """.trimIndent()
            )
        )
        return LayoutJsonUtils.createKeyDef(parsed[0]) as MacroKey
    }

    /**
     * 设置了 displayText 时，它必须优先于 label，并原样渲染（keepDisplayTextCase）。
     *
     * 回归点：MacroKey 的外观也是 AltText，此前 updateAlphabetKeys() 会把单字母显示文本按
     * Shift/Caps 改写大小写，于是用户把显示文本设成大写 "A" 时键盘上仍显示 "a"，
     * 与 AlphabetKey 毫无区别 —— 显示文本这个设置形同虚设。
     */
    @Test
    fun macroKeyDisplayTextWinsOverLabel() {
        val def = macroKey(label = "a", displayText = "A")
        assertEquals("label 原样保留", "a", def.label)
        assertEquals("displayText 被解析出来", "A", def.displayText)
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("显示文本优先于标签", "A", appearance.displayText)
        assertTrue("显式 displayText 必须原样渲染", appearance.keepDisplayTextCase)
    }

    /** 未填 displayText 时回落到 label，并保留原有的 Shift 大写行为。 */
    @Test
    fun macroKeyWithoutDisplayTextFallsBackToLabel() {
        val def = macroKey(label = "M", displayText = null)
        assertEquals("M", def.label)
        assertNull(def.displayText)
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("回落到标签文本", "M", appearance.displayText)
        assertTrue("标签文本仍参与 Shift 大小写改写", !appearance.keepDisplayTextCase)
    }

    /** displayText 必须能往返保存，否则用户设置完保存一次就丢。 */
    @Test
    fun macroKeyDisplayTextSurvivesRoundTrip() {
        val def = macroKey(label = "a", displayText = "A")
        val json = LayoutJsonUtils.keyDefToJson(def)
        assertEquals("a", json["label"])
        assertEquals("A", json["displayText"])
    }

    /** 未设置 displayText 时不应写出该字段，避免把"未设置"写成空串。 */
    @Test
    fun macroKeyWithoutDisplayTextWritesNoField() {
        val def = macroKey(label = "M", displayText = null)
        assertNull(LayoutJsonUtils.keyDefToJson(def)["displayText"])
    }

    /**
     * 子模式 displayText 未命中当前上下文时视为"未设置"，回落到 label 而不是空显示文本。
     */
    @Test
    fun macroKeyDisplayTextMissesSubModeAndFallsBackToLabel() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  "label": "M",
                  "displayText": {"倉頡五代": "手"},
                  "tap": {"macro": [{"type": "text", "text": "hi"}]}
                }]
                """.trimIndent()
            )
        )
        val def = LayoutJsonUtils.createKeyDef(parsed[0], subModeLabel = "拼音") as MacroKey
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("未命中子模式时回落到标签", "M", appearance.displayText)
        assertTrue(!appearance.keepDisplayTextCase)
    }
}
