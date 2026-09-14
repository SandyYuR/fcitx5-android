/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.graphics.Typeface
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import androidx.tracing.trace
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {

    // Cache candidate/comment fonts and refresh only when font configuration changes.
    private var candFont: Typeface? = FontProviders.resolveTypeface("cand_font", null)
    private var commentFont: Typeface? = resolveCommentFont()

    private fun resolveCommentFont(): Typeface? = FontProviders.resolveCommentTypeface(null)

    private fun refreshCandidateFontIfNeeded(): Boolean {
        if (FontProviders.needsRefresh()) {
            candFont = FontProviders.resolveTypeface("cand_font", null)
            commentFont = resolveCommentFont()
            return true
        }
        return false
    }

    init {
        setHasStableIds(true)
    }

    var candidates: Array<CandidateWord> = arrayOf()
        private set

    var total = -1
        private set

    var activeIndex = -1
        private set

    var indexOffset = 0
        private set

    fun updateCandidates(
        data: Array<CandidateWord>,
        total: Int,
        activeIndex: Int = this.activeIndex,
        indexOffset: Int = this.indexOffset,
    ) {
        val fontChanged = refreshCandidateFontIfNeeded()
        val old = candidates
        if (
            !fontChanged &&
            this.total == total &&
            this.indexOffset == indexOffset &&
            old.contentEquals(data)
        ) {
            if (this.activeIndex != activeIndex) {
                updateActiveIndex(activeIndex)
            }
            return
        }
        val oldActive = this.activeIndex
        this.candidates = data
        this.total = total
        this.activeIndex = activeIndex
        this.indexOffset = indexOffset
        // "content is identical; only metadata (total/indexOffset/font) changed"
        // keeps its notifyDataSetChanged: a structural plan would emit no ops at
        // all, and RecyclerView would keep stale ViewHolder styling.
        if (old.contentEquals(data)) {
            trace("notifyDataSetChanged") { notifyDataSetChanged() }
            return
        }
        // Structural diff on the common prefix/suffix: unchanged candidates keep
        // their ViewHolders, only the differing middle range is (re)bound. The
        // highlight rebind positions are resolved by [CandidateUpdatePlan] with
        // the insert/remove shift applied.
        val plan = CandidateUpdatePlan.compute(old, data, oldActive, activeIndex)
        if (plan.changedCount > 0) {
            trace("notifyItemRangeChanged") {
                notifyItemRangeChanged(plan.changedStart, plan.changedCount)
            }
        }
        if (plan.insertCount > 0) {
            notifyItemRangeInserted(plan.insertPosition, plan.insertCount)
        } else if (plan.removeCount > 0) {
            notifyItemRangeRemoved(plan.removePosition, plan.removeCount)
        }
        // Re-bind the highlight outside the structurally changed range. Positions
        // are resolved by [CandidateUpdatePlan] AFTER the insert/remove shift; a
        // naive notifyItemChanged(oldActive) targets the wrong item once items
        // before it were inserted/removed (double-highlight repro, 2026-09-14).
        if (plan.unhighlightPosition != CandidateUpdatePlan.NONE) {
            notifyItemChanged(plan.unhighlightPosition)
        }
        if (plan.highlightPosition != CandidateUpdatePlan.NONE) {
            notifyItemChanged(plan.highlightPosition)
        }
    }


    fun updateActiveIndex(index: Int) {
        if (index == activeIndex) return
        val previous = activeIndex
        activeIndex = index
        if (previous in candidates.indices) {
            notifyItemChanged(previous)
        }
        if (activeIndex in candidates.indices) {
            notifyItemChanged(activeIndex)
        }
    }

    override fun getItemCount() = candidates.size

    override fun getItemId(position: Int) = candidates.getOrNull(position).hashCode().toLong()

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme, candFont, commentFont)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        refreshCandidateFontIfNeeded()
        holder.ui.applyConfiguredTypeface(candFont)
        holder.ui.applyConfiguredCommentTypeface(commentFont)
        holder.ui.setActive(position == activeIndex)
        holder.update(position + indexOffset, candidates[position])
    }

    @CallSuper
    override fun onViewRecycled(holder: CandidateViewHolder) {
        holder.clear()
    }

}
