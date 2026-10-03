/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.input.keyboard.LayoutVariant
import org.fcitx.fcitx5.android.input.keyboard.LayoutVariantResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「每个子布局各配一套分体键盘布局」在磁盘上的形状。
 *
 * 这一层最要紧的两件事：
 *
 * 1. **没配分体排列的布局，存出来的 JSON 与加这个功能之前逐字节一致**——绝大多数布局文件
 *    都属于这一支，回归差异必须是零；
 * 2. **分体排列挂在它所属的子布局内部**，不是挂在布局本体上。挂错了的表现是"给倉頡五代
 *    配的分体布局，切到拼音也在用"，而编辑器这一趟看起来完全正常，只有真实键盘上才发现。
 */
class SplitLayoutSaveJsonTest {

    private fun rows(vararg ids: String): MutableList<MutableList<MutableMap<String, Any?>>> =
        mutableListOf(
            mutableListOf(
                mutableMapOf<String, Any?>("type" to "AlphabetKey", "main" to ids.first())
            )
        )

    /** 从保存结果里取某个布局元素。 */
    private fun layoutOf(json: JsonObject, name: String): JsonElement = json[name]!!

    /** 一个子布局层里的行集合内容（取首个按键的 main 作为可辨认的标记）。 */
    private fun marker(element: JsonElement?): String? =
        LayoutVariantResolver.rowsArrayOf(element)
            ?.firstOrNull()
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("main")
            ?.let { (it as? JsonPrimitive)?.content }

    private fun containerOf(json: JsonObject, name: String): JsonObject =
        layoutOf(json, name) as JsonObject

    // ==================== 回归护栏：没配分体排列时形状完全不变 ====================

    /**
     * 只有布局本体、没有子布局、没有分体排列：存出来必须是**平铺数组**。
     *
     * 这是与旧版本逐字节对齐的那一支。多包一层 `{"default": [...]}` 虽然读得回来，
     * 却会让用户的布局文件在每次保存后无谓地膨胀、并在 diff 里显示成整体改动。
     */
    @Test
    fun plainLayoutSavesAsAFlatArray() {
        val json = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to rows("q")))
        assertTrue("单布局未配置任何附加内容时必须还是平铺数组", layoutOf(json, "rime") is JsonArray)
    }

    /** 只有高度覆盖时仍是旧的 `{__meta__, default}` 形状。 */
    @Test
    fun layoutWithOnlyHeightOverrideSavesAsMetaPlusDefault() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf("rime" to rows("q")),
            layoutHeightPercentOverrides = mapOf("rime" to 40)
        )
        val element = containerOf(json, "rime")
        assertEquals(setOf("__meta__", "default"), element.keys)
        assertEquals("q", marker(element["default"]))
    }

    /** 方案专用布局 + 布局本体：沿用旧的"方案对象"结构，没有多余的 `default`。 */
    @Test
    fun subLayoutsKeepTheExistingNestedShape() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:倉頡五代" to rows("c")
            )
        )
        val element = containerOf(json, "rime")
        assertEquals("q", marker(element["default"]))
        assertEquals("c", marker(element["倉頡五代"]))
        assertNull("子布局没有本体时不该凭空造一个 default", element["__variant__:split"])
    }

    // ==================== 分体排列的存放位置 ====================

    /**
     * 布局本体的分体排列与 `default` **平级**。
     *
     * 读那一侧（[LayoutVariantResolver]）就是这么找的：`obj["default"]` 取普通排列、
     * `obj["__variant__:split"]` 取分体排列。写成嵌套一层就读不出来了。
     */
    @Test
    fun bodySplitLayoutSitsNextToDefault() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("Q")
            )
        )
        val element = containerOf(json, "rime")
        assertEquals("q", marker(element["default"]))
        assertEquals("Q", marker(element["__variant__:split"]))
    }

    /**
     * 本功能的核心：**每个子布局可以各带一套分体排列**。
     *
     * 倉頡五代 的分体排列写在倉頡五代自己的对象里，而不是跟布局本体那份混在一起。
     */
    @Test
    fun eachSubLayoutCarriesItsOwnSplitLayout() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("Q"),
                "rime:倉頡五代" to rows("c"),
                "rime:倉頡五代:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("C")
            )
        )
        val element = containerOf(json, "rime")
        assertEquals("默认子布局的普通排列", "q", marker(element["default"]))
        assertEquals("默认子布局的分体排列", "Q", marker(element["__variant__:split"]))

        val schema = element["倉頡五代"] as JsonObject
        assertEquals("方案自己的普通排列", "c", marker(schema["default"]))
        assertEquals("方案自己的分体排列", "C", marker(schema["__variant__:split"]))
        assertNull(
            "方案的分体排列绝不能挂到布局本体上",
            element["倉頡五代:__variant__:split"]
        )
    }

    /**
     * 分体排列与布局本体内部的 `__meta__`（这里用高度覆盖）必须能共存。
     *
     * 两者写的都是同一层对象，一旦有一方把另一方的键覆盖掉，用户就会看到"设了高度没生效"
     * 或者"分体布局不见了"，而两个设置在界面上是分开的、看起来毫无关系。
     */
    @Test
    fun metaAndSplitLayoutCoexistInTheSameObject() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("Q")
            ),
            layoutHeightPercentOverrides = mapOf("rime" to 45)
        )
        val element = containerOf(json, "rime")
        assertTrue("高度覆盖与分体排列必须同时存在", element["__meta__"] is JsonObject)
        assertEquals("q", marker(element["default"]))
        assertEquals("Q", marker(element["__variant__:split"]))
    }

    /** 子布局自己的高度覆盖记在它自己的键上，与它的分体排列写在同一层。 */
    @Test
    fun subLayoutMetaAndSplitShareTheSubLayoutObject() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:倉頡五代" to rows("c"),
                "rime:倉頡五代:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("C")
            ),
            layoutHeightPercentOverrides = mapOf("rime:倉頡五代" to 50)
        )
        val schema = containerOf(json, "rime")["倉頡五代"] as JsonObject
        assertTrue(schema["__meta__"] is JsonObject)
        assertEquals("c", marker(schema["default"]))
        assertEquals("C", marker(schema["__variant__:split"]))
    }

    /** 层子布局（`__layer__:`）同样能带自己的分体排列，且前缀不会被切坏。 */
    @Test
    fun layerSubLayoutCanCarryASplitLayout() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:${LayoutJsonUtils.LAYER_SUBMODE_PREFIX}num" to rows("1"),
                "rime:${LayoutJsonUtils.LAYER_SUBMODE_PREFIX}num:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("!")
            )
        )
        val layer = containerOf(json, "rime")["__layer__:num"] as JsonObject
        assertEquals("1", marker(layer["default"]))
        assertEquals("!", marker(layer["__variant__:split"]))
    }

    /** 布局本体没有内容、只有子布局时，本体键仍要保留（否则整个布局消失）。 */
    @Test
    fun baseLayoutKeySurvivesWhenItHasNoRowsOfItsOwn() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf("rime:倉頡五代" to rows("c"))
        )
        assertTrue("布局名不能因为本体没内容就消失", json.containsKey("rime"))
        assertEquals("c", marker(containerOf(json, "rime")["倉頡五代"]))
    }

    // ==================== 读写往返 ====================

    /**
     * 「保存再读回来」必须得到同一套行集合。
     *
     * 用 [LayoutVariantResolver] 读回保存结果，等于把写那一侧与真实键盘读那一侧接了起来：
     * 只测"写出来的 JSON 长什么样"而不测"读得回来吗"，两边的口径一旦漂移就没人发现。
     */
    @Test
    fun savedSplitLayoutsResolveBackTheSameWayTheKeyboardWould() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("Q"),
                "rime:倉頡五代" to rows("c"),
                "rime:倉頡五代:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}split" to rows("C")
            )
        )
        val element = layoutOf(json, "rime")

        assertEquals(
            "普通排列下倉頡五代 用自己的那份",
            "c",
            marker(LayoutVariantResolver.resolveRowsElement(element, LayoutVariant.Docked, listOf("倉頡五代")))
        )
        assertEquals(
            "分体排列下倉頡五代 用自己的分体那份",
            "C",
            marker(LayoutVariantResolver.resolveRowsElement(element, LayoutVariant.Split, listOf("倉頡五代")))
        )
        assertEquals(
            "没配分体排列的方案落到布局本体的分体那份",
            "Q",
            marker(LayoutVariantResolver.resolveRowsElement(element, LayoutVariant.Split, listOf("拼音")))
        )
    }

    /** 认不出的排列键（上一版实现留下的）要能原样存回去，不丢内容。 */
    @Test
    fun unknownVariantEntryRoundTripsUnchanged() {
        val json = LayoutJsonUtils.convertToSaveJson(
            mapOf(
                "rime" to rows("q"),
                "rime:${LayoutJsonUtils.VARIANT_SUBMODE_PREFIX}landscape" to rows("L")
            )
        )
        val element = containerOf(json, "rime")
        assertEquals(
            "认不出的排列键要按普通子布局原样保留",
            "L",
            marker(element["__variant__:landscape"])
        )
        // 它读不出来（不是已知排列），因此分体排列只能回落到本体。
        assertEquals(
            "q",
            marker(LayoutVariantResolver.resolveRowsElement(element, LayoutVariant.Split, emptyList()))
        )
    }

    /** 键的字段顺序稳定：否则每次保存都会产生一整份无意义的 diff。 */
    @Test
    fun keyFieldOrderIsStableAcrossSaves() {
        val once = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to rows("q")))
        val twice = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to rows("q")))
        assertEquals(
            Json.encodeToString(JsonElement.serializer(), once),
            Json.encodeToString(JsonElement.serializer(), twice)
        )
    }
}
