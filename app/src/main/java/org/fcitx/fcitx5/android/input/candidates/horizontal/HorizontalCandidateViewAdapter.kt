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
import org.fcitx.fcitx5.android.input.candidates.CandidateIndexBadgeContent
import org.fcitx.fcitx5.android.input.candidates.CandidateIndexBadgePosition
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

open class HorizontalCandidateViewAdapter(
    val theme: Theme,
    initialHorizontalOverflowEnabled: Boolean = true,
    initialCandidateIndexBadgeEnabled: Boolean = false,
    initialCandidateIndexBadgePosition: CandidateIndexBadgePosition = CandidateIndexBadgePosition.TopLeft,
    initialCandidateIndexBadgeContent: CandidateIndexBadgeContent = CandidateIndexBadgeContent.EngineLabel,
) : RecyclerView.Adapter<CandidateViewHolder>() {

    private var horizontalOverflowEnabled = initialHorizontalOverflowEnabled
    private var candidateIndexBadgeEnabled = initialCandidateIndexBadgeEnabled
    private var candidateIndexBadgePosition = initialCandidateIndexBadgePosition
    private var candidateIndexBadgeContent = initialCandidateIndexBadgeContent

    // Cache candidate/comment fonts and refresh only when font configuration changes.
    private var candFont: Typeface? = FontProviders.resolveTypeface("cand_font", null)
    private var commentFont: Typeface? = resolveCommentFont()

    /**
     * 上面两个字段是在哪个字体数据版本号下解析出来的。
     *
     * 版本号由 [FontProviders.fontGeneration] 提供，**同时覆盖字体与字号**。
     * 旧实现读的是一次性标志 `FontProviders.needsRefresh()`：该标志在
     * `KeyboardWindow.checkAndApplyFontRefresh()` 里先被消费并清零，候选侧永远读到 false，
     * 于是只有被回收重建的那部分 ViewHolder 才换上新字，表现为「换一部分、打几个字就好了」。
     */
    private var appliedFontGeneration = FontProviders.fontGeneration

    private fun resolveCommentFont(): Typeface? = FontProviders.resolveCommentTypeface(null)

    /**
     * 字体数据版本号前进时刷新缓存的字体并返回 true（调用方需要整表重绑）。
     */
    private fun refreshCandidateFontIfNeeded(): Boolean {
        val generation = FontProviders.fontGeneration
        if (generation == appliedFontGeneration) return false
        appliedFontGeneration = generation
        candFont = FontProviders.resolveTypeface("cand_font", null)
        commentFont = resolveCommentFont()
        return true
    }

    init {
        setHasStableIds(true)
    }

    fun setHorizontalOverflowEnabled(enabled: Boolean) {
        if (horizontalOverflowEnabled == enabled) return
        horizontalOverflowEnabled = enabled
        notifyDataSetChanged()
    }

    fun setCandidateIndexBadgeEnabled(enabled: Boolean) {
        if (candidateIndexBadgeEnabled == enabled) return
        candidateIndexBadgeEnabled = enabled
        notifyDataSetChanged()
    }

    fun setCandidateIndexBadgePosition(position: CandidateIndexBadgePosition) {
        if (candidateIndexBadgePosition == position) return
        candidateIndexBadgePosition = position
        notifyDataSetChanged()
    }

    fun setCandidateIndexBadgeContent(content: CandidateIndexBadgeContent) {
        if (candidateIndexBadgeContent == content) return
        candidateIndexBadgeContent = content
        notifyDataSetChanged()
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

    /**
     * 按当前的 [candidateIndexBadgeContent] 算出该位置候选的角标文本（引擎标签为空时
     * 或候选下标越界时返回 null 表示隐藏）。
     *
     * - 引擎标签取 `CandidateWord.label`（适配层已按 `select_labels → select_keys →
     *   (i+1)%10` 算好并带了尾空格，这里只做 trim）。
     * - 栏内序号用的是**栏内位置**（`position`，不是全局 `position + indexOffset`）：
     *   与 `635d3aa2` 引入时一致，角标永远描述当前这一栏里第几个。它不承诺指向
     *   全局候选下标——翻页/窗口平移后角标按新栏重算。
     */
    private fun resolveCandidateIndexBadgeText(position: Int, candidate: CandidateWord): String? {
        if (position !in candidates.indices) return null
        return when (candidateIndexBadgeContent) {
            CandidateIndexBadgeContent.SequenceOneBased -> (position + 1).toString()
            CandidateIndexBadgeContent.SequenceZeroBased -> position.toString()
            CandidateIndexBadgeContent.EngineLabel -> candidate.label.trim().ifEmpty {
                (position + 1).toString()
            }
        }
    }

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme, candFont, commentFont)
        ui.setHorizontalOverflowEnabled(horizontalOverflowEnabled)
        ui.root.apply {
            minimumWidth = dp(HorizontalCandidateComponent.itemMinWidthDp())
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        // 高亮外间距/内间距与 HorizontalCandidateComponent.predictRowOverflow 同口径：
        // 外间距 4dp（格子边缘 ↔ 高亮，上下左右都一样），内间距 = 「候选栏高亮边距」设置项
        // （高亮边框 ↔ 文字，左右）。前者让高亮不再上下贴边，后者保证文字与高亮边框的间距
        // 与候选长短无关。
        ui.configureHighlightSpacing(
            outerPadding = parent.context.dp(HorizontalCandidateComponent.itemHorizontalPaddingDp()),
            highlightPadding = parent.context.dp(
                HorizontalCandidateComponent.candidateHighlightPaddingDp()
            ),
        )
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        refreshCandidateFontIfNeeded()
        holder.ui.setHorizontalOverflowEnabled(horizontalOverflowEnabled)
        holder.ui.applyConfiguredCommentTypeface(commentFont)
        // 字体 + 字号一起重读：字号必须在每次绑定时都对齐版本号，否则被复用的
        // ViewHolder 会一直停在构造时那次求值的旧字号上。
        holder.ui.refreshConfiguredFont(candFont)
        holder.ui.setActive(position == activeIndex)
        val candidate = candidates[position]
        holder.ui.setIndexBadge(
            if (candidateIndexBadgeEnabled) {
                resolveCandidateIndexBadgeText(position, candidate)
            } else {
                null
            },
            candidateIndexBadgePosition,
        )
        holder.update(position + indexOffset, candidate)
    }

    @CallSuper
    override fun onViewRecycled(holder: CandidateViewHolder) {
        holder.clear()
    }

}
