/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/**
 * 候选窗口的「快速预设」。
 *
 * 背景：候选窗口有 7 个纯数值的外观参数（边距、字号、圆角……），原先它们在设置页里
 * 平铺展开，用户得先理解 dp/sp 才能动手。预设给出一组常用搭配，一次套用全部数值；
 * 之后仍可逐项微调（预设不是「模式」，只是写值的一次性动作）。
 *
 * **不做「当前属于哪个预设」的状态判定**：那会给每个参数都引入「是用户改的还是预设写的」
 * 这类持久化难题，而收益只是让某一项显示为「已选」。用户套用后自己微调是正常用法，
 * 界面不该因此显示成「自定义」而让人以为出了错。
 *
 * 数值范围与 `AppPrefs.Candidates` 里声明的 min/max 一致，由
 * `CandidatesPresetsTest` 钉住——将来有人调整范围或新增预设而越界，测试会失败
 * （越界值会被 SeekBar 夹到边界，表现为「预设没生效」，很难排查）。
 */
object CandidatesPresets {

    data class Preset(
        @StringRes val titleRes: Int,
        val windowPadding: Int,
        val fontSize: Int,
        val windowRadius: Int,
        val highlightRadius: Int,
        val itemPaddingVertical: Int,
        val itemPaddingHorizontal: Int
    )

    /* 与 AppPrefs.Candidates 中各 int() 的 min/max 参数保持一致。 */
    val RANGE_WINDOW_PADDING = 0..32
    val RANGE_FONT_SIZE = 4..64
    val RANGE_WINDOW_RADIUS = 0..48
    val RANGE_HIGHLIGHT_RADIUS = 0..48
    val RANGE_ITEM_PADDING = 0..64

    /**
     * 预设列表。第一项「标准」即现有默认值，套用它等于恢复默认外观。
     */
    val all: List<Preset> = listOf(
        Preset(
            titleRes = R.string.candidates_preset_compact,
            windowPadding = 2,
            fontSize = 16,
            windowRadius = 0,
            highlightRadius = 2,
            itemPaddingVertical = 1,
            itemPaddingHorizontal = 2
        ),
        Preset(
            titleRes = R.string.candidates_preset_standard,
            windowPadding = 4,
            fontSize = 20,
            windowRadius = 0,
            highlightRadius = 2,
            itemPaddingVertical = 2,
            itemPaddingHorizontal = 4
        ),
        Preset(
            titleRes = R.string.candidates_preset_relaxed,
            windowPadding = 8,
            fontSize = 24,
            windowRadius = 12,
            highlightRadius = 8,
            itemPaddingVertical = 6,
            itemPaddingHorizontal = 12
        )
    )
}
