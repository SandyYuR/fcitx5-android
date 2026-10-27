/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.app.Activity
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.candidates.CandidateIndexBadgeContent
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.font.FontProviderApi
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
class MovingCandidateBadgeTest {
    private class Adapter(content: CandidateIndexBadgeContent, enabled: Boolean) : HorizontalCandidateViewAdapter(
        ThemePreset.MaterialLight, initialCandidateIndexBadgeEnabled = enabled,
        initialCandidateIndexBadgeContent = content
    ) {
        var fullBinds = 0
        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            fullBinds++
            super.onBindViewHolder(holder, position)
        }
    }

    private fun word(text: String, label: String = "") = CandidateWord(label, text, "", false)
    private fun badge(holder: CandidateViewHolder) = holder.ui.root.getChildAt(1) as TextView
    private fun layout(view: RecyclerView) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1400, 140)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fixture(content: CandidateIndexBadgeContent = CandidateIndexBadgeContent.SequenceOneBased,
        enabled: Boolean = true, block: (RecyclerView, Adapter) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val adapter = Adapter(content, enabled)
            val view = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                itemAnimator = null
                this.adapter = adapter
            }
            activity.setContentView(view)
            block(view, adapter)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun headInsertionRefreshesAllMovedSequenceModesWithoutRebuildingText() {
        for (content in CandidateIndexBadgeContent.entries) fixture(content) { view, adapter ->
            adapter.updateCandidates(arrayOf(word("a"), word("b")), 10, indexOffset = 20)
            layout(view)
            val before = view.findViewHolderForAdapterPosition(1) as CandidateViewHolder
            adapter.fullBinds = 0
            adapter.updateCandidates(arrayOf(word("x"), word("a"), word("b")), 10, indexOffset = 20)
            layout(view)
            val after = view.findViewHolderForAdapterPosition(2) as CandidateViewHolder
            assertSame(before, after)
            assertEquals(if (content == CandidateIndexBadgeContent.SequenceZeroBased) "2" else "3",
                badge(after).text.toString())
            assertEquals(22, after.idx)
            assertEquals(1, adapter.fullBinds)
        }
    }

    @Test fun headRemovalUpdatesThePreservedHoldersUsingTheirNewPositions() = fixture { view, adapter ->
        adapter.updateCandidates(arrayOf(word("x"), word("a"), word("b")), 10)
        layout(view)
        val before = view.findViewHolderForAdapterPosition(2) as CandidateViewHolder
        adapter.fullBinds = 0
        adapter.updateCandidates(arrayOf(word("a"), word("b")), 10)
        layout(view)
        val after = view.findViewHolderForAdapterPosition(1) as CandidateViewHolder
        assertSame(before, after)
        assertEquals("2", badge(after).text.toString())
        assertEquals(0, adapter.fullBinds)
    }

    @Test fun explicitEngineLabelsRemainIntactAfterTheHolderMoves() = fixture(CandidateIndexBadgeContent.EngineLabel) { view, adapter ->
        adapter.updateCandidates(arrayOf(word("a", "k"), word("b", "j")), 10)
        layout(view)
        adapter.updateCandidates(arrayOf(word("x", "h"), word("a", "k"), word("b", "j")), 10)
        layout(view)
        assertEquals("j", badge(view.findViewHolderForAdapterPosition(2) as CandidateViewHolder).text.toString())
    }

    @Test fun disabledBadgesDoNotAddSuffixRebinds() = fixture(enabled = false) { view, adapter ->
        var changes = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { changes++ }
        })
        adapter.updateCandidates(arrayOf(word("a"), word("b")), 10)
        layout(view)
        adapter.updateCandidates(arrayOf(word("x"), word("a"), word("b")), 10)
        layout(view)
        assertEquals(0, changes)
        assertEquals(View.GONE, badge(view.findViewHolderForAdapterPosition(2) as CandidateViewHolder).visibility)
    }

    @Test fun changingFontAndCandidateContentTogetherRefreshesTheRetainedSuffix() {
        val original = FontProviders.provider
        val fonts = object : FontProviderApi {
            override fun clearCache() {}
            override val fontTypefaceMap = mutableMapOf<String, Typeface?>()
            override val fontSizeMap = mutableMapOf<String, Float>()
            override var fontDataVersion = 1L
        }
        try {
            FontProviders.provider = fonts
            fixture { view, adapter ->
                adapter.updateCandidates(arrayOf(word("a"), word("b")), 10)
                layout(view)
                var fullChanges = 0
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { fullChanges++ }
                })
                fonts.fontTypefaceMap["cand_font"] = Typeface.MONOSPACE
                fonts.fontDataVersion++
                adapter.fullBinds = 0
                adapter.updateCandidates(arrayOf(word("x"), word("a"), word("b")), 10)
                layout(view)
                assertEquals(1, fullChanges)
                assertEquals(3, adapter.fullBinds)
            }
        } finally { FontProviders.provider = original }
    }
}
