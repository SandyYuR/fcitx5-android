/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class CandidateIndexBadgePosition(override val stringRes: Int) : ManagedPreferenceEnum {
    TopLeft(R.string.top_left),
    TopRight(R.string.top_right),
    BottomRight(R.string.bottom_right),
    BottomLeft(R.string.bottom_left),
}

/**
 * 候选项序号角标显示什么。
 *
 * - [SequenceOneBased]：候选栏内从 1 起的序号（历史行为；`position + 1`）。
 * - [SequenceZeroBased]：候选栏内从 0 起的序号（`position`）。
 * - [EngineLabel]：引擎给出的选词标签（`CandidateWord.label`），方案里写了
 *   `alternative_select_labels` / `select_keys` 就原样跟随；没写则回退到引擎默认
 *   的 `(i + 1) % 10`（第 10 个是 `0`，与 Rime 选词键一致）。
 *
 * 存的是枚举常量名，顺序可调、中间插值不失效。
 */
enum class CandidateIndexBadgeContent(override val stringRes: Int) : ManagedPreferenceEnum {
    SequenceOneBased(R.string.candidate_index_badge_content_sequence_one_based),
    SequenceZeroBased(R.string.candidate_index_badge_content_sequence_zero_based),
    EngineLabel(R.string.candidate_index_badge_content_engine_label),
}
