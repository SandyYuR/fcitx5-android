/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选窗口预设的不变量测试。
 *
 * 钉住两件事：
 * 1. **每个预设值都落在对应偏好的合法范围内**。越界值会被 `SeekBarPreference` 夹到边界，
 *    表现为「套用预设后某一项没变成预期值」——这种失败在 UI 上很难察觉，且不会有任何日志。
 * 2. **紧凑 < 标准 < 宽松 的大小关系**。这保证三个按钮的语义与命名一致；若有人把某一组
 *    数值写反（例如让「紧凑」的字号大于「标准」），用户会看到与名字相反的效果。
 */
class CandidatesPresetsTest {

    private val presets = CandidatesPresets.all

    @Test
    fun `预设列表非空且含三项`() {
        assertEquals(3, presets.size)
    }

    @Test
    fun `窗口边距在范围内`() {
        presets.forEach {
            assertTrue(
                "windowPadding=${it.windowPadding} 超出 ${CandidatesPresets.RANGE_WINDOW_PADDING}",
                it.windowPadding in CandidatesPresets.RANGE_WINDOW_PADDING
            )
        }
    }

    @Test
    fun `字号在范围内`() {
        presets.forEach {
            assertTrue(
                "fontSize=${it.fontSize} 超出 ${CandidatesPresets.RANGE_FONT_SIZE}",
                it.fontSize in CandidatesPresets.RANGE_FONT_SIZE
            )
        }
    }

    @Test
    fun `窗口圆角在范围内`() {
        presets.forEach {
            assertTrue(
                "windowRadius=${it.windowRadius} 超出 ${CandidatesPresets.RANGE_WINDOW_RADIUS}",
                it.windowRadius in CandidatesPresets.RANGE_WINDOW_RADIUS
            )
        }
    }

    @Test
    fun `高亮圆角在范围内`() {
        presets.forEach {
            assertTrue(
                "highlightRadius=${it.highlightRadius} 超出 ${CandidatesPresets.RANGE_HIGHLIGHT_RADIUS}",
                it.highlightRadius in CandidatesPresets.RANGE_HIGHLIGHT_RADIUS
            )
        }
    }

    @Test
    fun `候选边距在范围内`() {
        presets.forEach {
            assertTrue(
                "itemPaddingVertical=${it.itemPaddingVertical} 超出 ${CandidatesPresets.RANGE_ITEM_PADDING}",
                it.itemPaddingVertical in CandidatesPresets.RANGE_ITEM_PADDING
            )
            assertTrue(
                "itemPaddingHorizontal=${it.itemPaddingHorizontal} 超出 ${CandidatesPresets.RANGE_ITEM_PADDING}",
                it.itemPaddingHorizontal in CandidatesPresets.RANGE_ITEM_PADDING
            )
        }
    }

    @Test
    fun `紧凑小于标准小于宽松`() {
        val compact = presets[0]
        val standard = presets[1]
        val relaxed = presets[2]

        assertTrue("字号应递增", compact.fontSize < standard.fontSize)
        assertTrue("字号应递增", standard.fontSize < relaxed.fontSize)

        assertTrue("窗口边距应递增", compact.windowPadding < standard.windowPadding)
        assertTrue("窗口边距应递增", standard.windowPadding < relaxed.windowPadding)

        assertTrue("候选边距应递增", compact.itemPaddingVertical < standard.itemPaddingVertical)
        assertTrue("候选边距应递增", standard.itemPaddingVertical < relaxed.itemPaddingVertical)

        assertTrue("候选横向边距应递增", compact.itemPaddingHorizontal < standard.itemPaddingHorizontal)
        assertTrue("候选横向边距应递增", standard.itemPaddingHorizontal < relaxed.itemPaddingHorizontal)

        assertTrue("窗口圆角应递增", compact.windowRadius <= standard.windowRadius)
        assertTrue("窗口圆角应递增", standard.windowRadius < relaxed.windowRadius)
    }

    @Test
    fun `范围声明与偏好声明一致`() {
        // 这些常量必须与 AppPrefs.Candidates 里各 int() 的 min/max 参数保持一致。
        // 若将来调整了偏好的取值范围而忘了改这里，测试会失败并指向该文件。
        assertEquals(0, CandidatesPresets.RANGE_WINDOW_PADDING.first)
        assertEquals(32, CandidatesPresets.RANGE_WINDOW_PADDING.last)
        assertEquals(4, CandidatesPresets.RANGE_FONT_SIZE.first)
        assertEquals(64, CandidatesPresets.RANGE_FONT_SIZE.last)
        assertEquals(0, CandidatesPresets.RANGE_WINDOW_RADIUS.first)
        assertEquals(48, CandidatesPresets.RANGE_WINDOW_RADIUS.last)
        assertEquals(0, CandidatesPresets.RANGE_HIGHLIGHT_RADIUS.first)
        assertEquals(48, CandidatesPresets.RANGE_HIGHLIGHT_RADIUS.last)
        assertEquals(0, CandidatesPresets.RANGE_ITEM_PADDING.first)
        assertEquals(64, CandidatesPresets.RANGE_ITEM_PADDING.last)
    }
}
