/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 把候选词按字形簇（grapheme cluster）拆成单字，供候选词上滑选字弹窗使用。
 *
 * 用 [BreakIterator] 而不是按 UTF-16 code unit 切分，保证增补字符（emoji、
 * 生僻字）和带组合标记的字符（é）不会被拆散。
 */
internal fun String.candidateCharacters(): List<String> {
    if (isBlank()) return emptyList()

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply {
        setText(this@candidateCharacters)
    }
    return buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            add(substring(start, end))
            start = end
            end = iterator.next()
        }
    }
}

internal data class CandidateCharacterGrid(
    val rows: Int,
    val columns: Int
)

/**
 * 按单字数量选择选字弹窗的网格尺寸：3 个以下排成单行，6 个用 2x3，
 * 其余向上取整为正方形。
 */
internal fun candidateCharacterGrid(characterCount: Int): CandidateCharacterGrid {
    val count = characterCount.coerceAtLeast(1)
    if (count < 4) return CandidateCharacterGrid(rows = 1, columns = count)
    if (count == 6) return CandidateCharacterGrid(rows = 2, columns = 3)

    val size = ceil(sqrt(count.toDouble())).toInt()
    return CandidateCharacterGrid(rows = size, columns = size)
}

/**
 * 手指抬起位置落在弹窗外时取消选字（不提交任何字符）。
 *
 * 调用方先把锚点坐标换算成弹窗内坐标再传入，见
 * [CandidateCharacterPopup.updateFocus]。
 */
internal fun isInsideCandidateCharacterPopup(
    x: Float,
    y: Float,
    width: Int,
    height: Int
): Boolean = x >= 0f && x < width && y >= 0f && y < height
