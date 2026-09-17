/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CandidateWord

class CandidateViewHolder(val ui: CandidateItemUi) : RecyclerView.ViewHolder(ui.root) {
    var idx = -1
        private set

    var listenersAttached = false
    var lastMinWidth = Int.MIN_VALUE
    var lastFlexGrow = Float.NaN

    var candidate: CandidateWord = CandidateWord.Empty
        private set

    fun update(newIndex: Int, newCandidate: CandidateWord) {
        idx = newIndex
        if (candidate != newCandidate) {
            candidate = newCandidate
            ui.updateCandidate(newCandidate)
        }
    }

    /**
     * 只刷新全局下标，不动候选内容。
     *
     * 窗口起点（`indexOffset` / `offset`）变化后，已绑定项的 `idx` 全部失效，但 DiffUtil
     * 可能判定“内容没变”而不重新绑定。此时既不能写内容（新一页数据可能还没到，`peek`
     * 拿到的仍是旧页），也不能放任 `idx` 停在旧值，于是只更新下标。
     */
    fun updateIndex(newIndex: Int) {
        idx = newIndex
    }

    /**
     * 取用时刻的全局候选下标。
     *
     * [idx] 是绑定时算出来的缓存值，只在 `onBindViewHolder` 里更新。DiffUtil 判定内容没变
     * 时 RecyclerView 只挪动 ViewHolder 而不重新绑定（重复候选、窗口平移后文本恰好相同
     * 都会命中），缓存值就会停在旧起点上，点击/长按会选到别的候选。这里优先用
     * RecyclerView 自己维护的位置重新计算，位置未知时才退回缓存值。
     */
    fun currentIndex(offset: Int): Int = CandidateIndexResolver.resolve(
        bindingAdapterPosition,
        offset,
        idx,
    )

    fun clear() {
        update(-1, CandidateWord.Empty)
    }
}
