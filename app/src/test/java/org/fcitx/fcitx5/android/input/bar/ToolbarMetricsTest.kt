/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarMetricsTest {

    @Test
    fun `默认百分比保持原样`() {
        assertEquals(1f, ToolbarMetrics.scale(ToolbarMetrics.DEFAULT_PERCENT), 1e-6f)
        assertEquals(40, ToolbarMetrics.heightDp(ToolbarMetrics.DEFAULT_PERCENT))
        assertEquals(16f, ToolbarMetrics.BASE_LABEL_SIZE * ToolbarMetrics.scale(100), 1e-6f)
    }

    @Test
    fun `一百百分比时图标与留白逐像素等于原实现`() {
        // 回归保险：100% 必须和改动前完全一致（20dp 有效图标 + 10dp 留白 + 40dp 图标方框；按钮足迹仍为44dp）。
        assertEquals(20, ToolbarMetrics.iconSizeDp(100))
        assertEquals(10, ToolbarMetrics.iconPaddingDp(100))
        assertEquals(40, ToolbarMetrics.iconBoxDp(100))
        assertEquals(44, ToolbarMetrics.BUTTON_FOOTPRINT_DP)
    }

    @Test
    fun `调大工具栏不会放大按钮格子因此可见个数不变`() {
        // 这是「200% 时中间只剩 3 个按钮」那个 bug 的守门断言：格子宽度必须恒定，
        // 行布局的平分阈值也必须恒定。
        for (percent in 80..200) {
            assertEquals(44, ToolbarMetrics.BUTTON_FOOTPRINT_DP)
            assertEquals(40, ToolbarMetrics.MIN_BUTTON_WIDTH_DP)
        }
    }

    @Test
    fun `两百百分比把栏高度翻倍`() {
        assertEquals(2f, ToolbarMetrics.scale(200), 1e-6f)
        assertEquals(80, ToolbarMetrics.heightDp(200))
        assertEquals(32f, ToolbarMetrics.BASE_LABEL_SIZE * ToolbarMetrics.scale(200), 1e-6f)
    }

    @Test
    fun `八十百分比把栏整体压到八成`() {
        assertEquals(0.8f, ToolbarMetrics.scale(80), 1e-6f)
        assertEquals(32, ToolbarMetrics.heightDp(80))
    }

    @Test
    fun `越界百分比被夹住而不是放大出界`() {
        assertEquals(ToolbarMetrics.MIN_PERCENT, ToolbarMetrics.clampPercent(0))
        assertEquals(ToolbarMetrics.MIN_PERCENT, ToolbarMetrics.clampPercent(-40))
        assertEquals(ToolbarMetrics.MAX_PERCENT, ToolbarMetrics.clampPercent(999))
        assertEquals(80, ToolbarMetrics.heightDp(999))
    }

    // 这里**刻意没有**「候选项字号随工具栏缩放」的用例：候选项字号已与工具栏解耦，
    // 完全由「字体设定」的 `cand_font` 决定（ToolbarMetrics 不再提供任何候选字号系数）。
    // 想放大候选字：先调高工具栏腾出高度，再去字体设定调大 cand_font。

    @Test
    fun `图标在任何百分比下都不撑破格子`() {
        for (percent in 0..400) {
            val box = ToolbarMetrics.iconBoxDp(percent)
            assertTrue("$percent%: box=$box", box <= ToolbarMetrics.BUTTON_FOOTPRINT_DP)
            val pad = ToolbarMetrics.iconPaddingDp(percent)
            assertTrue("$percent%: pad=$pad", pad >= ToolbarMetrics.MIN_ICON_GAP_DP)
            assertTrue("$percent%: icon", ToolbarMetrics.iconSizeDp(percent) >= 1)
        }
        // 200% 时图标吃掉留白：20dp→40dp，留白 10dp→2dp，格子仍是 44dp。
        assertEquals(40, ToolbarMetrics.iconSizeDp(200))
        assertEquals(2, ToolbarMetrics.iconPaddingDp(200))
        assertEquals(44, ToolbarMetrics.iconBoxDp(200))
    }

    @Test
    fun `方形按钮把栏高当格子宽时图标不超过栏高`() {
        // 工具栏两侧/展开按钮是正方形，边长 = 栏高。80% 时栏高 32dp，
        // 200% 时 80dp——图标都必须放得进这个正方形。
        for (percent in 80..200) {
            val slot = ToolbarMetrics.heightDp(percent)
            val box = ToolbarMetrics.iconBoxDp(percent, slotDp = slot)
            assertTrue("$percent%: box=$box slot=$slot", box <= slot)
        }
    }

    @Test
    fun `高度始终为正`() {
        for (percent in -200..400) {
            assertTrue(ToolbarMetrics.heightDp(percent) >= 1)
        }
    }
}
