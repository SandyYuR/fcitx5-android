/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 只钉住纯算术契约（本模块没有 Robolectric，`android.graphics.Path` 在 JVM 单测里不可执行）：
 * 「位移 = 工具栏圆角 + 编码区圆角（扣掉侧边距）」「反向弧半径 = 编码区圆角」。
 * 形状本身的正确性靠真机回归。
 */
class ContinuousCornerGeometryTest {

    @Test
    fun `radii are clamped to half of the shortest edge`() {
        assertEquals(20f, ContinuousCornerGeometry.clampRadius(48f, 100f, 40f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.clampRadius(-4f, 100f, 40f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.clampRadius(10f, -1f, 40f), 0f)
    }

    @Test
    fun `body offset is the sum of both radii when the pill starts at the card edge`() {
        assertEquals(25f, ContinuousCornerGeometry.preeditBodyOffset(10f, 0f, 15f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.preeditBodyOffset(0f, 0f, 0f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.preeditBodyOffset(-10f, 0f, -15f), 0f)
    }

    @Test
    fun `keyboard side padding shifts the body left by the same amount`() {
        // 内缩 8dp、工具栏圆角 12dp、编码区圆角 16dp：主体位移 = (12-8)+16
        assertEquals(20f, ContinuousCornerGeometry.preeditBodyOffset(12f, 8f, 16f), 0f)
    }

    @Test
    fun `padding beyond the toolbar radius degenerates to the preedit radius`() {
        // 侧边距把胶囊推到卡片圆角之外：反向弧从胶囊自身左缘起画，位移就是编码区圆角
        assertEquals(16f, ContinuousCornerGeometry.preeditBodyOffset(12f, 40f, 16f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.preeditBodyOffset(0f, 24f, 0f), 0f)
    }

    @Test
    fun `flare vertical radius is capped at half the pill height`() {
        assertEquals(18f, ContinuousCornerGeometry.flareVerticalRadius(18f, 64f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.flareVerticalRadius(-4f, 64f), 0f)
        assertEquals(15f, ContinuousCornerGeometry.flareVerticalRadius(48f, 30f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.flareVerticalRadius(48f, -1f), 0f)
    }
}
