/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空格键划动手势的归属规则。
 *
 * 两种模式互斥且开关优先级更高：
 * - 开关开启 → 光标手势接管，按键配置的划动动作**不生效**；
 * - 开关关闭 → 配了划动动作才接管。
 *
 * 这条优先级是本功能最容易在后续改动中被改反的一处（把开关与动作写成"或"之后
 * 又顺手让动作优先），所以单独钉住。
 */
class SpaceSwipeModeTest {

    private val action = MacroAction(
        listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("Left"))))
    )

    @Test
    fun cursorModeTakesOverEvenWhenSwipeActionConfigured() {
        assertTrue(
            "开关开启时光标手势必须参与，即使按键配了划动动作",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = true, swipeAction = action)
        )
    }

    @Test
    fun swipeActionTakesOverWhenCursorModeDisabled() {
        assertTrue(
            "开关关闭且配了划动动作时，划动动作必须生效",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = false, swipeAction = action)
        )
    }

    @Test
    fun cursorModeAloneStillEnablesSwipe() {
        assertTrue(
            "开关开启但没有划动动作时光标手势仍要生效",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = true, swipeAction = null)
        )
    }

    @Test
    fun neitherModeLeavesSwipeDisabled() {
        assertFalse(
            "开关关闭且没有划动动作时空格键不参与手势，避免滑动取消长按（按住说话）",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = false, swipeAction = null)
        )
    }
}
