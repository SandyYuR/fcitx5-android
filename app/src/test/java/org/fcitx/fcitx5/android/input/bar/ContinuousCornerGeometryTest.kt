/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `non-finite radii fall back to square corners`() {
        // coerceAtLeast 对 NaN 判定为 false 会原样放过 NaN，而 NaN 半径会让
        // Outline.setRoundRect 的形状非法——脏数据只能退成直角。
        assertEquals(0f, ContinuousCornerGeometry.clampRadius(Float.NaN, 100f, 40f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.clampRadius(Float.POSITIVE_INFINITY, 0f, 40f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.clampRadius(Float.NEGATIVE_INFINITY, 100f, 40f), 0f)
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
    fun `legacy uniform radius takes the larger corner and never goes negative`() {
        // API < 30 只能用 setRoundRect（四角同半径）：上下不等时取较大者，
        // 让用户显式设置的那一侧圆角保持原样。
        assertEquals(36f, ContinuousCornerGeometry.legacyUniformRadius(36f, 0f), 0f)
        assertEquals(36f, ContinuousCornerGeometry.legacyUniformRadius(0f, 36f), 0f)
        assertEquals(48f, ContinuousCornerGeometry.legacyUniformRadius(48f, 10f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.legacyUniformRadius(-4f, -8f), 0f)
    }

    @Test
    fun `legacy uniform radius is still clamped to half of the shortest edge`() {
        // 退化后的半径必须再夹一次：Outfit 的 setRoundRect 不自己做钳制。
        assertEquals(
            12f,
            ContinuousCornerGeometry.clampRadius(
                ContinuousCornerGeometry.legacyUniformRadius(40f, 8f), 300f, 24f
            ),
            0f
        )
    }

    @Test
    fun `path outline is only for api 30 and above`() {
        // 钉住 01d0dfee 的崩溃根因：Outline.setPath 是 API 30 才加入的方法。
        // Android 9（28）与 Android 10（29）都必须走 setRoundRect 退化路径，
        // 30 起才允许 setPath——门槛一旦被改回 Q，前两条断言先红。
        assertFalse(ContinuousCornerGeometry.usesPathOutline(Build.VERSION_CODES.P))
        assertFalse(ContinuousCornerGeometry.usesPathOutline(Build.VERSION_CODES.Q))
        assertTrue(ContinuousCornerGeometry.usesPathOutline(Build.VERSION_CODES.R))
        assertTrue(ContinuousCornerGeometry.usesPathOutline(Build.VERSION_CODES.S))
    }

    @Test
    fun `flare vertical radius is capped at half the pill height`() {
        assertEquals(18f, ContinuousCornerGeometry.flareVerticalRadius(18f, 64f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.flareVerticalRadius(-4f, 64f), 0f)
        assertEquals(15f, ContinuousCornerGeometry.flareVerticalRadius(48f, 30f), 0f)
        assertEquals(0f, ContinuousCornerGeometry.flareVerticalRadius(48f, -1f), 0f)
    }
}
