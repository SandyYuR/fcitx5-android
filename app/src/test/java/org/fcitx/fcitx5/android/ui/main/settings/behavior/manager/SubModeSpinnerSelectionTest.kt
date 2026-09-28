/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局编辑器子模式下拉框的「显示位置 → 子模式标签」映射。
 *
 * 这是修复「rime 下的 default 布局在编辑器里无法编辑」的核心不变量：位置 0 必须固定为
 * 显式「默认」项（映射为 null = 基础布局），其后才是各方案。若这条被破坏，基础布局会再次
 * 失去入口——某个方案一旦有了专用布局，基础布局就既看不到也改不了。
 *
 * 同样重要的是按**位置**取标签、而不是读 selectedItem 文本：首项显示的是「默认」这类
 * 本地化文案，拿它当标签会去查 `entries["rime:默认"]` 这种不存在的键。
 */
class SubModeSpinnerSelectionTest {

    @Test
    fun positionZeroIsAlwaysTheDefaultItem() {
        val map = SubModeManager.buildSpinnerSelectionMap(listOf("wanxiang", "wanxiang_t9"))
        assertEquals("首位固定是「默认」项", 3, map.size)
        assertNull("位置 0 必须映射为 null（基础布局）", map[0])
        assertEquals("wanxiang", map[1])
        assertEquals("wanxiang_t9", map[2])
    }

    @Test
    fun emptyLabelsStillYieldsTheDefaultItem() {
        // Rime 布局即使一个方案标签都没解析出来，也必须能编辑基础布局
        val map = SubModeManager.buildSpinnerSelectionMap(emptyList())
        assertEquals(1, map.size)
        assertNull(map[0])
    }

    @Test
    fun labelOrderIsPreserved() {
        val labels = listOf("a", "b", "c", "d")
        val map = SubModeManager.buildSpinnerSelectionMap(labels)
        assertEquals(labels, map.drop(1))
        assertEquals(labels, map.filterNotNull())
    }

    @Test
    fun defaultItemIsFindableByLabelLookup() {
        // bindSubModeSpinner 用 indexOf(previewSubModeLabel) 决定选中位置：
        // 内存状态为 null 时必须命中位置 0（「默认」项），而不是回落成第一个方案。
        val labels = listOf("wanxiang", "wanxiang_t9")
        val map = SubModeManager.buildSpinnerSelectionMap(labels)
        assertEquals(0, map.indexOf(null))
        assertEquals(1, map.indexOf("wanxiang"))
        assertEquals(-1, map.indexOf("missing"))
    }

    @Test
    fun duplicateLabelKeepsFirstOccurrence() {
        // indexOf 取首个匹配，重复标签不应把「默认」项挤掉
        val map = SubModeManager.buildSpinnerSelectionMap(listOf("x", "x"))
        assertEquals(0, map.indexOf(null))
        assertEquals(1, map.indexOf("x"))
    }

    @Test
    fun positionZeroIsValidDespiteNullValue() {
        // 位置 0 的映射值就是 null（基础布局），但它是个**合法位置**。
        // 曾用 `getOrNull(position) ?: return` 判断，把"值恰好是 null"当成越界，
        // 于是「默认」项永远点不动：下拉框显示回到「默认」，编辑区却仍是原方案。
        val map = SubModeManager.buildSpinnerSelectionMap(listOf("wanxiang", "wanxiang_t9"))
        assertTrue("位置 0（null = 默认项）必须被判为有效", SubModeManager.isValidSpinnerPosition(map, 0))
        assertNull("位置 0 的取值应当仍是 null", map[0])
    }

    @Test
    fun outOfRangePositionsAreInvalid() {
        val map = SubModeManager.buildSpinnerSelectionMap(listOf("a"))
        assertFalse(SubModeManager.isValidSpinnerPosition(map, -1))
        assertFalse(SubModeManager.isValidSpinnerPosition(map, map.size))
    }

    @Test
    fun everyPositionOfTheMapIsValid() {
        // 边界：空标签时只有「默认」一项，位置 0 仍必须可选中
        val map = SubModeManager.buildSpinnerSelectionMap(emptyList())
        assertTrue(SubModeManager.isValidSpinnerPosition(map, 0))
    }
}
