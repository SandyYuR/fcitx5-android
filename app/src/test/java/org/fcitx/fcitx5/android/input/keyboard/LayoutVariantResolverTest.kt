/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分体排列的行集合解析。
 *
 * 这里守着三条不变量，每一条坏掉的后果都是"键盘排布不对"而不是崩溃，最难靠肉眼定位：
 *
 * 1. **没配分体排列的布局行为完全不变**——绝大多数用户不碰这个功能，回归代价最大；
 * 2. **分体排列优先，且挂在子布局上**：给倉頡五代配的分体排列只能影响倉頡五代，
 *    而用户在「默认」下配的那份对没有自己分体排列的方案仍然生效；
 * 3. **方案专用布局的普通排列是最后的兜底之一**，不能被排列解析弄丢。
 */
class LayoutVariantResolverTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /**
     * 行集合里每个元素取成**裸字符串**。
     *
     * 不能直接用 `toString()`：`JsonPrimitive.toString()` 是 JSON 表示，会带引号，
     * 于是断言要写成 `listOf("\"base\"")`——那种"测试在验证 JSON 序列化"的写法一旦
     * 有人改动比较方式就会集体失败，噪音远大于收益。
     */
    private fun rowsOf(element: JsonElement?): List<String>? =
        LayoutVariantResolver.rowsArrayOf(element)?.map { (it as JsonPrimitive).content }

    private fun resolve(
        layout: JsonElement?,
        variant: LayoutVariant,
        subModes: List<String> = emptyList()
    ): List<String>? = rowsOf(LayoutVariantResolver.resolveRowsElement(layout, variant, subModes))

    /** 布局本体 + 本体自己的分体排列 + 一个带自己分体排列的方案。 */
    private val fullLayout = obj(
        """
        {
          "default": ["base"],
          "__variant__:split": ["base-split"],
          "倉頡五代": {
            "default": ["schema"],
            "__variant__:split": ["schema-split"]
          }
        }
        """.trimIndent()
    )

    @Test
    fun dockedLayoutUsesTheLayoutBody() {
        assertEquals(listOf("base"), resolve(fullLayout, LayoutVariant.Docked))
    }

    @Test
    fun splitUsesTheBodySplitEntry() {
        assertEquals(listOf("base-split"), resolve(fullLayout, LayoutVariant.Split))
    }

    /**
     * 只有 layout 本体、没有任何分体排列时，两种排列必须都拿到同一份行集合。
     *
     * 这是本功能最重要的回归护栏：用户没配分体排列，行为就得和加这个功能之前一模一样。
     */
    @Test
    fun layoutWithoutSplitEntryBehavesExactlyAsBefore() {
        val plain = obj("""{"default": ["base"]}""")
        LayoutVariant.entries.forEach { variant ->
            assertEquals(
                "$variant 在没配分体排列时必须回退到布局本体",
                listOf("base"),
                resolve(plain, variant)
            )
        }
    }

    /** 平铺数组（没有任何子布局层）同样不能因为排列解析而丢失。 */
    @Test
    fun flatArrayLayoutIsReturnedUnchanged() {
        val flat: JsonElement = Json.parseToJsonElement("""[["a"]]""")
        LayoutVariant.entries.forEach { variant ->
            assertSame(flat, LayoutVariantResolver.resolveRowsElement(flat, variant, emptyList()))
        }
    }

    // ==================== 分体排列挂在子布局上 ====================

    /**
     * 本功能的核心诉求：**每个子布局可以各配一套分体键盘布局**。
     *
     * 倉頡五代 有自己那份就用它，别的方案（没配）落到布局本体的那份——而不是"一份分体
     * 排列所有方案共用"，那样用户改了一个方案的分体键位，别的方案也跟着变。
     */
    @Test
    fun eachSubLayoutHasItsOwnSplitLayout() {
        assertEquals(
            listOf("schema-split"),
            resolve(fullLayout, LayoutVariant.Split, listOf("倉頡五代"))
        )
        assertEquals(
            "没配自己那份的方案落到布局本体的分体排列",
            listOf("base-split"),
            resolve(fullLayout, LayoutVariant.Split, listOf("拼音"))
        )
    }

    /**
     * 方案有自己的分体排列时，**不能**被它自己的普通排列顶掉。
     *
     * 这是最容易写错的一支：先按方案取到 `倉頡五代` 那条，就直接把它当结果返回了，
     * 于是用户在"倉頡 + 分体"下看到的其实是倉頡的普通排列——改了半天没效果。
     */
    @Test
    fun schemaSplitWinsOverSchemaDocked() {
        val layout = obj(
            """
            {
              "default": ["base"],
              "倉頡五代": {"default": ["schema"], "__variant__:split": ["schema-split"]}
            }
            """.trimIndent()
        )
        assertEquals(listOf("schema"), resolve(layout, LayoutVariant.Docked, listOf("倉頡五代")))
        assertEquals(listOf("schema-split"), resolve(layout, LayoutVariant.Split, listOf("倉頡五代")))
    }

    /** 本体的分体排列要能压过方案的普通排列：否则"默认"下配的那份形同虚设。 */
    @Test
    fun bodySplitWinsOverSchemaDocked() {
        val layout = obj(
            """
            {
              "default": ["base"],
              "__variant__:split": ["base-split"],
              "倉頡五代": ["schema"]
            }
            """.trimIndent()
        )
        assertEquals(
            listOf("base-split"),
            resolve(layout, LayoutVariant.Split, listOf("倉頡五代"))
        )
        assertEquals(
            "普通排列下方案专用布局照常生效",
            listOf("schema"),
            resolve(layout, LayoutVariant.Docked, listOf("倉頡五代"))
        )
    }

    @Test
    fun schemaSpecificLayoutIsUsedWhenNoSplitIsConfigured() {
        val layout = obj("""{"default": ["base"], "倉頡五代": ["schema"]}""")
        assertEquals(listOf("schema"), resolve(layout, LayoutVariant.Split, listOf("倉頡五代")))
        assertEquals(
            "没命中方案时仍回退到本体",
            listOf("base"),
            resolve(layout, LayoutVariant.Split, listOf("拼音"))
        )
    }

    /** 方案条目只带分体排列、没有普通排列时，只读分体排列那一支。 */
    @Test
    fun schemaObjectWithOnlySplitEntryResolvesSplit() {
        val layout = obj(
            """
            {
              "default": ["base"],
              "倉頡五代": {"__variant__:split": ["schema-split"]}
            }
            """.trimIndent()
        )
        assertEquals(listOf("schema-split"), resolve(layout, LayoutVariant.Split, listOf("倉頡五代")))
        assertEquals(
            "该方案没有普通排列时落到布局本体，而不是拿着一个给不出行集合的对象当结果",
            listOf("base"),
            resolve(layout, LayoutVariant.Docked, listOf("倉頡五代"))
        )
    }

    /** 畸形的分体条目必须被跳过并继续回退，而不是当作"找到了"让键盘变成空布局。 */
    @Test
    fun malformedSplitEntryIsSkipped() {
        val layout = obj("""{"default": ["base"], "__variant__:split": "not-a-row-set"}""")
        assertEquals(listOf("base"), resolve(layout, LayoutVariant.Split))
    }

    /** 分体条目内部带 `__meta__` 时，取的仍是 default 那份行集合。 */
    @Test
    fun splitEntryWithMetaStillResolvesRows() {
        val layout = obj(
            """
            {
              "default": ["base"],
              "__variant__:split": {"__meta__": {"keyboard_height_percent": 40}, "default": ["base-split"]}
            }
            """.trimIndent()
        )
        assertEquals(listOf("base-split"), resolve(layout, LayoutVariant.Split))
    }

    /** `""` 也是合法的本体键（老文件/手写文件里出现过），不能被漏掉。 */
    @Test
    fun emptyStringKeyCountsAsLayoutBody() {
        val layout = obj("""{"": ["base"]}""")
        assertEquals(listOf("base"), resolve(layout, LayoutVariant.Split))
    }

    // ==================== 磁盘契约 ====================

    /** 条目键的解析/生成是磁盘契约，改它等于让已有布局文件里的分体排列全部失联。 */
    @Test
    fun entryKeysAreStableContract() {
        assertEquals(null, LayoutVariant.Docked.jsonKey)
        assertEquals("split", LayoutVariant.Split.jsonKey)
        assertEquals("__variant__:split", LayoutJsonUtils.toVariantSubModeLabel("split"))
        assertEquals(LayoutVariant.Split, LayoutVariant.fromJsonKey("split"))
        assertNull(LayoutVariant.fromJsonKey(null))
        assertNull("带前缀的名字不是 jsonKey", LayoutVariant.fromJsonKey("__variant__:split"))

        assertEquals("rime", LayoutJsonUtils.entryKeyOf("rime", null, LayoutVariant.Docked))
        assertEquals("rime:倉頡五代", LayoutJsonUtils.entryKeyOf("rime", "倉頡五代", LayoutVariant.Docked))
        assertEquals("rime:__variant__:split", LayoutJsonUtils.entryKeyOf("rime", null, LayoutVariant.Split))
        assertEquals(
            "rime:倉頡五代:__variant__:split",
            LayoutJsonUtils.entryKeyOf("rime", "倉頡五代", LayoutVariant.Split)
        )
    }

    /**
     * 条目键往返必须无损，且**子布局标签里不能混进排列那一段**。
     *
     * 混进去的后果是分体排列被挂到布局本体上：用户给倉頡五代配的分体布局，保存后跑到
     * 所有方案共用的那一份上去了，而界面这一趟看起来完全正常。
     */
    @Test
    fun entryKeyRoundTripKeepsSubLayoutSeparateFromVariant() {
        val cases = listOf(
            "rime" to LayoutJsonUtils.EntryKey("rime", null, LayoutVariant.Docked),
            "rime:倉頡五代" to LayoutJsonUtils.EntryKey("rime", "倉頡五代", LayoutVariant.Docked),
            "rime:__variant__:split" to LayoutJsonUtils.EntryKey("rime", null, LayoutVariant.Split),
            "rime:倉頡五代:__variant__:split" to
                LayoutJsonUtils.EntryKey("rime", "倉頡五代", LayoutVariant.Split),
            // 层子布局的保留前缀自带冒号，同样不能把它切坏
            "rime:__layer__:num" to LayoutJsonUtils.EntryKey("rime", "__layer__:num", LayoutVariant.Docked),
            "rime:__layer__:num:__variant__:split" to
                LayoutJsonUtils.EntryKey("rime", "__layer__:num", LayoutVariant.Split)
        )
        cases.forEach { (key, expected) ->
            val parsed = LayoutJsonUtils.parseEntryKey(key)
            assertEquals("解析 $key", expected, parsed)
            assertEquals("回写 $key", key, parsed!!.toEntryKey())
        }
    }

    /**
     * 认不出的排列键按**普通子布局**处理，不丢内容。
     *
     * 上一版实现用过 `__variant__:landscape` 等名字；升级后那些条目必须还能被读进来、
     * 原样写回去。把它们当成"无法识别的条目"丢掉，等于用户升级一次就少一份手写的布局。
     */
    @Test
    fun unknownVariantKeyIsTreatedAsAPlainSubLayout() {
        val parsed = LayoutJsonUtils.parseEntryKey("rime:__variant__:landscape")
        assertEquals("rime", parsed?.layoutName)
        assertEquals("__variant__:landscape", parsed?.subLayout)
        assertEquals(LayoutVariant.Docked, parsed?.variant)
        assertEquals("rime:__variant__:landscape", parsed?.toEntryKey())
    }

    /**
     * 子布局标签的收集必须排除分体排列条目。
     *
     * 混进去的后果有两个，都会让用户看到莫名其妙的东西：方案下拉框里多出一个删不掉、
     * 点了没反应的"方案"；displayText 迁移把它当成一个方案去改写键面文本。
     */
    @Test
    fun subLayoutLabelsExcludeSplitEntries() {
        val keys = listOf(
            "rime",
            "rime:倉頡五代",
            "rime:__variant__:split",
            "rime:倉頡五代:__variant__:split"
        )
        assertEquals(listOf("倉頡五代"), LayoutJsonUtils.subLayoutLabelsOf(keys, "rime"))
        assertTrue(LayoutJsonUtils.hasSubLayouts(keys, "rime"))

        val onlySplit = listOf("rime", "rime:__variant__:split")
        assertTrue(
            "只有分体排列时不算有子布局，否则会凭空触发一次 displayText 迁移",
            LayoutJsonUtils.subLayoutLabelsOf(onlySplit, "rime").isEmpty()
        )
    }

    /** 回退链的每一支都必须以普通排列收尾，否则会出现"没配 → 空键盘"。 */
    @Test
    fun everyFallbackChainEndsAtDocked() {
        LayoutVariant.entries.forEach { variant ->
            val chain = variant.fallbackChain()
            assertTrue("$variant 的回退链不能为空", chain.isNotEmpty())
            assertEquals("$variant 的回退链必须以普通排列收尾", LayoutVariant.Docked, chain.last())
            assertEquals("回退链不得重复", chain.size, chain.distinct().size)
        }
    }

    /** `of()` 是"按键盘宽度是否达到阈值"的唯一入口。 */
    @Test
    fun ofMapsSplitFlagToVariant() {
        assertEquals(LayoutVariant.Split, LayoutVariant.of(split = true))
        assertEquals(LayoutVariant.Docked, LayoutVariant.of(split = false))
    }

    /** `rowsArrayOf` 对非行集合元素返回 null，调用方据此继续回退。 */
    @Test
    fun rowsArrayOfRejectsNonRowSets() {
        assertNull(LayoutVariantResolver.rowsArrayOf(null))
        assertNull(LayoutVariantResolver.rowsArrayOf(JsonPrimitive("x")))
        assertNull(LayoutVariantResolver.rowsArrayOf(obj("""{"other": []}""")))
        assertEquals(
            listOf("a"),
            rowsOf(obj("""{"default": ["a"]}"""))
        )
        assertEquals(
            listOf("a"),
            rowsOf(obj("""{"": ["a"]}"""))
        )
    }

    /** 嵌套的更畸形输入不能抛异常，只能返回"没有行集合"。 */
    @Test
    fun resolveNeverThrowsOnGarbage() {
        assertNull(resolve(null, LayoutVariant.Split))
        assertNull(resolve(JsonPrimitive(1), LayoutVariant.Split))
        assertEquals(
            "布局本体是数组时原样返回",
            listOf("a"),
            resolve(JsonArray(listOf(JsonPrimitive("a"))), LayoutVariant.Split)
        )
    }
}
