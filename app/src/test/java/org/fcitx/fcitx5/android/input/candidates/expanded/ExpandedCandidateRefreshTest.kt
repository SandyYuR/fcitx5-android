/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖展开候选面板（Paging）的去重与取数判定。
 *
 * 2026-09 的放大链复现：候选栏每完成一次布局都会下发一次展开面板取数请求，
 * 一次按键（双斜杠期间尤其明显）会有多个布局 pass → 相同 offset 反复下发 →
 * 展开面板反复 `resetPosition()` + 重新分页，所有可见项 rebind + 重新布局。
 * 修复后：(offset, generation) 不变就完全不刷新；offset 不变但候选内容变了仍要刷新。
 */
class ExpandedCandidateRefreshTest {

    private fun word(text: String, label: String = "1 ") = CandidateWord(label, text, "", true)

    private fun request(offset: Int, generation: Long) = ExpandedCandidateRefreshRequest(offset, generation)

    // ---- 下发去重 ----

    @Test
    fun firstRequestIsAlwaysEmitted() {
        // 展开面板刚创建（或新会话）：没有“上一次”，replay 必须补上这次。
        assertTrue(ExpandedCandidateRefreshRequest.shouldEmit(null, request(0, 0)))
        assertTrue(ExpandedCandidateRefreshRequest.shouldEmit(null, request(48, 7)))
    }

    @Test
    fun repeatedLayoutPassWithSameOffsetIsSuppressed() {
        // 同一个布局 pass 的重复回调（双斜杠）、以及 offset 与素材都没变的情况。
        val previous = request(48, 5)
        assertFalse(ExpandedCandidateRefreshRequest.shouldEmit(previous, request(48, 5)))
    }

    @Test
    fun sameOffsetWithNewGenerationStillRefreshes() {
        // offset 没动但候选内容换了（继续输入）→ 数据必须重新取。
        val previous = request(48, 5)
        assertTrue(ExpandedCandidateRefreshRequest.shouldEmit(previous, request(48, 6)))
    }

    @Test
    fun advancedOffsetWithSameGenerationStillRefreshes() {
        // 候选栏窗口往前滑动了（ensureActiveCandidateVisible 之后的布局）→ 起点必须推进。
        val previous = request(48, 5)
        assertTrue(ExpandedCandidateRefreshRequest.shouldEmit(previous, request(72, 5)))
    }

    @Test
    fun multipleLayoutPassesInOneKeystrokeEmitOnlyOnce() {
        // 一次按键的多个布局 pass：只有第一个 pass 的请求会被下发。
        var last: ExpandedCandidateRefreshRequest? = null
        var emitted = 0
        val perPassOffset = listOf(48, 48, 48, 48)
        for (offset in perPassOffset) {
            val current = request(offset, 5)
            if (ExpandedCandidateRefreshRequest.shouldEmit(last, current)) {
                last = current
                emitted++
            }
        }
        assertEquals(1, emitted)
    }

    // ---- generation 计算 ----

    @Test
    fun identicalCandidatesDoNotAdvanceGeneration() {
        // 同一个 composing 会话内只移动高亮（候选列表一模一样）：不能触发重新分页。
        val tracker = CandidateGenerationTracker()
        assertTrue(tracker.onCandidates(arrayOf(word("你"), word("泥")), 100))
        val generation = tracker.value
        assertFalse(tracker.onCandidates(arrayOf(word("你"), word("泥")), 100))
        assertEquals(generation, tracker.value)
    }

    @Test
    fun contentChangeAdvancesGeneration() {
        val tracker = CandidateGenerationTracker()
        tracker.onCandidates(arrayOf(word("你"), word("泥")), 100)
        val generation = tracker.value
        assertTrue(tracker.onCandidates(arrayOf(word("你"), word("呢")), 100))
        assertTrue(tracker.value > generation)
    }

    @Test
    fun totalChangeAdvancesGeneration() {
        // Rime 候选总数变化（-1 是 legacy 事件的未知总数）同样意味着内容刷新。
        val tracker = CandidateGenerationTracker()
        tracker.onCandidates(arrayOf(word("你")), -1)
        val generation = tracker.value
        assertTrue(tracker.onCandidates(arrayOf(word("你")), 7))
        assertTrue(tracker.value > generation)
    }

    @Test
    fun contentEqualityIsValueBasedNotIdentityBased() {
        // 两次事件是不同数组实例、相同内容 → 仍然不该前进。
        val tracker = CandidateGenerationTracker()
        tracker.onCandidates(arrayOf(word("你"), word("泥")), 100)
        val generation = tracker.value
        assertFalse(tracker.onCandidates(arrayOf(word("你"), word("泥")), 100))
        assertEquals(generation, tracker.value)
    }

    @Test
    fun sessionStartForcesGenerationForwardEvenWhenCandidatesLookIdentical() {
        // Rime 重启 / 切换输入法后第一批候选可能与上一会话完全相同；
        // 若不强制前进，(offset, generation) 会与上次相同而被去重吞掉，
        // 展开面板就会继续显示旧会话分页到的那一段。
        val tracker = CandidateGenerationTracker()
        tracker.onCandidates(arrayOf(word("你"), word("泥")), 100)
        val lastRequestOfPreviousSession = request(48, tracker.value)
        tracker.onSessionStart()
        tracker.onCandidates(arrayOf(word("你"), word("泥")), 100)
        val firstRequestOfNewSession = request(48, tracker.value)
        assertNotEquals(lastRequestOfPreviousSession, firstRequestOfNewSession)
        assertTrue(
            ExpandedCandidateRefreshRequest.shouldEmit(
                lastRequestOfPreviousSession,
                firstRequestOfNewSession,
            )
        )
    }

    @Test
    fun clearedCandidatesAfterSessionStartReachTheWindow() {
        // 候选被清空（提交/取消输入）→ offset 归 0，但 generation 前进，
        // 面板仍会收到一次请求来收尾（offset<=0 时消费侧回到键盘窗口）。
        val tracker = CandidateGenerationTracker()
        tracker.onCandidates(arrayOf(word("你")), 100)
        val before = tracker.value
        tracker.onSessionStart()
        assertTrue(tracker.onCandidates(emptyArray(), 0))
        assertTrue(tracker.value > before)
    }

    // ---- DiffUtil 判定 ----

    @Test
    fun sameTextDifferentLabelAreDifferentItems() {
        // 标签参与身份：编号不同 → 各占一个 ViewHolder，不会互相顶替。
        val first = CandidateWord("1 ", "你", "", true)
        val second = CandidateWord("2 ", "你", "", true)
        assertFalse(PagingCandidateDiff.areItemsTheSame(first, second))
    }

    @Test
    fun identicalCandidateIsTheSameItemAndSatisfiesContents() {
        val word = word("你", "1 ")
        assertTrue(PagingCandidateDiff.areItemsTheSame(word, word.copy()))
        assertTrue(PagingCandidateDiff.areContentsTheSame(word, word.copy()))
    }

    @Test
    fun commentChangeIsContentChangeNotIdentityChange() {
        // 注释变了但标签+正文+分隔方式相同 → 仍然是同一个候选，只是内容变了。
        // 这与 RecyclerView 的常规行为一致：同一候选的注音更新不应销毁重建 ViewHolder。
        val firstOfPair = CandidateWord("1 ", "你", "ni3", true)
        val secondOfPair = CandidateWord("1 ", "你", "nǐ", true)
        assertTrue(PagingCandidateDiff.areItemsTheSame(firstOfPair, secondOfPair))
        assertFalse(PagingCandidateDiff.areContentsTheSame(firstOfPair, secondOfPair))
    }

    @Test
    fun commentSeparatorChangeCountsAsContentChange() {
        // 分隔方式也会被 CandidateItemUi 渲染出来，必须算内容变化。
        val before = CandidateWord("1 ", "你", "ni3", true)
        val after = CandidateWord("1 ", "你", "ni3", false)
        assertFalse(PagingCandidateDiff.areContentsTheSame(before, after))
        assertFalse(PagingCandidateDiff.areItemsTheSame(before, after))
    }

    @Test
    fun emptyPlaceholderNeverMatchesARealCandidate() {
        assertFalse(PagingCandidateDiff.areItemsTheSame(CandidateWord.Empty, word("你")))
        assertFalse(PagingCandidateDiff.areContentsTheSame(CandidateWord.Empty, word("你")))
    }
}