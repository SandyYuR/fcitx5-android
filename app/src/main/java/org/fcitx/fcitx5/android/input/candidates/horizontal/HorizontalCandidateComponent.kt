/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.style.AbsoluteSizeSpan
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.tracing.trace
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CandidateIndexBadgePosition
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.CustomTypefaceSpan
import org.fcitx.fcitx5.android.input.candidates.expanded.CandidateGenerationTracker
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateRefreshRequest
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val highlightFirstCandidate by AppPrefs.getInstance().keyboard.highlightFirstCandidate
    private val horizontalCandidateOverflowScroll =
        AppPrefs.getInstance().keyboard.horizontalCandidateOverflowScroll
    private val candidateIndexBadgePref = AppPrefs.getInstance().keyboard.showCandidateIndexBadge
    private val candidateIndexBadgePositionPref =
        AppPrefs.getInstance().keyboard.candidateIndexBadgePosition
    private var horizontalOverflowEnabled = horizontalCandidateOverflowScroll.getValue()
    private var candidateIndexBadgeEnabled = candidateIndexBadgePref.getValue()
    private var candidateIndexBadgePosition = candidateIndexBadgePositionPref.getValue()
    private var adapterCreated = false
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    /**
     * Cached [maxSpanCountPref] value.
     *
     * Read on every candidate update before, i.e. potentially several times per keystroke, and
     * ManagedPreference reads go through SharedPreferences (see E5). Invalidated by
     * [invalidateMaxSpanCount], which the view-size change hook calls — the same point at which
     * a span-count change can take visual effect anyway.
     */
    private var cachedMaxSpanCount = 0

    private fun maxSpanCount(): Int {
        if (cachedMaxSpanCount <= 0) {
            cachedMaxSpanCount = maxSpanCountPref.getValue().coerceAtLeast(1)
        }
        return cachedMaxSpanCount
    }

    private fun invalidateMaxSpanCount() {
        cachedMaxSpanCount = 0
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f

    /**
     * 候选项的尺寸常量。
     *
     * 这些数值同时被两处使用：真正的 item 测量（[HorizontalCandidateViewAdapter.onCreateViewHolder]）
     * 与 [predictRowOverflow] 的宽度预测。放在同一处是为了让两边**永远同一个口径**
     * ——一旦分叉，预测就会与真实布局不符，候选行会在两种排布之间抖动。
     *
     * **不跟随「工具栏大小」**：候选格子若跟着工具栏一起长大，可见候选个数会骤减
     * （候选条总宽是屏宽，不随百分比变化），而且与候选字号的观感会脱节。
     * 工具栏百分比只管工具栏自身。
     *
     * 结构上，一个候选项有三层（详见 [CandidateItemUi]）：
     * `root`(格子，四边各留 [ITEM_HORIZONTAL_PADDING_DP] 外间距)
     * → `content`(高亮层，左右再收 [candidateHighlightPaddingDp]) → `text`。
     * 所以格子的实际占用宽 = `文字宽 + 2 * (外间距 + 高亮边距)`。
     */
    private fun candidateItemMinWidth(): Int = context.dp(ITEM_MIN_WIDTH_DP)

    private fun candidateItemHorizontalPadding(): Int = context.dp(ITEM_HORIZONTAL_PADDING_DP)

    companion object {
        private const val ITEM_MIN_WIDTH_DP = 40

        /**
         * 格子边缘 ↔ 高亮之间的外间距（dp），**四边相同**：左右决定格子之间的留白，上下决定
         * 高亮距格子上下的距离（高亮不再上下贴边）。
         *
         * 取 4，与 boomker/fcitx5-android 的 `HORIZONTAL_CANDIDATE_OUTER_PADDING_DP` /
         * `HORIZONTAL_CANDIDATE_VERTICAL_PADDING_DP`（两者同值）一致。历史值是 10：那时高亮直接
         * 铺满整格，格子间距就是这 10dp；现在高亮改画在内层 content 上、格子留白由「外间距 +
         * 高亮边距」共同构成，若外间距仍留 10，则最小宽格子留给文字的可用宽只剩
         * `40 − 2*10 − 2*8 = 4dp`，短候选会被 AutoScaleTextView 压扁。
         */
        private const val ITEM_HORIZONTAL_PADDING_DP = 4

        fun itemMinWidthDp(): Int = ITEM_MIN_WIDTH_DP

        fun itemHorizontalPaddingDp(): Int = ITEM_HORIZONTAL_PADDING_DP

        /**
         * 高亮边框 ↔ 文字之间的水平间距（dp），由主题配置项「候选栏高亮边距」控制。
         *
         * 适配器（真实测量）与 [predictRowOverflow]（宽度预测）都必须走这里取值，保证同口径。
         * 这里**现读**偏好而不是缓存成常量：候选项每次编码更新都会重建，改设置后下一次输入即
         * 生效，符合 `CandidateItemUi` 不注册偏好监听的设计（见 AGENTS.md 相关条目）。
         */
        fun candidateHighlightPaddingDp(): Int =
            ThemeManager.prefs.candidateBarHighlightInset.getValue()
    }

    /**
     * (for [HorizontalCandidateMode.AutoFillWidth] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false
    private var highlightMovedInCurrentComposition = false
    private var lastPagedCandidatesSnapshot: Array<CandidateWord> = emptyArray()
    private var lastPagedCursor = -1
    private var lastPagedHasPrev = false
    private var lastPagedData: PagedCandidateEvent.Data? = null
    private var pagedCandidateFlowActive = false
    private var lastPagedEventUptimeMs = 0L
    private var lastRenderedCandidatesSnapshot: Array<CandidateWord> = emptyArray()
    private var lastRenderedActiveIndex = Int.MIN_VALUE

    /**
     * 最近一次真正渲染候选时所用的字体数据版本号（见 [FontProviders.fontGeneration]）。
     *
     * 本文件里的两道去重早退只看「候选内容 + 高亮」，在用户只改了「字体设定」里的
     * 候选字体/字号、而候选内容一个字都没变的场景下会把刷新整个吃掉 —— 候选栏就会
     * 一直停在旧字号上，直到用户打出下一个编码（即「换一部分、打几个字才好」的那一半）。
     * 所以去重必须把版本号一并纳入比较。
     */
    private var lastRenderedFontGeneration = FontProviders.fontGeneration

    private fun fontDataChanged(): Boolean =
        FontProviders.fontGeneration != lastRenderedFontGeneration

    private var pendingLegacyCandidateUpdate: Runnable? = null

    /**
     * 候选数据版本号，只在候选内容（或总数）真的变化时前进；
     * 展开面板的取数请求同时带上 offset 和它，见 [ExpandedCandidateRefreshRequest]。
     */
    private val candidateGeneration = CandidateGenerationTracker()

    /**
     * 最近一次真正下发过的展开面板取数请求。
     *
     * 布局每完成一次都会请求一次刷新，同一个 (offset, generation) 重复下发会让展开面板
     * 反复 `resetPosition()` + 重新分页（全部可见项 rebind）。
     * 新会话（[onStartInput]）会清空它，保证 Rime 重启 / 输入法切换后第一次请求一定通过。
     */
    private var lastExpandedRefreshRequest: ExpandedCandidateRefreshRequest? = null

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean) {
        // New input session should not inherit paged-candidate flow state from previous one.
        pagedCandidateFlowActive = false
        lastPagedEventUptimeMs = 0L
        lastPagedData = null
        // 新会话（含 Rime 重启、切换输入法）的 generation 必须前进：否则即使新会话的候选
        // 内容与旧会话完全一样，(offset, generation) 也会与上次相同而被去重吞掉，展开面板
        // 会继续显示旧会话分页到的那一段候选。
        candidateGeneration.onSessionStart()
        lastExpandedRefreshRequest = null
        // 候选侧没有「字体设定已保存」的事件入口：KeyboardWindow.checkAndApplyFontRefresh()
        // 只重建按键行，而候选内容没变时上面的去重会把候选栏继续钉在旧字号上，只有下一个
        // 编码（新候选事件）才会换 —— 用户看到的正是「换一部分、打几个字才好」。
        // 这里搭上键盘侧同一趟 preload 的回调：新的字体/字号真正发布（版本号前进）后再重下发
        // 一次当前候选。版本号没动时 adapter.updateCandidates 会立刻早退，无额外开销。
        FontProviders.preloadFontsAsync {
            ContextCompat.getMainExecutor(context).execute {
                val current = adapter
                current.updateCandidates(
                    current.candidates, current.total,
                    current.activeIndex, current.indexOffset
                )
            }
        }
    }

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last refresh request
    private val _expandedCandidateRefresh = MutableSharedFlow<ExpandedCandidateRefreshRequest>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateRefresh = _expandedCandidateRefresh.asSharedFlow()

    private fun refreshExpanded(childCount: Int) {
        val expandedOffset = (adapter.indexOffset + childCount).coerceAtLeast(0)
        val request = ExpandedCandidateRefreshRequest(expandedOffset, candidateGeneration.value)
        // 一次按键可以触发多个布局 pass（双斜杠期间每个 pass 都会走到这里），
        // 只有 (offset, generation) 真的变了才下发。
        // 另外注意：下发失败时不能记账，否则一次真正需要的刷新会被吞掉。
        if (
            ExpandedCandidateRefreshRequest.shouldEmit(lastExpandedRefreshRequest, request) &&
            _expandedCandidateRefresh.tryEmit(request)
        ) {
            lastExpandedRefreshRequest = request
        }
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (adapter.total >= 0 && adapter.total <= expandedOffset)
        )
    }

    private fun firstRowVisibleSlotCount(totalCandidates: Int): Int {
        val rv = view
        val lm = layoutManager
        val childCnt = lm.childCount
        if (childCnt <= 0) return 0
        val rightBound = rv.width - rv.paddingRight
        var firstRowTop = Int.MIN_VALUE
        var visible = 0
        for (i in 0 until childCnt) {
            val child = lm.getChildAt(i) ?: continue
            if (firstRowTop == Int.MIN_VALUE) {
                firstRowTop = child.top
            }
            if (child.top != firstRowTop) break
            if (child.right <= rightBound) {
                visible++
            } else {
                break
            }
        }
        return visible.coerceIn(0, totalCandidates)
    }

    /**
     * Scroll the candidate window so the highlighted candidate sits in the first row.
     *
     * A pass needs a completed layout to know how many slots the first row holds, so one
     * follow-up may be required; follow-ups are triggered by [pendingEnsureVisible] after the
     * next layout pass instead of unconditional view.post recursion (see P1b).
     */
    private fun ensureActiveCandidateVisible(
        originalCandidates: Array<CandidateWord>,
        total: Int,
        activeIndex: Int,
    ) {
        if (view.isComputingLayout) {
            // The posted drain can still lose a race with a newer layout pass;
            // never notify from inside layout — re-queue and return.
            pendingEnsureVisible = Triple(originalCandidates, total, activeIndex)
            scheduleEnsureVisibleDrain()
            return
        }
        if (activeIndex !in originalCandidates.indices) {
            return
        }
        val visibleCount = firstRowVisibleSlotCount(adapter.candidates.size)
        if (visibleCount <= 0 || visibleCount >= adapter.candidates.size) {
            return
        }
        val relativeActiveIndex = activeIndex - adapter.indexOffset
        if (relativeActiveIndex in 0 until visibleCount) {
            return
        }
        val shift = relativeActiveIndex - visibleCount + 1
        if (shift <= 0) {
            return
        }
        val oldOffset = adapter.indexOffset
        val newOffset = (oldOffset + shift).coerceAtMost(originalCandidates.lastIndex)
        if (newOffset == oldOffset) {
            return
        }
        val windowedCandidates = originalCandidates.copyOfRange(newOffset, originalCandidates.size)
        val windowedActiveIndex = activeIndex - newOffset
        adapter.updateCandidates(windowedCandidates, total, windowedActiveIndex, newOffset)
        // Re-check visibility after the next layout pass (consumed in onLayoutCompleted)
        pendingEnsureVisible = Triple(originalCandidates, total, activeIndex)
    }

    fun setHorizontalOverflowEnabled(enabled: Boolean) {
        if (horizontalOverflowEnabled == enabled) return
        horizontalOverflowEnabled = enabled
        if (adapterCreated) {
            adapter.setHorizontalOverflowEnabled(enabled)
        }
    }

    fun setCandidateIndexBadgeEnabled(enabled: Boolean) {
        if (candidateIndexBadgeEnabled == enabled) return
        candidateIndexBadgeEnabled = enabled
        if (adapterCreated) {
            adapter.setCandidateIndexBadgeEnabled(enabled)
            updateCandidates(adapter.candidates, adapter.total, adapter.activeIndex)
        }
    }

    fun setCandidateIndexBadgePosition(position: CandidateIndexBadgePosition) {
        if (candidateIndexBadgePosition == position) return
        candidateIndexBadgePosition = position
        if (adapterCreated) {
            adapter.setCandidateIndexBadgePosition(position)
        }
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        adapterCreated = true
        object : HorizontalCandidateViewAdapter(
            theme,
            horizontalOverflowEnabled,
            candidateIndexBadgeEnabled,
            candidateIndexBadgePosition,
        ) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
                    flexGrow = layoutFlexGrow
                }
                holder.itemView.setOnClickListener {
                    val idx = holder.currentIndex(adapter.indexOffset)
                    val total = adapter.total
                    if (idx < 0 || (total >= 0 && idx >= total)) {
                        return@setOnClickListener
                    }
                    fcitx.launchOnReady { it.select(idx) }
                }
                holder.itemView.setOnLongClickListener {
                    val idx = holder.currentIndex(adapter.indexOffset)
                    inputView.showCandidateActionMenu(idx, holder.candidate.text, holder.ui.root)
                    true
                }
                inputView.bindCandidateGesture(
                    holder.ui.root,
                    holder.candidate.text
                ) { holder.currentIndex(adapter.indexOffset) }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                inputView.unbindCandidateGesture(holder.ui.root)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = false
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.candidates.size) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (!secondLayoutPassDone) {
                            secondLayoutPassDone = true
                            for (i in 0 until cnt) {
                                getChildAt(i)?.updateLayoutParams<LayoutParams> {
                                    flexGrow = 1f
                                }
                            }
                        }
                        // Fall through to refreshExpanded + drain scheduling below:
                        // the early `return` used to strand a pendingEnsureVisible
                        // set by an interleaved drain when the second pass runs.
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshExpanded(cnt)
                // Must not touch the adapter synchronously here: we are inside
                // dispatchLayout, and any notify* crashes with "Cannot call this
                // method while RecyclerView is computing a layout or scrolling"
                // (double-slash repro). Coalesce into one posted drain so a stale
                // first-slash follow-up can never clobber a newer second-slash list.
                if (pendingEnsureVisible != null) {
                    scheduleEnsureVisibleDrain()
                }
            }
            // no need to override `generate{,Default}LayoutParams`, because HorizontalCandidateViewAdapter
            // guarantees ViewHolder's layoutParams to be `FlexboxLayoutManager.LayoutParams`
        }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    // Mirrors CandidateItemUi's span-aware text measurement so the overflow prediction uses
    // the same width as the item that will actually be laid out.
    private data class CandidateTextEpoch(
        val candidateFont: Typeface?,
        val candidateSizePx: Float,
        val commentFont: Typeface,
        val commentSizePx: Int,
    )

    private val candidateTextPaint = TextPaint()
    private var candidateTextEpoch: CandidateTextEpoch? = null
    private val candidateTextWidthCache = HashMap<CandidateWord, Int>()

    private fun candidateTextWidth(candidate: CandidateWord): Int {
        val candidateFont = FontProviders.resolveTypeface("cand_font", null)
        // CandidateItemUi resolves the same fallback chain when it creates the comment span.
        val commentFont = FontProviders.resolveCommentTypeface(null)
        val candidateSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            FontProviders.getFontSize("cand_font", FontProviders.DEFAULT_CANDIDATE_FONT_SIZE),
            context.resources.displayMetrics
        )
        val commentSizePx = FontProviders.commentFontSizePx(context)
        val epoch = CandidateTextEpoch(candidateFont, candidateSizePx, commentFont, commentSizePx)
        if (epoch != candidateTextEpoch) {
            candidateTextEpoch = epoch
            candidateTextWidthCache.clear()
            candidateTextPaint.typeface = candidateFont
            candidateTextPaint.textSize = candidateSizePx
        }
        if (candidateTextWidthCache.size > 512) {
            candidateTextWidthCache.clear()
        }
        return candidateTextWidthCache.getOrPut(candidate) {
            val text = buildSpannedString {
                append(candidate.text)
                if (candidate.comment.isNotBlank()) {
                    if (candidate.spaceBetweenComment) append(' ')
                    inSpans(
                        CustomTypefaceSpan(commentFont),
                        AbsoluteSizeSpan(commentSizePx, false),
                    ) {
                        append(candidate.comment)
                    }
                }
            }
            AutoScaleTextView.measureTextWidth(text, candidateTextPaint)
        }
    }

    /**
     * Predict whether [candidates] cannot all be displayed in one row — exactly the
     * condition (childCount < candidates.size) that the second layout pass in
     * [layoutManager]'s onLayoutCompleted discovers. Width math mirrors
     * [HorizontalCandidateViewAdapter.onCreateViewHolder] + [CandidateItemUi]:
     * item = max(textWidth + 2 * highlightPadding, 40dp) + 2 * 4dp outer padding,
     * coerced to layoutMinWidth, plus one divider inset per item.
     */
    private fun predictRowOverflow(candidates: Array<CandidateWord>): Boolean {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0 || candidates.isEmpty()) {
            // Not laid out yet or nothing to display; fall back to discovery.
            return false
        }
        val rootMinWidth = candidateItemMinWidth()
        val rootPadding = candidateItemHorizontalPadding() * 2
        // 高亮层的内边距把文字再往里收，文字所在的 content 宽度 = 文字宽 + 2 * 高亮边距。
        val highlightPadding = context.dp(candidateHighlightPaddingDp()) * 2
        val divider = dividerDrawable.intrinsicWidth
        var total = 0
        for (candidate in candidates) {
            val textWidth = candidateTextWidth(candidate)
            val itemWidth =
                max(max(textWidth + highlightPadding, rootMinWidth) + rootPadding, layoutMinWidth)
            total += itemWidth + divider
            if (total > available) {
                return true
            }
        }
        return false
    }

    private var pendingEnsureVisible: Triple<Array<CandidateWord>, Int, Int>? = null
    private var ensureVisibleDrainScheduled = false

    /**
     * Drain [pendingEnsureVisible] outside the layout pass.
     *
     * Called only from a posted runnable, never synchronously from
     * `onLayoutCompleted`: adapter notify* calls (notably
     * notifyItemRangeRemoved) throw IllegalStateException while RecyclerView is
     * computing a layout or scrolling, which is exactly the context of
     * onLayoutCompleted (double-slash repro). Pending requests are coalesced:
     * only the newest candidate list is drained, and a newer [updateCandidates]
     * list that arrives while a drain is queued replaces the stale one.
     */
    private fun scheduleEnsureVisibleDrain() {
        if (ensureVisibleDrainScheduled) return
        ensureVisibleDrainScheduled = true
        view.post {
            ensureVisibleDrainScheduled = false
            val pending = pendingEnsureVisible ?: return@post
            pendingEnsureVisible = null
            ensureActiveCandidateVisible(pending.first, pending.second, pending.third)
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                // A size change is also when a span-count preference change becomes visible, so
                // re-read it here instead of on every candidate update (see E5).
                invalidateMaxSpanCount()
                if (fillStyle == AutoFillWidth) {
                    layoutMinWidth = w / maxSpanCount() - dividerDrawable.intrinsicWidth
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(dividerDrawable))
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (pagedCandidateFlowActive && data.total == -1) {
            val now = SystemClock.uptimeMillis()
            // Keep preferring paged events only when they are still arriving.
            // If paged stream is stale (e.g. engine/plugin restarted), fallback to legacy list updates.
            if (now - lastPagedEventUptimeMs <= 500L) {
                pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
                pendingLegacyCandidateUpdate = null
                return
            }
            pagedCandidateFlowActive = false
            lastPagedData = null
            pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        }
        lastPagedData = null
        lastRenderedCandidatesSnapshot = emptyArray()
        lastRenderedActiveIndex = Int.MIN_VALUE
        val candidates = data.candidates
        val total = data.total
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        val update = Runnable {
            pendingLegacyCandidateUpdate = null
            // CandidateListEvent doesn't provide cursor info; the cursor is at 0 by default.
            updateCandidates(candidates, total, if (highlightFirstCandidate) 0 else -1)
        }
        pendingLegacyCandidateUpdate = update
        // Run synchronously when already on the main thread to avoid an extra frame delay;
        // fall back to posting for off-main-thread delivery.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run()
        } else {
            view.post(update)
        }
    }

    override fun onPagedCandidateUpdate(data: PagedCandidateEvent.Data) {
        pagedCandidateFlowActive = true
        lastPagedEventUptimeMs = SystemClock.uptimeMillis()
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = null
        if (data == lastPagedData) {
            return
        }
        lastPagedData = data
        val candidates = data.candidates
        val cursorIndex = data.cursorIndex
        val normalizedCursor = when {
            cursorIndex in candidates.indices -> cursorIndex
            // Some engines may expose a global cursor index in paged mode.
            cursorIndex >= 0 && candidates.isNotEmpty() -> cursorIndex % candidates.size
            else -> -1
        }

        val isNewFirstPageSnapshot =
            !data.hasPrev && !candidates.contentEquals(lastPagedCandidatesSnapshot)
        if (isNewFirstPageSnapshot) {
            // New composing snapshot on the first page; keep it unhighlighted until moved
            // (unless the "highlight first candidate" preference is enabled).
            highlightMovedInCurrentComposition = false
            // Reset movement baseline for the new snapshot to avoid false positive move detection.
            lastPagedCursor = normalizedCursor
            lastPagedHasPrev = data.hasPrev
        }

        if (!highlightMovedInCurrentComposition && !isNewFirstPageSnapshot) {
            val cursorChanged = lastPagedCursor >= 0 && normalizedCursor >= 0 && normalizedCursor != lastPagedCursor
            val pageChanged = data.hasPrev != lastPagedHasPrev
            if (cursorChanged || pageChanged) {
                highlightMovedInCurrentComposition = true
            }
        }

        val effectiveActiveIndex = if (
            !highlightFirstCandidate &&
            !highlightMovedInCurrentComposition &&
            normalizedCursor == 0
        ) {
            -1
        } else {
            normalizedCursor
        }

        if (
            candidates.contentEquals(lastRenderedCandidatesSnapshot) &&
            effectiveActiveIndex == lastRenderedActiveIndex &&
            !fontDataChanged()
        ) {
            return
        }

        // Keep direct array references as snapshots; contentEquals avoids the
        // per-keystroke asList() wrapper allocations.
        lastPagedCandidatesSnapshot = candidates
        lastPagedCursor = normalizedCursor
        lastPagedHasPrev = data.hasPrev
        lastRenderedCandidatesSnapshot = candidates
        lastRenderedActiveIndex = effectiveActiveIndex

        updateCandidates(candidates, -1, effectiveActiveIndex)
    }

    private fun updateCandidates(
        candidates: Array<CandidateWord>,
        total: Int,
        activeIndex: Int,
    ) {
        lastRenderedFontGeneration = FontProviders.fontGeneration
        // 先推进 generation 再更新 adapter：adapter 的 notify 会触发一次布局，
        // 那次布局的 onLayoutCompleted 正是读取 generation 的地方。
        // 候选内容没变（例如只移动了高亮）时 generation 不动，展开面板就不会重新分页。
        candidateGeneration.onCandidates(candidates, total)
        val maxSpanCount = maxSpanCount()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
                val fewCandidates = candidates.size < maxSpanCount
                // P1a: predict the overflow ([^2]) that the second layout pass would
                // discover; when predicted, apply its outcome (stretch evenly via
                // flexGrow=1) up front so the extra measure/layout pass is skipped.
                val predictedOverflow = fewCandidates && predictRowOverflow(candidates)
                layoutFlexGrow = if (!fewCandidates || predictedOverflow) 1f else 0f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = fewCandidates && !predictedOverflow
                secondLayoutPassDone = false
            }
            AlwaysFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }
        // Phase 0 perf tracing: candidate binding/notify on the UI thread.
        trace("updateCandidates") {
            adapter.updateCandidates(candidates, total, activeIndex, 0)
        }
        pendingEnsureVisible = Triple(candidates, total, activeIndex)
        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }
}
