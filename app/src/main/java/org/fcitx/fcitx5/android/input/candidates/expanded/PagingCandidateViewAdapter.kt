/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.graphics.Typeface
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.font.FontProviders

open class PagingCandidateViewAdapter(val theme: Theme) :
    PagingDataAdapter<CandidateWord, CandidateViewHolder>(diffCallback) {

    // Cache candidate/comment fonts and refresh only when font configuration changes.
    private var candFont: Typeface? = FontProviders.resolveTypeface("cand_font", null)
    private var commentFont: Typeface? = resolveCommentFont()

    private fun resolveCommentFont(): Typeface? = FontProviders.resolveCommentTypeface(null)

    private fun refreshCandidateFontIfNeeded() {
        if (FontProviders.needsRefresh()) {
            candFont = FontProviders.resolveTypeface("cand_font", null)
            commentFont = resolveCommentFont()
        }
    }

    companion object {
        /**
         * 以稳定候选身份做 diff，见 [PagingCandidateDiff]。
         *
         * 恒为 `false` 的旧实现让每次 offset 刷新都全量 rebind；改成内容比较后，
         * “同一个窗口重新取到同一批候选”不再触发任何 rebind。
         */
        private val diffCallback = object : DiffUtil.ItemCallback<CandidateWord>() {
            override fun areItemsTheSame(oldItem: CandidateWord, newItem: CandidateWord) =
                PagingCandidateDiff.areItemsTheSame(oldItem, newItem)

            override fun areContentsTheSame(oldItem: CandidateWord, newItem: CandidateWord) =
                PagingCandidateDiff.areContentsTheSame(oldItem, newItem)
        }
    }

    var offset = 0
        private set

    /**
     * 最近一次真正改动过候选数据的 generation，见
     * [org.fcitx.fcitx5.android.input.candidates.expanded.CandidateGenerationTracker]。
     * 只在 [refreshWithOffset] 里随数据一起前进。
     */
    var generation = 0L
        private set

    /**
     * 按新的分页起点重建数据。
     *
     * @param offset 已加载数据的全局起点（`idx = position + offset` 依赖它，不能省）。
     * @param generation 候选内容版本；offset 一样时也用它判断“这只是重复请求”。
     */
    fun refreshWithOffset(offset: Int, generation: Long) {
        refreshCandidateFontIfNeeded()
        if (offset == this.offset && generation == this.generation) return
        this.offset = offset
        this.generation = generation
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme, candFont, commentFont)
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        refreshCandidateFontIfNeeded()
        holder.ui.applyConfiguredTypeface(candFont)
        holder.ui.applyConfiguredCommentTypeface(commentFont)
        val candidate = getItem(position) ?: CandidateWord.Empty
        holder.update(position + offset, candidate)
    }
}
