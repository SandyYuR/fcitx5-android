/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMoveHandlePlacementTest {

    // 容器高 2000，键盘高 600，把手+间距：上方需 80，下方需 120。
    private fun placement(
        keyboardTop: Float,
        currentlyAbove: Boolean? = null
    ) = FloatingMoveHandlePlacement.placement(
        keyboardTop = keyboardTop,
        keyboardHeight = 600f,
        containerHeight = 2000f,
        needAbove = 80f,
        needBelow = 120f,
        currentlyAbove = currentlyAbove
    )

    @Test
    fun roomyOnBothSides_staysAboveByDefault() {
        // 顶部空间 500、底部空间 900：两侧都放得下，首次摆放回落上方（旧行为）。
        assertEquals(FloatingMoveHandlePlacement.ABOVE, placement(500f))
        assertEquals(FloatingMoveHandlePlacement.ABOVE, placement(500f, currentlyAbove = true))
    }

    @Test
    fun roomyOnBothSides_keepsBelowForHysteresis() {
        // 两侧都放得下但当前已在下方：保持下方，避免临界抖动时来回横跳。
        assertEquals(FloatingMoveHandlePlacement.BELOW, placement(500f, currentlyAbove = false))
    }

    @Test
    fun tooCloseToTop_flipsBelow() {
        // 用户报障场景：键盘拖到顶部，顶部只剩 10px（< 80），底部空间充足 → 翻到底部。
        assertEquals(FloatingMoveHandlePlacement.BELOW, placement(10f))
        assertEquals(FloatingMoveHandlePlacement.BELOW, placement(0f, currentlyAbove = true))
        // 恰好顶边（顶部空间 0）同样翻转。
        assertEquals(FloatingMoveHandlePlacement.BELOW, placement(0f))
    }

    @Test
    fun tooCloseToBottom_flipsAbove() {
        // 键盘拖到底部：底部剩余 50（< 120），顶部充足 → 回到上方。
        // keyboardTop = 2000 - 600 - 50 = 1350。
        assertEquals(FloatingMoveHandlePlacement.ABOVE, placement(1350f, currentlyAbove = false))
        assertEquals(FloatingMoveHandlePlacement.ABOVE, placement(1350f))
    }

    @Test
    fun neitherSideFits_keepsCurrentPosition() {
        // 键盘几乎占满全高（高 1950）：上下都放不下，保持现状，不乱跳。
        fun cramped(currentlyAbove: Boolean?) = FloatingMoveHandlePlacement.placement(
            keyboardTop = 20f,
            keyboardHeight = 1950f,
            containerHeight = 2000f,
            needAbove = 80f,
            needBelow = 120f,
            currentlyAbove = currentlyAbove
        )
        assertEquals(FloatingMoveHandlePlacement.ABOVE, cramped(true))
        assertEquals(FloatingMoveHandlePlacement.BELOW, cramped(false))
        // 首次摆放回落上方（旧行为）。
        assertEquals(FloatingMoveHandlePlacement.ABOVE, cramped(null))
    }

    @Test
    fun boundaryConditions() {
        // 恰好放下（== need）算放得下；两侧都放得下时保持现状（迟滞）。
        assertEquals(FloatingMoveHandlePlacement.BELOW, placement(80f, currentlyAbove = false))
        assertEquals(FloatingMoveHandlePlacement.ABOVE, placement(1280f, currentlyAbove = true))
        // 上方恰好放下、下方放不下（高键盘）：上方临界值仍算放得下。
        assertEquals(
            FloatingMoveHandlePlacement.ABOVE,
            FloatingMoveHandlePlacement.placement(
                keyboardTop = 80f,
                keyboardHeight = 1900f,
                containerHeight = 2000f,
                needAbove = 80f,
                needBelow = 120f,
                currentlyAbove = false
            )
        )
        // 下方恰好放下、上方放不下：下方临界值仍算放得下。
        assertEquals(
            FloatingMoveHandlePlacement.BELOW,
            FloatingMoveHandlePlacement.placement(
                keyboardTop = 50f,
                keyboardHeight = 1830f,
                containerHeight = 2000f,
                needAbove = 80f,
                needBelow = 120f,
                currentlyAbove = true
            )
        )
    }
}
