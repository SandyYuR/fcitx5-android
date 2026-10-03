/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 当前生效的键盘排列：**普通**还是**分体**。
 *
 * 只有两种。横屏与竖屏曾经各占一种形态，但它们对"怎么排键"的要求其实是同一件事——
 * 屏幕变宽只是把同一套行拉伸得更宽，真正需要另一套行集合的只有分体：分体时每行要在
 * 某处断成左右两半，断点在哪、两侧各放什么键，是合体时完全不需要考虑的问题。
 * 于是横屏那一维交给既有的 `keyboard_height_percent_landscape` 与全局边距设置处理，
 * 这里只留分体与否一个正交条件。
 *
 * 形态只影响**行集合**，不影响键盘高度。
 *
 * [Docked] 没有对应的 JSON 条目（它就是布局本体，也是所有形态的最终回退）；
 * [Split] 对应布局 JSON 里的 `__variant__:split` 条目，见
 * [org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils.VARIANT_SUBMODE_PREFIX]。
 */
enum class LayoutVariant(val jsonKey: String?) {
    /** 普通键盘（默认形态，也是分体缺省时的最终回退）。 */
    Docked(null),

    /** 分体键盘。 */
    Split("split");

    val isSplit: Boolean
        get() = this == Split

    /**
     * 该形态缺省时依次尝试的形态，最后一项总是 [Docked]（布局本体）。
     */
    fun fallbackChain(): List<LayoutVariant> = when (this) {
        Docked -> listOf(Docked)
        Split -> listOf(Split, Docked)
    }

    companion object {
        fun of(split: Boolean): LayoutVariant = if (split) Split else Docked

        fun fromJsonKey(key: String?): LayoutVariant? =
            entries.firstOrNull { it.jsonKey != null && it.jsonKey == key }
    }
}
