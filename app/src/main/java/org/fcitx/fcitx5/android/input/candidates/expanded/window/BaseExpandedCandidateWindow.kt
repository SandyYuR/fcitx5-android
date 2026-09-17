/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded.window

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CandidateAction
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesAttached
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesDetached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.CandidateTabActionsAdapter
import org.fcitx.fcitx5.android.input.candidates.expanded.CandidatesPagingSource
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateRefreshRequest
import org.fcitx.fcitx5.android.input.candidates.expanded.PagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.recyclerview.verticalLayoutManager
import kotlin.math.max

abstract class BaseExpandedCandidateWindow<T : BaseExpandedCandidateWindow<T>> :
    InputWindow.SimpleInputWindow<T>(), InputBroadcastReceiver {

    protected val service by manager.inputMethodService()
    protected val theme by manager.theme()
    protected val fcitx by manager.fcitx()
    protected val inputView by manager.inputView()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    protected val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private lateinit var candidateLayout: ExpandedCandidateLayout

    private val iconThemeListener = IconThemeManager.OnIconThemeChangeListener {
        returnKeyDrawable.onIconThemeChanged()
        candidateLayout.embeddedKeyboard.refreshIconTheme()
    }

    protected val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    abstract fun onCreateCandidateLayout(): ExpandedCandidateLayout

    final override fun onCreateView(): View {
        candidateLayout = onCreateCandidateLayout().apply {
            scrollableTabs.apply {
                adapter = tabsAdapter
                layoutManager = verticalLayoutManager()
            }
            pinnedTabs.apply {
                adapter = pinnedTabsAdapter
                layoutManager = verticalLayoutManager()
            }
        }
        return candidateLayout
    }

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            when (it.act) {
                ExpandedCandidateLayout.Keyboard.UpBtnLabel -> prevPage()
                ExpandedCandidateLayout.Keyboard.DownBtnLabel -> nextPage()
            }
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    abstract val adapter: PagingCandidateViewAdapter
    abstract val layoutManager: RecyclerView.LayoutManager

    val tabsAdapter by lazy {
        object : CandidateTabActionsAdapter(theme, false) {
            override fun onTriggerTabAction(id: Int) {
                fcitx.launchOnReady { it.triggerCandidateListTabAction(id) }
            }
        }
    }

    val pinnedTabsAdapter by lazy {
        object : CandidateTabActionsAdapter(theme, true) {
            override fun onTriggerTabAction(id: Int) {
                fcitx.launchOnReady { it.triggerCandidateListTabAction(id) }
            }
        }
    }

    private fun updateTabs(newTabs: Array<CandidateAction>) {
        val tabs = newTabs.takeWhile { !it.isSeparator }
        val pinnedTabs = newTabs.drop(tabs.size + 1).filter { !it.isSeparator }
        if (tabs.isEmpty() && pinnedTabs.isEmpty()) {
            candidateLayout.tabsContainer.visibility = View.GONE
        } else {
            candidateLayout.tabsContainer.visibility = View.VISIBLE
        }
        tabsAdapter.updateTabs(tabs)
        pinnedTabsAdapter.updateTabs(pinnedTabs)
    }

    private var offsetJob: Job? = null

    private val candidatesPager by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 48,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                CandidatesPagingSource(
                    fcitx,
                    total = horizontalCandidate.adapter.total,
                    offset = adapter.offset
                )
            }
        )
    }
    private var candidatesSubmitJob: Job? = null

    /**
     * 待下发的窗口取数请求。候选栏一次布局可能连发多个请求，
     * 这里只保留最新的一个（见 [drainPendingOffsetRefresh]）。
     */
    private var pendingOffsetRequest: ExpandedCandidateRefreshRequest? = null
    private var offsetRefreshScheduled = false

    abstract fun prevPage()

    abstract fun nextPage()

    override fun onAttached() {
        IconThemeManager.addOnChangedListener(iconThemeListener)
        bar.expandButtonStateMachine.push(ExpandedCandidatesAttached)
        candidateLayout.embeddedKeyboard.also {
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onReturnDrawableOverride(returnKeyDrawable.iconThemeDrawable)
            it.keyActionListener = keyActionListener
        }
        updateTabs(fcitx.runImmediately { inputPanelCached.tabs })
        offsetJob = service.lifecycleScope.launch {
            // replay 会补发最近一次请求，所以面板构造时就能拿到当前窗口；
            // 后续每次请求都是“候选生成变了”或“候选栏窗口起点推进了”，
            // 相同的 (offset, generation) 不会重复到达（见 refreshExpanded 的去重）。
            horizontalCandidate.expandedCandidateRefresh.collect { request ->
                if (request.offset <= 0) {
                    windowManager.attachWindow(KeyboardWindow)
                } else {
                    // refresh() 会同步触碰 adapter，不能在布局里调用（d1e5bafc 的约束），
                    // 推到下一帧，且同一批里只保留最新请求。
                    pendingOffsetRequest = request
                    if (!offsetRefreshScheduled) {
                        offsetRefreshScheduled = true
                        candidateLayout.recyclerView.post(::drainPendingOffsetRefresh)
                    }
                }
            }
        }
        candidatesSubmitJob = service.lifecycleScope.launch {
            candidatesPager.flow.collectLatest {
                adapter.submitData(it)
            }
        }
    }

    private fun drainPendingOffsetRefresh() {
        offsetRefreshScheduled = false
        val request = pendingOffsetRequest ?: return
        pendingOffsetRequest = null
        runOffsetRefresh(request)
    }

    /**
     * 按请求重建分页数据。
     *
     * 取数起点沿用请求里的绝对下标（候选栏可见窗口之后的第一批候选），
     * 同时把它记进 adapter：`idx = position + offset` 靠它对齐全局下标，
     * 点击、长按菜单和高亮都依赖这个值。
     *
     * 只有两种情况需要重新取数：起点变了，或候选内容 generation 变了。
     * 两者都没变时什么都不做——这正是“同一 offset 被重复下发”的情形
     * （一次按键的多个布局 pass），旧实现会在这里无条件
     * `resetPosition()` + `refresh()`，让所有可见项反复 rebind。
     */
    private fun runOffsetRefresh(request: ExpandedCandidateRefreshRequest) {
        val offsetChanged = request.offset != adapter.offset
        if (!offsetChanged && request.generation == adapter.generation) {
            return
        }
        adapter.refreshWithOffset(request.offset, request.generation)
        if (!offsetChanged) {
            // 起点没变、只是候选内容换了（同一个 composing 会话内继续输入）：
            // 重新取这一页并保留当前滚动位置，绑定项会因内容变化自然拿到新候选。
            return
        }
        // 取数起点变了：列表回到窗口开头（position 0 就是这批数据的第一个候选），
        // 并立即刷新已绑定项的全局下标。
        candidateLayout.resetPosition()
        refreshBoundCandidateIndexes()
    }

    /**
     * 起点变化后，把已绑定项的全局下标刷成新的 `position + offset`。
     *
     * 不能指望 DiffUtil 的 notify：新老两组候选在 [PagingCandidateDiff] 下判定相同时
     * （重复候选、窗口平移后文本恰好相同）RecyclerView 只挪动 ViewHolder 而不重新绑定，
     * 缓存的 `holder.idx` 会停在旧起点上，点击/长按于是 `select()` 到错误候选
     * （报告 P1-6 第 3 条）。这里只在下标真的失效时刷新，不做全量 rebind。
     *
     * 只改下标、不动候选内容：`resetPosition()` 之后新一页数据是异步取回的，此时
     * `peek(position)` 拿到的还是旧页的候选，写进去会让界面先显示错误内容再跳回去。
     */
    private fun refreshBoundCandidateIndexes() {
        val recyclerView = candidateLayout.recyclerView
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? CandidateViewHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) continue
            holder.updateIndex(position + adapter.offset)
        }
    }

    fun bindCandidateUiViewHolder(holder: CandidateViewHolder) {
        holder.itemView.setOnClickListener {
            // 取用时刻用 RecyclerView 的位置重新算全局下标：只在绑定时缓存
            // holder.idx 的话，DiffUtil 认为“内容没变”而不 rebind 时会停在旧起点上。
            val idx = holder.currentIndex(adapter.offset)
            val total = horizontalCandidate.adapter.total
            if (idx < 0 || (total >= 0 && idx >= total)) {
                return@setOnClickListener
            }
            fcitx.launchOnReady { it.select(idx) }
        }
        holder.itemView.setOnLongClickListener {
            val idx = holder.currentIndex(adapter.offset)
            val total = horizontalCandidate.adapter.total
            if (idx < 0 || (total >= 0 && idx >= total)) {
                return@setOnLongClickListener true
            }
            inputView.showCandidateActionMenu(idx, holder.candidate.text, holder.ui.root)
            true
        }
    }

    fun recycleCandidateViewHolder(holder: CandidateViewHolder) {
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
    }

    override fun onDetached() {
        IconThemeManager.removeOnChangedListener(iconThemeListener)
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesDetached,
            ExpandedCandidatesEmpty to (horizontalCandidate.adapter.total == adapter.offset)
        )
        candidatesSubmitJob?.cancel()
        offsetJob?.cancel()
        // 面板重建时 replay 会补发最新请求，排队中的旧请求（对应旧面板）作废。
        pendingOffsetRequest = null
        candidateLayout.embeddedKeyboard.keyActionListener = null
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        candidateLayout.embeddedKeyboard.onReturnDrawableUpdate(resourceId)
        candidateLayout.embeddedKeyboard.onReturnDrawableOverride(returnKeyDrawable.iconThemeDrawable)
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        updateTabs(data.tabs)
    }

}
