/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the RecyclerView notify planning for the horizontal candidate bar.
 *
 * The 2026-09-14 double-highlight repro: holding a key walks the cursor through
 * the punctuation page (、､/／÷) while [HorizontalCandidateComponent]'s
 * ensure-visible windowing slides the adapter list (front remove, then front
 * insert). The old code re-bound the highlight at the pre-shift position, so
 * the ViewHolder that moved kept its active background next to the newly
 * highlighted item.
 */
class CandidateUpdatePlanTest {

    private fun word(text: String) = CandidateWord("1 ", text, "", true)

    private fun compute(
        old: List<String>,
        new: List<String>,
        oldActive: Int = CandidateUpdatePlan.NONE,
        newActive: Int = CandidateUpdatePlan.NONE,
    ) = CandidateUpdatePlan.compute(
        old.map(::word).toTypedArray(),
        new.map(::word).toTypedArray(),
        oldActive,
        newActive,
    )

    @Test
    fun identicalContentEmitsNoOps() {
        val plan = compute(listOf("a", "b"), listOf("a", "b"), oldActive = 1, newActive = 1)
        assertEquals(0, plan.changedCount)
        assertEquals(CandidateUpdatePlan.NONE, plan.insertPosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.removePosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.unhighlightPosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.highlightPosition)
    }

    @Test
    fun sameContentActiveMoveRebindsBothPositions() {
        val plan = compute(listOf("a", "b", "c"), listOf("a", "b", "c"), oldActive = 1, newActive = 2)
        assertEquals(0, plan.changedCount)
        assertEquals(1, plan.unhighlightPosition)
        assertEquals(2, plan.highlightPosition)
    }

    @Test
    fun headInsertShiftsPreviousActiveRebindPosition() {
        // Full list -> windowed by ensureActiveCandidateVisible from the front:
        // [a,b,c,d,e] -> [b,c,d,e]. The previously active ViewHolder (old index
        // 3) moves to new position 2; the old code notified position 3, hitting
        // the wrong holder.
        val plan = compute(listOf("a", "b", "c", "d", "e"), listOf("b", "c", "d", "e"), oldActive = 3, newActive = 2)
        assertEquals(CandidateUpdatePlan.NONE, plan.changedStart)
        assertEquals(1, plan.removeCount)
        assertEquals(0, plan.removePosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.unhighlightPosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.highlightPosition)
    }

    @Test
    fun headInsertOfNewItemShiftsPreviousActiveRebindPosition() {
        // Exact 2026-09-14 repro: windowed [b,c,d,e] slides back to the full
        // [a,b,c,d,e]. The stale-active ViewHolder (old index 2 = d) moves to
        // new position 3 and must be re-bound inactive there; the old code
        // notified position 2 (c, already inactive) and left d highlighted
        // next to the newly active e.
        val plan = compute(listOf("b", "c", "d", "e"), listOf("a", "b", "c", "d", "e"), oldActive = 2, newActive = 4)
        assertEquals(1, plan.insertCount)
        assertEquals(0, plan.insertPosition)
        assertEquals(3, plan.unhighlightPosition)
        assertEquals(4, plan.highlightPosition)
    }

    @Test
    fun tailGrowthKeepsActivePositions() {
        // Typing appends candidates: [a,b] -> [a,b,c], highlight moves 0 -> 1.
        val plan = compute(listOf("a", "b"), listOf("a", "b", "c"), oldActive = 0, newActive = 1)
        assertEquals(1, plan.insertCount)
        assertEquals(2, plan.insertPosition)
        assertEquals(0, plan.unhighlightPosition)
        assertEquals(1, plan.highlightPosition)
    }

    @Test
    fun shrinkByTailRemovalKeepsActivePositions() {
        // Backspace drops the tail: [a,b,c] -> [a,b], highlight 2 -> 1.
        val plan = compute(listOf("a", "b", "c"), listOf("a", "b"), oldActive = 2, newActive = 1)
        assertEquals(1, plan.removeCount)
        assertEquals(2, plan.removePosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.unhighlightPosition)
        assertEquals(1, plan.highlightPosition)
    }

    @Test
    fun removalOfActiveHolderNeedsNoUnhighlight() {
        // [a,b,c] -> [b,c]: the removed holder was the active one.
        val plan = compute(listOf("a", "b", "c"), listOf("b", "c"), oldActive = 0, newActive = 0)
        assertEquals(1, plan.removeCount)
        assertEquals(CandidateUpdatePlan.NONE, plan.unhighlightPosition)
        assertEquals(0, plan.highlightPosition)
    }

    @Test
    fun middleChangeRebindsRangeAndKeepsHighlightPositionsStable() {
        // [a,b,c,d,e] -> [a,x,y,e]: minimal ops are changed(1,2) + removed(3,1)
        // (old d is dropped, suffix e is kept). Old active b's holder is
        // re-bound by the changed range as inactive, and the new active y is
        // re-bound by the same range as active — no dedicated rebind needed.
        val plan = compute(listOf("a", "b", "c", "d", "e"), listOf("a", "x", "y", "e"), oldActive = 1, newActive = 2)
        assertEquals(1, plan.changedStart)
        assertEquals(2, plan.changedCount)
        assertEquals(CandidateUpdatePlan.NONE, plan.insertPosition)
        assertEquals(3, plan.removePosition)
        assertEquals(1, plan.removeCount)
        assertEquals(CandidateUpdatePlan.NONE, plan.unhighlightPosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.highlightPosition)
    }

    @Test
    fun middleChangeOutsideHighlightRangeEmitsDedicatedRebinds() {
        // Same lists, highlight a(0) -> e(3). Position 0 is in the preserved
        // prefix and position 3 is the shifted suffix holder — neither is
        // re-bound by the structural ops, so dedicated rebinds are required.
        val plan = compute(listOf("a", "b", "c", "d", "e"), listOf("a", "x", "y", "e"), oldActive = 0, newActive = 3)
        assertEquals(1, plan.changedStart)
        assertEquals(2, plan.changedCount)
        assertEquals(0, plan.unhighlightPosition)
        assertEquals(3, plan.highlightPosition)
    }

    @Test
    fun emptyToContentHighlightsFirst() {
        val plan = compute(emptyList(), listOf("a", "b"), oldActive = CandidateUpdatePlan.NONE, newActive = 0)
        assertEquals(2, plan.insertCount)
        assertEquals(0, plan.insertPosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.unhighlightPosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.highlightPosition)
    }

    @Test
    fun contentToEmptyNeedsNoRebind() {
        val plan = compute(listOf("a", "b"), emptyList(), oldActive = 1, newActive = CandidateUpdatePlan.NONE)
        assertEquals(2, plan.removeCount)
        assertEquals(CandidateUpdatePlan.NONE, plan.unhighlightPosition)
        assertEquals(CandidateUpdatePlan.NONE, plan.highlightPosition)
    }

    @Test
    fun identicalContentWithMetadataOnlyChangeStillRequestsFullRebind() {
        // Same content, different active index: the caller relies on a full
        // rebind (metadata-only update), so no structural ops but dedicated
        // highlight rebinds are still emitted.
        val plan = compute(listOf("a", "b"), listOf("a", "b"), oldActive = 0, newActive = 1)
        assertEquals(0, plan.changedCount)
        assertEquals(0, plan.unhighlightPosition)
        assertEquals(1, plan.highlightPosition)
    }
}
