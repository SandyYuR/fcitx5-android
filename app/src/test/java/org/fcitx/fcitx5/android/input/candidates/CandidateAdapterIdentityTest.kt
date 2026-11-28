/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.app.Activity
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateActionContext
import org.fcitx.fcitx5.android.core.CandidateSelectionTarget
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CandidateAdapterIdentityTest {
    @Test fun horizontalAdapterHandlesHashCollisionsAndRepeatedCandidatesWithFreshTargets() = fixture { activity ->
        val (first, second) = collidingCandidates()
        val adapter = HorizontalCandidateViewAdapter(ThemePreset.MaterialLight)
        val view = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            itemAnimator = null
            this.adapter = adapter
        }
        activity.setContentView(view)
        assertFalse(adapter.hasStableIds())
        adapter.updateCandidates(arrayOf(first.target(1, 0), second.target(1, 1)), 3)
        layout(view)
        val original = view.findViewHolderForAdapterPosition(0) as CandidateViewHolder

        // Different rows can collide, and an inserted row can repeat another row's entire content.
        val next = arrayOf(second.target(2, 0), first.target(2, 1), first.target(2, 2))
        adapter.updateCandidates(next, 3, activeIndex = 2)
        assertNull(original.selectionFor(adapter.candidates[0]))
        layout(view)
        assertHorizontalRows(view, adapter, next)

        val removed = arrayOf(second.target(3, 0), first.target(3, 1))
        adapter.updateCandidates(removed, 2)
        layout(view)
        assertHorizontalRows(view, adapter, removed)
    }

    @Test fun floatingAdapterKeepsCollidingAndRepeatedRowsSeparateFromItsPaginationRow() = fixture { activity ->
        val (first, second) = collidingCandidates()
        val clicks = mutableListOf<Int>()
        var prev = 0
        var next = 0
        val ui = PagedCandidatesUi(activity, ThemePreset.MaterialLight, { textSize = 20f },
            { clicks += it }, { _, _, _ -> }, { prev++ }, { next++ }, 0f)
        activity.setContentView(ui.root)
        val adapter = ui.root.adapter!!
        assertFalse(adapter.hasStableIds())
        val data = FcitxEvent.PagedCandidateEvent.Data(
            arrayOf(first, second, first.copy()), 1,
            FcitxEvent.PagedCandidateEvent.LayoutHint.Vertical, true, true,
        )
        for (candidates in listOf(data.candidates, arrayOf(second, first))) {
            ui.update(data.copy(candidates = candidates), FloatingCandidatesOrientation.Vertical)
            layout(ui.root)
            assertEquals(candidates.size + 1, adapter.itemCount)
            candidates.forEachIndexed { index, candidate ->
                assertEquals(RecyclerView.NO_ID, adapter.getItemId(index))
                val holder = ui.root.findViewHolderForAdapterPosition(index) as PagedCandidatesUi.UiHolder.Candidate
                assertEquals(candidate.label + candidate.text + " " + candidate.comment, holder.ui.root.text.toString())
                assertTrue(holder.itemView.performClick())
                assertEquals(index, clicks.last())
            }
            val pagination = ui.root.findViewHolderForAdapterPosition(candidates.size) as PagedCandidatesUi.UiHolder.Pagination
            assertEquals(RecyclerView.NO_ID, adapter.getItemId(candidates.size))
            pagination.ui.prevIcon.performClick()
            pagination.ui.nextIcon.performClick()
        }
        assertEquals(2, prev)
        assertEquals(2, next)
    }

    private fun collidingCandidates(): Pair<CandidateWord, CandidateWord> {
        val first = CandidateWord("1 ", "候选", "aaa", true)
        val second = CandidateWord("2 ", "候选", "Baa", true)
        assertNotEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        return first to second
    }

    private fun CandidateWord.target(version: Long, index: Int) = copy().apply {
        selectionTarget = CandidateSelectionTarget(CandidateActionContext(9, version), index, false)
    }

    private fun assertHorizontalRows(view: RecyclerView, adapter: HorizontalCandidateViewAdapter,
        expected: Array<CandidateWord>) {
        assertEquals(expected.size, adapter.itemCount)
        expected.forEachIndexed { index, candidate ->
            assertEquals(RecyclerView.NO_ID, adapter.getItemId(index))
            val holder = view.findViewHolderForAdapterPosition(index) as CandidateViewHolder
            assertEquals(candidate, holder.candidate)
            assertEquals(candidate.selectionTarget, holder.selectionFor(adapter.candidates[index]))
        }
    }

    private fun layout(view: RecyclerView) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1400, 800)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fixture(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try { block(controller.get()) }
        finally { controller.pause().stop().destroy() }
    }
}
