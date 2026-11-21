/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericLayoutResolverTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val kitLayout = json(
        """
        {
          "rime": {"default": [], "__layer__:数字": []},
          "text_editor": []
        }
        """.trimIndent()
    )

    @Test
    fun autoDetectsNumericLayerInPreferredBase() {
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.resolve(kitLayout, "", listOf("rime"))
        )
    }

    @Test
    fun autoDetectFallsBackToSingleBase() {
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.resolve(kitLayout, "", listOf("default"))
        )
    }

    @Test
    fun autoAcceptsCommonLayerNames() {
        listOf("数字", "数字键盘", "数字层", "123", "?123", "#123", "１２３", "numpad", "NUMBER").forEach { name ->
            assertTrue(name, NumericLayoutResolver.isNumericLayerLabel(LayoutJsonUtils.toLayerSubModeLabel(name)))
        }
        assertFalse(NumericLayoutResolver.isNumericLayerLabel("default"))
        assertFalse(NumericLayoutResolver.isNumericLayerLabel("__layer__:符号"))
    }

    @Test
    fun autoDetectFromEntryKeys() {
        val entries = listOf("rime", "rime:__layer__:数字", "rime:__layer__:符号", "text_editor")
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.autoDetect(entries, listOf("rime"))
        )
    }

    @Test
    fun explicitFullKeyWins() {
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.resolve(kitLayout, "rime:__layer__:数字", listOf("rime"))
        )
    }

    @Test
    fun bareLayerNameResolvesAgainstPreferredBase() {
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.resolve(kitLayout, "数字", listOf("rime"))
        )
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.resolve(kitLayout, "__layer__:数字", listOf("rime"))
        )
    }

    @Test
    fun stalePreferenceFallsBackToAuto() {
        // 上一个主题套装留下的布局键：解析不到时自动发现，而不是静默回内置键盘。
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.resolve(kitLayout, "水彩:__layer__:数字", listOf("rime"))
        )
    }

    @Test
    fun builtinTokenDisablesAutoDetection() {
        assertNull(NumericLayoutResolver.resolve(kitLayout, NumericLayoutResolver.BUILTIN, listOf("rime")))
    }

    @Test
    fun ambiguousBasesNeedAPreferredBase() {
        val twoBases = json(
            """
            {
              "rime": {"default": [], "__layer__:数字": []},
              "other": {"default": [], "__layer__:123": []}
            }
            """.trimIndent()
        )
        assertNull(NumericLayoutResolver.resolve(twoBases, "", listOf("default")))
        assertEquals(
            "other:__layer__:123",
            NumericLayoutResolver.resolve(twoBases, "", listOf("other"))
        )
    }

    @Test
    fun layoutWithoutNumericLayerReturnsNull() {
        val plain = json("""{"rime": {"default": []}}""")
        assertNull(NumericLayoutResolver.resolve(plain, "", listOf("rime")))
    }

    @Test
    fun autoDetectsStandaloneNumericLayout() {
        // Sandy 的写法：数字键盘是文件里的独立条目「数字」，不在 rime 下面做层。
        val sandy = json(
            """
            {
              "default": [],
              "rime": {"default": [], "wanxiang_t9": []},
              "text_editor": [],
              "数字": {"default": [["1"]], "__variant__:split": [["1"]]}
            }
            """.trimIndent()
        )
        assertEquals("数字", NumericLayoutResolver.resolve(sandy, "", listOf("rime")))
    }

    @Test
    fun standaloneLayoutWithoutRowsIsIgnored() {
        val metaOnly = json("""{"rime": {"default": []}, "数字": {"__meta__": {}}}""")
        assertNull(NumericLayoutResolver.resolve(metaOnly, "", listOf("rime")))
    }

    @Test
    fun preferredBaseLayerWinsOverStandaloneLayout() {
        val both = json(
            """
            {
              "rime": {"default": [], "__layer__:数字": []},
              "数字": {"default": []}
            }
            """.trimIndent()
        )
        assertEquals("rime:__layer__:数字", NumericLayoutResolver.resolve(both, "", listOf("rime")))
    }

    @Test
    fun standaloneLayoutWinsOverForeignBaseLayer() {
        val mixed = json(
            """
            {
              "rime": {"default": []},
              "other": {"__layer__:数字": []},
              "数字": {"default": [["1"]]}
            }
            """.trimIndent()
        )
        assertEquals("数字", NumericLayoutResolver.resolve(mixed, "", listOf("rime")))
    }

    @Test
    fun explicitStandaloneLayoutKeyResolves() {
        val sandy = json("""{"rime": {"default": []}, "数字": {"default": []}}""")
        assertEquals("数字", NumericLayoutResolver.resolve(sandy, "数字", listOf("rime")))
    }

    @Test
    fun entryKeysDetectStandaloneLayout() {
        val entries = listOf("rime", "rime:wanxiang_t9", "text_editor", "数字", "数字:__variant__:split")
        assertEquals("数字", NumericLayoutResolver.autoDetect(entries, listOf("rime")))
    }

    @Test
    fun multipleNumericLayersInOneBasePickByPriority() {
        val many = json(
            """
            {
              "rime": {"default": [], "__layer__:numpad": [], "__layer__:数字": []}
            }
            """.trimIndent()
        )
        assertEquals(
            "rime:__layer__:数字",
            NumericLayoutResolver.resolve(many, "", listOf("rime"))
        )
    }
}

