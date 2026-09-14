/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord

/**
 * The minimal set of [androidx.recyclerview.widget.RecyclerView.Adapter] notify
 * operations that turns one candidate list into another, with highlight rebind
 * positions already resolved in the NEW list's coordinate system.
 *
 * Pure computation without Android classes so the diff can be unit tested.
 *
 * Highlight positions must account for the structural shift: when items are
 * inserted/removed before the previously active item, its ViewHolder moves, and
 * a rebind issued at the OLD position hits the wrong item — the previous
 * highlight then stays painted next to the new one (double highlight, seen on
 * 2026-09-14: a key held to walk the cursor through 、､/／÷ while the visible
 * window slides).
 */
data class CandidateUpdatePlan(
    /** Position of the count-neutral changed range, [NONE] when empty. */
    val changedStart: Int,
    /** Length of the count-neutral changed range, 0 when empty. */
    val changedCount: Int,
    /** Insert position, [NONE] when nothing is inserted. */
    val insertPosition: Int,
    val insertCount: Int,
    /** Remove position, [NONE] when nothing is removed. */
    val removePosition: Int,
    val removeCount: Int,
    /**
     * Post-shift position of the ViewHolder that displayed the previous active
     * candidate and must be re-bound as inactive; [NONE] when the holder is
     * gone, covered by the changed range, or there was no previous highlight.
     */
    val unhighlightPosition: Int,
    /** Post-shift position of the newly active candidate; [NONE] when none. */
    val highlightPosition: Int,
) {
    companion object {
        const val NONE = -1

        /**
         * [oldActive]/[newActive] are indices into [old]/[new]; either may be
         * [NONE] (no highlight).
         */
        fun compute(
            old: Array<CandidateWord>,
            new: Array<CandidateWord>,
            oldActive: Int,
            newActive: Int,
        ): CandidateUpdatePlan {
            val minLen = minOf(old.size, new.size)
            var prefix = 0
            while (prefix < minLen && old[prefix] == new[prefix]) prefix++
            var oldEnd = old.size
            var newEnd = new.size
            while (oldEnd > prefix && newEnd > prefix && old[oldEnd - 1] == new[newEnd - 1]) {
                oldEnd--
                newEnd--
            }
            val commonLen = minOf(oldEnd, newEnd) - prefix
            val commonEnd = prefix + commonLen
            // Exactly one of the two is positive.
            val removedCount = maxOf(0, oldEnd - newEnd)
            val insertedCount = maxOf(0, newEnd - oldEnd)

            // Where does the ViewHolder that displayed old[index] end up?
            //  - inside the preserved prefix [0, prefix) and the changed range
            //    [prefix, commonEnd): position unchanged;
            //  - inside the replaced range [commonEnd, oldEnd): holder is gone;
            //  - in the matched suffix [oldEnd, old.size): keeps its holder,
            //    shifted by the net count change.
            fun positionAfterUpdate(index: Int): Int = when {
                index !in old.indices -> NONE
                index < commonEnd -> index
                index < oldEnd -> NONE
                else -> index + insertedCount - removedCount
            }

            val oldPos = positionAfterUpdate(oldActive)
            val newPos = if (newActive in new.indices) newActive else NONE
            // Positions re-bound by the structural notifies anyway need no
            // dedicated highlight rebind.
            val oldCovered = oldPos != NONE && oldPos >= prefix && oldPos < commonEnd
            val newCovered = newPos != NONE && (
                (newPos >= prefix && newPos < commonEnd) ||
                    (insertedCount > 0 && newPos >= commonEnd && newPos < commonEnd + insertedCount)
                )
            var unhighlight = NONE
            var highlight = NONE
            if (oldPos != newPos) {
                if (!oldCovered) {
                    unhighlight = oldPos
                }
                if (!newCovered) {
                    highlight = newPos
                }
            }
            return CandidateUpdatePlan(
                changedStart = if (commonLen > 0) prefix else NONE,
                changedCount = commonLen.coerceAtLeast(0),
                insertPosition = if (insertedCount > 0) prefix + commonLen else NONE,
                insertCount = insertedCount.coerceAtLeast(0),
                removePosition = if (removedCount > 0) prefix + commonLen else NONE,
                removeCount = removedCount.coerceAtLeast(0),
                unhighlightPosition = unhighlight,
                highlightPosition = highlight,
            )
        }
    }
}
