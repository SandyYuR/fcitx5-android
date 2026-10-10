/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.fcitx.fcitx5.android.input.keyboard.AlphabetKey
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroKey
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /** MacroKey 的方向标签应与上下滑宏一起往返保存，且不影响原有动作字段。 */
    @Test
    fun macroKeyRoundTripsDirectionalLabelsWithActions() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  "label": "M",
                  "swipeUpLabel": "Up",
                  "swipeDownLabel": "Down",
                  "tap": {"macro": [{"type": "text", "text": "tap"}]},
                  "swipeUp": {"macro": [{"type": "text", "text": "up"}]},
                  "swipeDown": {"macro": [{"type": "text", "text": "down"}]}
                }]
                """.trimIndent()
            )
        )
        val def = LayoutJsonUtils.createKeyDef(parsed.single()) as MacroKey
        assertEquals("Up", def.swipeUpLabel)
        assertEquals("Down", def.swipeDownLabel)
        assertEquals(1, def.swipeUp?.steps?.size)
        assertEquals(1, def.swipeDown?.steps?.size)
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("Up", appearance.altText)
        assertEquals("Down", appearance.altText1)
        assertTrue(appearance.directionalSwipeLabels)

        val json = LayoutJsonUtils.keyDefToJson(def)
        assertEquals("Up", json["swipeUpLabel"])
        assertEquals("Down", json["swipeDownLabel"])
        assertEquals(1, (json["swipeUp"] as Map<*, *>) ["macro"].let { (it as List<*>).size })
        assertEquals(1, (json["swipeDown"] as Map<*, *>) ["macro"].let { (it as List<*>).size })
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

    private fun alphabetKey(main: String, displayText: String?): AlphabetKey {
        val displayFragment = displayText?.let { """"displayText": "$it",""" }.orEmpty()
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "AlphabetKey",
                  "main": "$main",
                  "alt": "1",
                  $displayFragment
                  "weight": 0.1
                }]
                """.trimIndent()
            )
        )
        return LayoutJsonUtils.createKeyDef(parsed[0]) as AlphabetKey
    }

    /**
     * 与主字符**同值**的 displayText 也是显式设置：键面恒定显示该大小写。
     *
     * 回归点：早先的判定是「与主字符相同即视为未设置」，看起来能省掉冗余字段，实际把
     * 「主字符大写 Q + 显示文本大写 Q」这种合理诉求静默丢掉——编辑器存不住该字段，
     * 手改配置文件也不生效，用户只能绕道把主字符改成小写。同值不等于没有意图。
     */
    @Test
    fun alphabetKeyDisplayTextEqualToMainIsExplicit() {
        val def = alphabetKey(main = "Q", displayText = "Q")
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("Q", appearance.displayText)
        assertTrue("同值也是显式设置，须原样渲染（恒定大写）", appearance.keepDisplayTextCase)
    }

    /** 缺字段才是「未设置」：回落到主字符，并保留字母键固有的 Shift 切换。 */
    @Test
    fun alphabetKeyWithoutDisplayTextKeepsShiftBehaviour() {
        val def = alphabetKey(main = "Q", displayText = null)
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("Q", appearance.displayText)
        assertTrue("未设置时保留 Shift 行为", !appearance.keepDisplayTextCase)
    }

    /**
     * 与主字符真正不同的 displayText 按原样显示（keepDisplayTextCase）。
     *
     * 这既覆盖「恒定大小写」的诉求（main=d 显示 D），也覆盖多字符标签
     * （main=W 显示 "W E"，旧代码在 Shift 下会把它截断成 "W"）。
     */
    @Test
    fun alphabetKeyDistinctDisplayTextIsKeptVerbatim() {
        val upper = alphabetKey(main = "d", displayText = "D")
        val upperAppearance = upper.appearance as KeyDef.Appearance.AltText
        assertEquals("D", upperAppearance.displayText)
        assertTrue("显式设置必须原样渲染", upperAppearance.keepDisplayTextCase)

        val pair = alphabetKey(main = "W", displayText = "W E")
        val pairAppearance = pair.appearance as KeyDef.Appearance.AltText
        assertEquals("W E", pairAppearance.displayText)
        assertTrue(pairAppearance.keepDisplayTextCase)
    }

    /** 子模式取值命中且与主字符不同时，同样是显式设置。 */
    @Test
    fun alphabetKeySubModeDisplayTextIsKeptVerbatim() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "AlphabetKey",
                  "main": "Q",
                  "alt": "1",
                  "displayText": {"倉頡五代": "手"}
                }]
                """.trimIndent()
            )
        )
        val def = LayoutJsonUtils.createKeyDef(parsed[0], subModeLabel = "倉頡五代") as AlphabetKey
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("手", appearance.displayText)
        assertTrue(appearance.keepDisplayTextCase)
    }

    /** 子模式未命中时回落到主字符，此时等于未设置。 */
    @Test
    fun alphabetKeySubModeMissFallsBackToMain() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "AlphabetKey",
                  "main": "Q",
                  "alt": "1",
                  "displayText": {"倉頡五代": "手"}
                }]
                """.trimIndent()
            )
        )
        val def = LayoutJsonUtils.createKeyDef(parsed[0], subModeLabel = "拼音") as AlphabetKey
        val appearance = def.appearance as KeyDef.Appearance.AltText
        assertEquals("Q", appearance.displayText)
        assertTrue(!appearance.keepDisplayTextCase)
    }

    /**
     * 保存时必须写回与主字符同值的 displayText。
     *
     * 这正是用户报的那个 bug：编辑器把「主字符大写 + 显示文本大写」当成冗余丢掉，
     * 表现就是「填了却存不住、重进编辑框显示文本是空的」。判据只能是「用户填了没有」，
     * 不能是「填的值跟主字符像不像」。
     */
    @Test
    fun alphabetKeyWritesDisplayTextEvenWhenEqualToMain() {
        assertEquals("Q", LayoutJsonUtils.keyDefToJson(alphabetKey(main = "Q", displayText = "Q"))["displayText"])
        assertNull("未设置时不写该字段", LayoutJsonUtils.keyDefToJson(alphabetKey(main = "Q", displayText = null))["displayText"])
    }

    /** 与主字符不同的取值同样写出。 */
    @Test
    fun alphabetKeyWritesDistinctDisplayTextOnSave() {
        assertEquals("D", LayoutJsonUtils.keyDefToJson(alphabetKey(main = "d", displayText = "D"))["displayText"])
        assertEquals("W E", LayoutJsonUtils.keyDefToJson(alphabetKey(main = "W", displayText = "W E"))["displayText"])
    }

    /** 真实主字符不被显示文本改写：main 始终是输入字符。 */
    @Test
    fun alphabetKeyDisplayTextDoesNotChangeTypedCharacter() {
        val def = alphabetKey(main = "d", displayText = "D")
        assertEquals("d", def.character)
        assertEquals("1", def.punctuation)
    }

    // ==================== 分体键盘的手动分界 splitAfter ====================

    @Test
    fun splitAfterIsParsedIntoTheKeyDef() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": "g", "alt": "5", "splitAfter": true}]""")
        )
        val def = LayoutJsonUtils.createKeyDef(parsed[0])
        assertEquals(true, def?.splitAfter)
    }

    @Test
    fun absentOrFalseSplitAfterLeavesTheFlagOff() {
        val absent = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": "g", "alt": "5"}]""")
        )
        assertEquals(false, LayoutJsonUtils.createKeyDef(absent[0])?.splitAfter)

        val explicitFalse = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": "g", "alt": "5", "splitAfter": false}]""")
        )
        assertEquals(false, LayoutJsonUtils.createKeyDef(explicitFalse[0])?.splitAfter)
    }

    /**
     * 只在勾选时写出 `splitAfter`。
     *
     * 这条不是洁癖：`false` 一旦被写出来，每个键都会多一个字段——布局文件平白膨胀，
     * 二维码分享的分块数跟着上涨，而这一切对用户毫无意义。
     */
    @Test
    fun splitAfterIsOnlyWrittenWhenTrue() {
        // 同一个键：勾选 / 未勾选，序列化结果只差这一个字段。
        fun defWith(splitAfter: Boolean): KeyDef {
            val parsed = LayoutJsonUtils.parseKeyJsonArray(
                row("""[{"type": "AlphabetKey", "main": "g", "alt": "5"}]""")
            )
            return LayoutJsonUtils.createKeyDef(parsed[0])!!.apply { this.splitAfter = splitAfter }
        }

        assertEquals(true, LayoutJsonUtils.keyDefToJson(defWith(true))["splitAfter"])
        assertNull(
            "未标记时不应写出该字段",
            LayoutJsonUtils.keyDefToJson(defWith(false))["splitAfter"]
        )
    }

    /** 从 JSON 解析再写回，标记必须原样保留（编辑器保存/二维码往返都要靠它）。 */
    @Test
    fun splitAfterSurvivesAJsonRoundTrip() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": "g", "alt": "5", "splitAfter": true}]""")
        )
        val def = LayoutJsonUtils.createKeyDef(parsed[0])!!
        // keyDefToJson 产出的是编辑器内部用的 Map，转成 JSON 对象即"保存进文件"的形状。
        val written = JsonObject(
            LayoutJsonUtils.keyDefToJson(def).mapValues { (_, v) -> LayoutJsonUtils.convertToJsonProperty(v) }
        )
        assertEquals(true, (written["splitAfter"] as? JsonPrimitive)?.booleanOrNull)

        // 再解析一次（模拟用户保存后重新打开编辑器）
        val reparsed = LayoutJsonUtils.parseKeyJsonArray(JsonArray(listOf(written)))
        assertEquals(true, LayoutJsonUtils.createKeyDef(reparsed[0])?.splitAfter)
    }

    // ==================== 空白占位键 PlaceholderKey ====================

    private fun placeholderOf(json: String): KeyDef =
        LayoutJsonUtils.createKeyDef(LayoutJsonUtils.parseKeyJsonArray(row(json))[0])!!

    /**
     * 空白占位键的核心契约：**不可点击**。
     *
     * 这不是靠界面禁用做出来的效果，而是根本没有行为可绑——`BaseKeyboard` 据此把视图
     * 置为不可交互，触摸事件连进都不会进。一旦有人往这里加了一条 Behavior，占位键就会
     * 开始震动、出现按压高亮，并挡住同一位置父容器的滚动，而外观上完全看不出来。
     */
    @Test
    fun placeholderKeyHasNoBehaviorsAndNoPopup() {
        val def = placeholderOf("""[{"type": "PlaceholderKey"}]""")
        assertEquals(0, def.behaviors.size)
        assertNull("没有 popup 才不会触发长按/滑动的候选面板", def.popup)
        assertTrue("必须标成纯外观键，供运行时跳过状态改写", def.appearance.staticDisplay)
    }

    /** 默认形态：完全空白（无字符）且键底透明。 */
    @Test
    fun placeholderKeyDefaultsToInvisible() {
        val def = placeholderOf("""[{"type": "PlaceholderKey"}]""")
        val appearance = def.appearance
        assertTrue("默认不绘制键底", appearance.transparentBackground)
        val text = appearance as KeyDef.Appearance.AltText
        assertEquals("主字符默认留空", "", text.displayText)
        assertEquals("副字符默认留空", "", text.altText)
        assertEquals(
            "不画边框",
            KeyDef.Appearance.Border.Off,
            appearance.border
        )
    }

    /** 填了字符时按原样保存，绝不给任何兜底默认字符。 */
    @Test
    fun placeholderKeyKeepsUserCharactersVerbatim() {
        val def = placeholderOf("""[{"type": "PlaceholderKey", "main": "G", "alt": "5"}]""")
        val text = def.appearance as KeyDef.Appearance.AltText
        assertEquals("G", text.displayText)
        assertEquals("5", text.altText)

        val json = LayoutJsonUtils.keyDefToJson(def)
        assertEquals("G", json["main"])
        assertEquals("5", json["alt"])
    }

    /**
     * 留空就是留空：不能像别的键型那样用 `?: "0"` 之类兜底。
     *
     * 这条守的是"完全不可见的占位"那一半需求——一旦写入侧兜底出一个字符，用户得到的
     * 就是一个看得见的按键，而界面上明明什么都没填。
     */
    @Test
    fun placeholderKeyWritesNoCharacterFieldsWhenBlank() {
        val json = LayoutJsonUtils.keyDefToJson(placeholderOf("""[{"type": "PlaceholderKey"}]"""))
        assertNull("空主字符不应写出 main", json["main"])
        assertNull("空副字符不应写出 alt", json["alt"])
    }

    /** 打开自定义颜色（`transparent: false`）后要画真实键底。 */
    @Test
    fun placeholderKeyWithCustomColorDrawsItsBackground() {
        val def = placeholderOf(
            """[{"type": "PlaceholderKey", "transparent": false, "backgroundColor": -16777216}]"""
        )
        assertTrue("显式 false 表示要画底", !def.appearance.transparentBackground)
        assertEquals(-16777216, def.appearance.backgroundColor)

        // 写回时必须保留这个 false——省略会让运行时按类型默认值（透明）处理，
        // 与用户刚打开的开关相反。
        assertEquals(false, LayoutJsonUtils.keyDefToJson(def)["transparent"])
    }

    /** 透明是默认形态，不该往每个占位键都写一个 `"transparent": true`。 */
    @Test
    fun placeholderKeyOmitsTransparentWhenItIsTheDefault() {
        val json = LayoutJsonUtils.keyDefToJson(placeholderOf("""[{"type": "PlaceholderKey"}]"""))
        assertNull("透明是默认值，无需写出", json["transparent"])
    }

    /** 权重与行高百分比照常生效（占位键最常见的用法就是用它调位置）。 */
    @Test
    fun placeholderKeyCarriesWeightAndRowHeight() {
        val def = placeholderOf(
            """[{"type": "PlaceholderKey", "weight": 0.25, "rowHeightPercent": 30}]"""
        )
        assertEquals(0.25f, def.appearance.percentWidth, 1e-5f)
        assertEquals(30f, def.rowHeightPercent!!, 1e-5f)
    }

    /** 完整往返：编辑器保存后再读回来必须还是同一个占位键。 */
    @Test
    fun placeholderKeySurvivesAJsonRoundTrip() {
        val def = placeholderOf(
            """[{"type": "PlaceholderKey", "main": "·", "alt": "!", "weight": 0.2, "transparent": false}]"""
        )
        val written = JsonObject(
            LayoutJsonUtils.keyDefToJson(def).mapValues { (_, v) -> LayoutJsonUtils.convertToJsonProperty(v) }
        )
        assertEquals("PlaceholderKey", (written["type"] as? JsonPrimitive)?.content)

        val reparsed = LayoutJsonUtils.createKeyDef(
            LayoutJsonUtils.parseKeyJsonArray(JsonArray(listOf(written)))[0]
        )!!
        assertEquals("PlaceholderKey", LayoutJsonUtils.keyDefToJson(reparsed)["type"])
        assertEquals(0.2f, reparsed.appearance.percentWidth, 1e-5f)
        val text = reparsed.appearance as KeyDef.Appearance.AltText
        assertEquals("·", text.displayText)
        assertEquals("!", text.altText)
    }

    /** 同一 MacroKey 可以同时保存上滑/下滑宏，且 Shortcut 载荷不被丢失。 */
    @Test
    fun macroKeyRoundTripsBothPhysicalSwipeMacros() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  "label": "Enter",
                  "tap": {"macro": [{"type": "text", "text": "tap"}]},
                  "swipeUp": {"macro": [{
                    "type": "shortcut",
                    "modifiers": [{"fcitx": "Control"}, {"fcitx": "Shift"}],
                    "key": {"fcitx": "Return"}
                  }]},
                  "swipeDown": {"macro": [{
                    "type": "shortcut",
                    "modifiers": [{"fcitx": "Shift"}],
                    "key": {"fcitx": "Return"}
                  }]}
                }]
                """.trimIndent()
            )
        )
        val def = LayoutJsonUtils.createKeyDef(parsed.single()) as MacroKey

        assertEquals(1, def.swipeUp?.steps?.size)
        assertEquals(1, def.swipeDown?.steps?.size)
        val up = def.swipeUp!!.steps.single() as MacroStep.Shortcut
        val down = def.swipeDown!!.steps.single() as MacroStep.Shortcut
        assertEquals(listOf(KeyRef.Fcitx("Control"), KeyRef.Fcitx("Shift")), up.modifiers)
        assertEquals(KeyRef.Fcitx("Return"), up.key)
        assertEquals(listOf(KeyRef.Fcitx("Shift")), down.modifiers)
        assertEquals(KeyRef.Fcitx("Return"), down.key)

        val behavior = def.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(1, behavior.upMacro?.let { (it as MacroAction).steps.size })
        assertEquals(1, behavior.downMacro?.let { (it as MacroAction).steps.size })
        assertTrue("配置了独立上下滑动作时不受全局方向偏好限制", behavior.overrideDefaults)
        val written = LayoutJsonUtils.keyDefToJson(def)
        assertTrue(written["swipeUp"] is JsonObject)
        assertTrue(written["swipeDown"] is JsonObject)
        assertNull("新版双槽存在时不应重新写旧 swipe", written["swipe"])
    }

    /**
     * 旧单槽字段默认落到下滑槽：没有方向信息时方向不该由主题的「标点位置」决定，
     * 否则换个主题就会把用户配好的下滑键整体翻成上滑（副标签也跑到上方）。
     */
    @Test
    fun legacySingleSlotSwipeFallsBackToDownSlot() {
        val key = mutableMapOf<String, Any?>(
            "type" to "MacroKey",
            "label" to "M",
            "altLabel" to "legacy"
        )
        val entries: MutableMap<String, MutableList<MutableList<MutableMap<String, Any?>>>> =
            mutableMapOf("main" to mutableListOf(mutableListOf(key)))

        assertTrue(LayoutJsonUtils.migrateDirectionalSwipeFields(entries))
        assertNull(key["altLabel"])
        assertEquals("legacy", key["swipeDownLabel"])
        assertNull(key["swipeUpLabel"])
    }

    /** 键上只有上划槽位时，旧字段跟随到上滑槽，标签与动作方向保持一致。 */
    @Test
    fun legacySingleSlotSwipeFollowsLoneUpSlot() {
        val key = mutableMapOf<String, Any?>(
            "type" to "ReturnKey",
            "swipe" to mapOf("macro" to listOf("legacy macro")),
            "swipeLabel" to "legacy label",
            "swipeUp" to mapOf("macro" to listOf("new up macro"))
        )
        val entries: MutableMap<String, MutableList<MutableList<MutableMap<String, Any?>>>> =
            mutableMapOf("main" to mutableListOf(mutableListOf(key)))

        assertTrue(LayoutJsonUtils.migrateDirectionalSwipeFields(entries))
        assertNull(key["swipe"])
        assertNull(key["swipeLabel"])
        assertEquals("legacy label", key["swipeUpLabel"])
    }

    /** 旧字段按方向迁移一次；部分新配置不能误删另一类旧字段。 */
    @Test
    fun directionalSwipeMigrationPreservesIndependentLegacyFields() {
        val key = mutableMapOf<String, Any?>(
            "type" to "ReturnKey",
            "swipe" to mapOf("macro" to listOf("legacy macro")),
            "swipeLabel" to "legacy label",
            "swipeUp" to mapOf("macro" to listOf("new up macro")),
            "composeOverride" to mapOf(
                "swipe" to mapOf("macro" to listOf("compose legacy macro")),
                "swipeLabel" to "compose legacy label"
            )
        )
        val entries: MutableMap<String, MutableList<MutableList<MutableMap<String, Any?>>>> =
            mutableMapOf("main" to mutableListOf(mutableListOf(key)))

        assertTrue(LayoutJsonUtils.migrateDirectionalSwipeFields(entries))
        assertNull(key["swipe"])
        // 顶层键已有上划宏，旧标签跟随到上滑槽：标签显示在哪边，哪边就是触发方向。
        assertEquals("legacy label", key["swipeUpLabel"])
        assertNull(key["swipeLabel"])
        assertTrue(key.containsKey("swipeUp"))
        val compose = key["composeOverride"] as Map<*, *>
        assertTrue(compose.containsKey("swipeDown"))
        assertEquals("compose legacy label", compose["swipeDownLabel"])
        assertNull(compose["swipe"])
        assertTrue(!LayoutJsonUtils.migrateDirectionalSwipeFields(entries))
    }

    /** 功能键旧 swipe/swipeLabel 同样迁移到下滑槽。 */
    @Test
    fun directionalSwipeMigrationMovesLegacyFieldsDownward() {
        val key = mutableMapOf<String, Any?>(
            "type" to "ReturnKey",
            "swipe" to mapOf("macro" to listOf("legacy macro")),
            "swipeLabel" to "legacy label"
        )
        val entries: MutableMap<String, MutableList<MutableList<MutableMap<String, Any?>>>> =
            mutableMapOf("main" to mutableListOf(mutableListOf(key)))

        assertTrue(LayoutJsonUtils.migrateDirectionalSwipeFields(entries))
        assertTrue(key.containsKey("swipeDown"))
        assertEquals("legacy label", key["swipeDownLabel"])
        assertNull(key["swipe"])
        assertNull(key["swipeLabel"])
    }

    /**
     * 运行时解析（createKeyDef）与迁移必须给出同一个方向：旧单槽字段落到下滑槽，
     * 不再让主题的「标点位置」参与判断。
     */
    @Test
    fun createKeyDefResolvesLegacySingleSlotSwipeToDownSlot() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "ReturnKey",
                  "swipe": {"macro": [{"type": "tap", "keys": [{"fcitx": "Enter"}]}]},
                  "swipeLabel": "legacy"
                }]
                """.trimIndent()
            )
        ).single()
        val def = LayoutJsonUtils.createKeyDef(parsed) as ReturnKey

        assertNull("旧单槽动作应落到下滑槽", def.swipeUp)
        assertNotNull(def.swipeDown)
        assertNull(def.swipeUpLabel)
        assertEquals("legacy", def.swipeDownLabel)
    }

    /** 键上只有上划宏时，旧标签跟随上滑槽，避免「标签在上、动作在下」。 */
    @Test
    fun createKeyDefAttachesLegacyLabelToLoneUpMacro() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "ReturnKey",
                  "swipeUp": {"macro": [{"type": "tap", "keys": [{"fcitx": "Enter"}]}]},
                  "swipeLabel": "legacy"
                }]
                """.trimIndent()
            )
        ).single()
        val def = LayoutJsonUtils.createKeyDef(parsed) as ReturnKey

        assertNotNull(def.swipeUp)
        assertEquals("legacy", def.swipeUpLabel)
    }

    /**
     * 既没有标签、也没有方向字段的旧 `swipe` 保持原样：运行时继续按
     * 「符号划动方向（符号隐藏时）」决定，钉死到某一侧会悄悄丢掉另一个方向。
     */
    @Test
    fun unlabelledLegacySwipeKeepsItsGlobalPreferenceSlot() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "ReturnKey",
                  "swipe": {"macro": [{"type": "tap", "keys": [{"fcitx": "Enter"}]}]}
                }]
                """.trimIndent()
            )
        ).single()
        val def = LayoutJsonUtils.createKeyDef(parsed) as ReturnKey

        assertNull(def.swipeUp)
        assertNull(def.swipeDown)
        assertNotNull("旧槽位保留给全局方向偏好处理", def.swipe)
    }

    /** 方向判定规则本身：只有「同侧且唯一」的槽位才算上滑。 */
    @Test
    fun legacySwipeTargetsUpOnlyForLoneUpSlots() {
        assertFalse(LayoutJsonUtils.legacySwipeTargetsUp(false, false, false, false))
        assertFalse("默认下滑", LayoutJsonUtils.legacySwipeTargetsUp(false, true, false, false))
        assertFalse(LayoutJsonUtils.legacySwipeTargetsUp(false, false, false, true))
        assertFalse("两侧都有时不能猜方向", LayoutJsonUtils.legacySwipeTargetsUp(true, true, false, false))
        assertFalse(
            "动作在下、标签在上属于自相矛盾，仍按有下侧处理",
            LayoutJsonUtils.legacySwipeTargetsUp(false, true, true, false)
        )
        assertTrue(LayoutJsonUtils.legacySwipeTargetsUp(true, false, false, false))
        assertTrue(LayoutJsonUtils.legacySwipeTargetsUp(false, false, true, false))
        assertTrue(LayoutJsonUtils.legacySwipeTargetsUp(true, false, true, false))
    }
}
