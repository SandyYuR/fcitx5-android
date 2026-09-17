/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import org.fcitx.fcitx5.android.core.CandidateWord

/**
 * 展开候选面板的一次取数请求：从 [offset] 开始分页。
 *
 * 纯数据、不含 Android 类型，便于单元测试；实际的下发与消费见
 * [org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent]
 * 的 `refreshExpanded` 与 [org.fcitx.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow]。
 *
 * [generation] 见 [CandidateGenerationTracker]：offset 相同但候选内容变了时也必须重新取数，
 * 否则展开面板会一直显示上一次分页到的那一段候选。
 */
data class ExpandedCandidateRefreshRequest(
    val offset: Int,
    val generation: Long,
) {
    companion object {
        /**
         * 是否需要把 [current] 下发给展开面板。
         *
         * 同一个 (offset, generation) 重复下发毫无意义：消费侧会立刻
         * `resetPosition()` + 重新分页，让所有可见项 rebind + 重新布局。
         * 候选栏每完成一次布局都会走到下发点（一次按键可能有多个布局 pass，
         * 双斜杠期间尤其明显），所以这里的去重直接决定展开面板是否会反复刷新。
         *
         * [previous] 为 null（新会话、面板刚创建）时必须下发，不能把首次请求吞掉。
         */
        fun shouldEmit(
            previous: ExpandedCandidateRefreshRequest?,
            current: ExpandedCandidateRefreshRequest,
        ): Boolean = previous == null || previous != current
    }
}

/**
 * 候选数据的 generation（版本号）。
 *
 * 只在真正的候选内容变化以及新输入会话时前进，供 [ExpandedCandidateRefreshRequest] 使用：
 * 相同 offset 且 generation 相同 → 完全跳过；generation 变了 → 即使 offset 不变也要刷新。
 *
 * 比较基线是**完整候选列表 + total**。跟随窗口滑动的 `ensureActiveCandidateVisible`
 * 只调用 adapter 的 `updateCandidates`，不经过这里，所以滑窗不会改变 generation。
 */
class CandidateGenerationTracker {

    /** 当前版本号，单调不减，仅由 [onCandidates] / [onSessionStart] 改变。 */
    var value: Long = 0L
        private set

    private var candidateSnapshot: Array<CandidateWord> = emptyArray()
    private var total: Int = NO_TOTAL
    private var snapshotValid = false

    /**
     * 记录一次候选更新。
     * @return 内容（或总数）真的变了、generation 已前进时为 true。
     */
    fun onCandidates(candidates: Array<CandidateWord>, total: Int): Boolean {
        val changed = !snapshotValid ||
            this.total != total ||
            !candidateSnapshot.contentEquals(candidates)
        if (!changed) return false
        candidateSnapshot = candidates
        this.total = total
        snapshotValid = true
        value++
        return true
    }

    /**
     * 新输入会话：丢弃与上一会话的比较基线并强制前进。
     *
     * 新会话（Rime 重启、切换应用/输入法）的第一批候选可能与旧会话完全相同，
     * 若不强制前进，(offset, generation) 会与上次相同而被去重吞掉，
     * 展开面板就会继续显示旧会话分页到的那一段候选。
     */
    fun onSessionStart() {
        candidateSnapshot = emptyArray()
        total = NO_TOTAL
        snapshotValid = false
        value++
    }

    private companion object {
        /** “未知总数”。legacy 候选事件与新会话基线会用 -1 之外的哨兵值区分。 */
        const val NO_TOTAL = Int.MIN_VALUE
    }
}