/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向性划动标签（上滑标签 / 下滑标签）的结构约束。
 *
 * 这里钉住的是一条用户可见的不变式：**标签显示在哪边，哪边就是触发方向**。
 * 历史实现让主题的「标点位置」决定单侧标签的摆放位置，于是换了主题就会把一个下滑键
 * 画成上滑、副标签也跑到上方；这批断言保证构造期就不会再出现方向与槽位错配。
 *
 * 纯数据断言，不依赖 Android 运行时。
 */
class DirectionalSwipeLabelDefTest {

    @Test
    fun loneDownLabelKeepsItsPhysicalDirection() {
        val key = MacroKey(
            label = "z",
            swipeDownLabel = "撤销",
            tap = MacroAction(listOf(MacroStep.Text("z")))
        )

        assertTrue("有方向性标签，布局分支必须走方向模式", key.appearance.directionalSwipeLabels)
        val alt = key.appearance as KeyDef.Appearance.AltText
        assertEquals("上滑槽位保持为空", "", alt.altText)
        assertEquals("下滑标签落在下滑槽位", "撤销", alt.altText1)
    }

    @Test
    fun loneUpLabelKeepsItsPhysicalDirection() {
        val key = MacroKey(
            label = "z",
            swipeUpLabel = "撤销",
            tap = MacroAction(listOf(MacroStep.Text("z")))
        )

        val alt = key.appearance as KeyDef.Appearance.AltText
        assertEquals("上滑标签落在上滑槽位", "撤销", alt.altText)
        assertNull("没有配下滑标签时下滑槽位为空", alt.altText1)
    }

    /**
     * 只配了标签、没有配动作时不能凭空生成方向宏：那会让一个纯提示标签变成真实动作。
     */
    @Test
    fun labelsAloneDoNotCreateSwipeActions() {
        val key = MacroKey(
            label = "z",
            swipeDownLabel = "撤销",
            tap = MacroAction(listOf(MacroStep.Text("z")))
        )

        val swipe = key.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()
        assertNull("没有划动宏就不该注册划动行为", swipe)
    }

    /** 上下宏都配好时不受全局方向偏好限制（overrideDefaults 由构造期决定）。 */
    @Test
    fun explicitDirectionalMacrosIgnoreGlobalPreference() {
        val up = MacroAction(listOf(MacroStep.Text("up")))
        val down = MacroAction(listOf(MacroStep.Text("down")))
        val key = MacroKey(
            label = "Enter",
            tap = MacroAction(listOf(MacroStep.Text("tap"))),
            swipeUp = up,
            swipeDown = down
        )

        val swipe = key.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertTrue("显式上下划宏不受全局方向偏好限制", swipe.overrideDefaults)
        assertEquals(up, swipe.upMacro)
        assertEquals(down, swipe.downMacro)
    }
}
