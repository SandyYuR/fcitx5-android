/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置搜索的匹配规则测试。
 *
 * 这些用例钉住的是**用户可感知的搜索行为**，不是实现细节：
 * 空查询不该匹配一切、多词要收敛、大小写不敏感、
 * 以及「别名能搜到」这条决定索引可用性的约定。
 */
class SettingsSearchQueryTest {

    /* ===== 切词 ===== */

    @Test
    fun `空查询切成空列表`() {
        assertEquals(emptyList<String>(), SettingsSearchQuery.terms(""))
        assertEquals(emptyList<String>(), SettingsSearchQuery.terms("   "))
    }

    @Test
    fun `按空白切词并丢弃空段`() {
        assertEquals(listOf("空格", "长按"), SettingsSearchQuery.terms("空格  长按"))
        assertEquals(listOf("space", "press"), SettingsSearchQuery.terms("  space press  "))
    }

    @Test
    fun `切词后统一小写`() {
        assertEquals(listOf("radius"), SettingsSearchQuery.terms("Radius"))
    }

    /* ===== 匹配 ===== */

    @Test
    fun `空查询不匹配任何文本`() {
        // 关键约定：空查询必须返回 false，否则调用方会在「什么都没搜」时
        // 渲染出整份目录，看起来像搜索结果被撑爆。
        assertFalse(SettingsSearchQuery.matches("键盘高度", ""))
        assertFalse(SettingsSearchQuery.matches("键盘高度", "   "))
    }

    @Test
    fun `子串命中即可`() {
        assertTrue(SettingsSearchQuery.matches("键盘高度", "高度"))
        assertTrue(SettingsSearchQuery.matches("键盘高度", "键盘"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(SettingsSearchQuery.matches("Key Radius", "radius"))
        assertTrue(SettingsSearchQuery.matches("key radius", "RADIUS"))
        assertTrue(SettingsSearchQuery.matches("Key Radius", "KeY"))
    }

    @Test
    fun `多词按与语义收敛`() {
        val hay = "空格键长按行为 space long press"
        // 两个词都命中 → 命中
        assertTrue(SettingsSearchQuery.matches(hay, "空格 长按"))
        assertTrue(SettingsSearchQuery.matches(hay, "space press"))
        // 只要有一个词不命中 → 整体不命中（这正是多词输入的价值：
        // 避免「空格」「长按」各自命中一大片无关项）
        assertFalse(SettingsSearchQuery.matches(hay, "空格 振动"))
        assertFalse(SettingsSearchQuery.matches(hay, "space vibration"))
    }

    @Test
    fun `词序不影响结果`() {
        val hay = "空格键长按行为"
        assertTrue(SettingsSearchQuery.matches(hay, "空格 长按"))
        assertTrue(SettingsSearchQuery.matches(hay, "长按 空格"))
    }

    @Test
    fun `不命中时返回假`() {
        assertFalse(SettingsSearchQuery.matches("键盘高度", "候选"))
        assertFalse(SettingsSearchQuery.matches("", "任意"))
    }

    /* ===== 与条目的组合：别名必须能搜到 ===== */

    @Test
    fun `别名参与匹配`() {
        // 复刻 SettingsSearchEntry.matches 的拼接方式，验证「中文名 + 英文别名」
        // 这条约定真的成立——索引的价值就靠它。
        val haystack = "键盘高度 应用设置 · 键盘 height gaodu"
        assertTrue(SettingsSearchQuery.matches(haystack, "height"))
        assertTrue(SettingsSearchQuery.matches(haystack, "gaodu"))
        assertTrue(SettingsSearchQuery.matches(haystack, "键盘高度"))
        // 层级词也能带出子项
        assertTrue(SettingsSearchQuery.matches(haystack, "键盘"))
    }

    @Test
    fun `中文与英文混输可命中`() {
        val haystack = "按键振动时长 应用设置 · 键盘 vibration 毫秒"
        assertTrue(SettingsSearchQuery.matches(haystack, "振动 vibration"))
        assertTrue(SettingsSearchQuery.matches(haystack, "毫秒"))
    }
}
