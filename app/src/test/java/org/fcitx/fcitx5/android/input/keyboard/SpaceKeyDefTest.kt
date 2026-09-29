/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空格键 KeyDef 的结构约束。
 *
 * 空格键的划动与「长按重复」都由**设置**驱动、在 BaseKeyboard 里按视图绑定，因此
 * 绝不能写进 [KeyDef.behaviors]——这条如果被改回去，表现出来是"改了设置没反应"：
 * - `Behavior.Swipe` 会把 `swipeThresholdX` 改写成 `disabledSwipeThreshold`，直接废掉
 *   「划动空格键以移动光标」的横向移动；
 * - `Behavior.Repeat` 会让缓存行带着创建时的值，设置改变不会重建布局 → 开关失效。
 *
 * 这是纯数据断言，不依赖 Android 运行时。
 */
class SpaceKeyDefTest {

    private val swipeAction = MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("Left")))))

    @Test
    fun spaceKeyBehavesAsTapAndLongPressOnly() {
        val key = SpaceKey()
        assertTrue("轻点仍需上屏空格", key.behaviors.any { it is KeyDef.Behavior.Press })
        assertTrue("长按行为由 SpaceLongPressAction 分发", key.behaviors.any { it is KeyDef.Behavior.LongPress })
        assertEquals(2, key.behaviors.size)
    }

    @Test
    fun spaceKeyNeverRegistersSwipeBehavior() {
        val plain = SpaceKey()
        val withSwipe = SpaceKey(swipe = swipeAction, swipeLabel = "←")
        assertTrue(
            "划动必须留在设置驱动的手势监听器里，不能注册 Behavior.Swipe",
            plain.behaviors.none { it is KeyDef.Behavior.Swipe } &&
                withSwipe.behaviors.none { it is KeyDef.Behavior.Swipe }
        )
    }

    @Test
    fun spaceKeyNeverRegistersRepeatBehavior() {
        val plain = SpaceKey()
        val withSwipe = SpaceKey(swipe = swipeAction, swipeLabel = "←")
        assertTrue(
            "长按重复必须留在设置驱动的视图绑定里，不能注册 Behavior.Repeat",
            plain.behaviors.none { it is KeyDef.Behavior.Repeat } &&
                withSwipe.behaviors.none { it is KeyDef.Behavior.Repeat }
        )
    }

    /** 划动标签非空时改用 AltText 外观，键面才会显示划动提示。 */
    @Test
    fun swipeLabelSwitchesToAltTextAppearance() {
        assertTrue(SpaceKey().appearance is KeyDef.Appearance.Text)
        val labelled = SpaceKey(swipe = swipeAction, swipeLabel = "←").appearance
        assertTrue(labelled is KeyDef.Appearance.AltText)
        assertEquals("←", (labelled as KeyDef.Appearance.AltText).altText)
    }
}
