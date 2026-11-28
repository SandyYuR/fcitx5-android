/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.app.Activity
import android.graphics.Typeface
import android.os.Looper
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CandidateActionContext
import org.fcitx.fcitx5.android.core.CandidateSelectionTarget
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.AutoScaleTextView
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
    private fun selectable(text: String, version: Long, index: Int = 0) = word(text).apply {
        selectionTarget = CandidateSelectionTarget(CandidateActionContext(9, version), index, false)
    }

    @Test fun identicalTextUpdatesItsClickTargetWithoutRebindingTheHolder() = fixture { view, adapter ->
        adapter.updateCandidates(arrayOf(selectable("a", 1)), -1)
        layout(view)
        val holder = view.findViewHolderForAdapterPosition(0) as CandidateViewHolder
        adapter.fullBinds = 0
        val next = selectable("a", 2)
        adapter.updateCandidates(arrayOf(next), -1)
        layout(view)
        assertEquals(0, adapter.fullBinds)
        assertSame(holder, view.findViewHolderForAdapterPosition(0))
        assertEquals(next.selectionTarget, holder.selectionFor(adapter.candidates[0]))
        assertNull(holder.selectionFor(selectable("not-yet-displayed", 2)))
    }

    @Test fun targetRefreshPreservesTheCurrentWindowAndOriginalCandidateIndex() = fixture { view, adapter ->
        val old = arrayOf(selectable("a", 1, 0), selectable("b", 1, 1))
        adapter.updateCandidates(old.copyOfRange(1, 2), -1, indexOffset = 1)
        layout(view)
        val holder = view.findViewHolderForAdapterPosition(0) as CandidateViewHolder
        adapter.fullBinds = 0
        val next = arrayOf(selectable("a", 2, 0), selectable("b", 2, 1))
        assertTrue(adapter.updateSelectionTargets(next))
        layout(view)
        assertEquals(1, adapter.indexOffset)
        assertEquals(0, adapter.fullBinds)
        assertEquals(next[1].selectionTarget, holder.selectionFor(adapter.candidates[0]))
    }

    private class Adapter(content: CandidateIndexBadgeContent, enabled: Boolean) : HorizontalCandidateViewAdapter(
        ThemePreset.MaterialLight, initialCandidateIndexBadgeEnabled = enabled,
        initialCandidateIndexBadgeContent = content
    ) {
        var fullBinds = 0
        var payloadBinds = 0
        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            fullBinds++
            super.onBindViewHolder(holder, position)
        }
        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) payloadBinds++
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun word(text: String, label: String = "") = CandidateWord(label, text, "", false)
    private fun badge(holder: CandidateViewHolder) = holder.ui.root.getChildAt(1) as TextView
    private fun highlight(holder: CandidateViewHolder) = (holder.ui.root.getChildAt(0) as View).background
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

    @Test fun movingOnlyTheHighlightUsesPayloadsWithoutRebindingCandidateContent() = fixture { view, adapter ->
        adapter.updateCandidates(arrayOf(word("a"), word("b"), word("c")), 3, activeIndex = 0)
        layout(view)
        val first = view.findViewHolderForAdapterPosition(0) as CandidateViewHolder
        val second = view.findViewHolderForAdapterPosition(1) as CandidateViewHolder
        assertNotNull(highlight(first))
        assertNull(highlight(second))
        adapter.fullBinds = 0
        adapter.payloadBinds = 0

        adapter.updateActiveIndex(1)
        layout(view)

        assertEquals(0, adapter.fullBinds)
        assertTrue(adapter.payloadBinds >= 2)
        assertSame(first, view.findViewHolderForAdapterPosition(0))
        assertSame(second, view.findViewHolderForAdapterPosition(1))
        assertNull(highlight(first))
        assertNotNull(highlight(second))
        assertEquals("a", first.candidate.text)
        assertEquals("b", second.candidate.text)
    }

    @Test fun shiftedBadgeAndHighlightPayloadsAreAppliedTogetherAtNewPositions() = fixture { view, adapter ->
        adapter.updateCandidates(arrayOf(word("b"), word("c"), word("d"), word("e")), 5, activeIndex = 2)
        layout(view)
        val oldD = view.findViewHolderForAdapterPosition(2) as CandidateViewHolder
        val oldE = view.findViewHolderForAdapterPosition(3) as CandidateViewHolder
        assertNotNull(highlight(oldD))
        assertNull(highlight(oldE))
        adapter.fullBinds = 0
        adapter.payloadBinds = 0

        adapter.updateCandidates(arrayOf(word("a"), word("b"), word("c"), word("d"), word("e")), 5,
            activeIndex = 4)
        layout(view)

        val movedD = view.findViewHolderForAdapterPosition(3) as CandidateViewHolder
        val movedE = view.findViewHolderForAdapterPosition(4) as CandidateViewHolder
        assertSame(oldD, movedD)
        assertSame(oldE, movedE)
        assertEquals(1, adapter.fullBinds)
        assertTrue(adapter.payloadBinds >= 4)
        assertNull(highlight(movedD))
        assertNotNull(highlight(movedE))
        assertEquals("4", badge(movedD).text.toString())
        assertEquals("5", badge(movedE).text.toString())
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

    @Test fun bindingOneRowBeforeIdenticalCandidateUpdateStillRefreshesAllFonts() =
        assertFontRefreshAfterOneRowBinds(insertAtHead = false)

    @Test fun bindingOneRowBeforeStructuralUpdateStillRefreshesRetainedFonts() =
        assertFontRefreshAfterOneRowBinds(insertAtHead = true)

    private fun assertFontRefreshAfterOneRowBinds(insertAtHead: Boolean) {
        val original = FontProviders.provider
        val fonts = object : FontProviderApi {
            override fun clearCache() {}
            override val fontTypefaceMap = mutableMapOf<String, Typeface?>(
                "cand_font" to Typeface.MONOSPACE, "comment_font" to Typeface.SERIF,
            )
            override val fontSizeMap = mutableMapOf("cand_font" to 20f, "comment_font" to 12f)
            override var fontDataVersion = 1L
        }
        try {
            FontProviders.provider = fonts
            fixture { view, adapter ->
                val initial = arrayOf(commentedCandidate("a", 1, 5), commentedCandidate("b", 1, 6))
                adapter.updateCandidates(initial, 10, indexOffset = 5)
                layout(view)
                val first = view.findViewHolderForAdapterPosition(0) as CandidateViewHolder
                val second = view.findViewHolderForAdapterPosition(1) as CandidateViewHolder
                assertEquals(Typeface.MONOSPACE, candidateText(second).typeface)

                fonts.fontTypefaceMap["cand_font"] = Typeface.SERIF
                fonts.fontTypefaceMap["comment_font"] = Typeface.MONOSPACE
                fonts.fontSizeMap["cand_font"] = 28f
                fonts.fontSizeMap["comment_font"] = 18f
                fonts.fontDataVersion++
                FontProviders.provider = fonts
                // Rebinding one existing holder must not consume the whole row's invalidation.
                adapter.onBindViewHolder(first, 0)
                assertEquals(Typeface.SERIF, candidateText(first).typeface)
                assertEquals(Typeface.MONOSPACE, candidateText(second).typeface)
                adapter.fullBinds = 0
                var fullChanges = 0
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { fullChanges++ }
                })

                val texts = if (insertAtHead) listOf("x", "a", "b") else listOf("a", "b")
                val next = texts.mapIndexed { position, text ->
                    commentedCandidate(text, 2, position + 5)
                }.toTypedArray()
                adapter.updateCandidates(next, 10, indexOffset = 5)
                layout(view)

                assertEquals(1, fullChanges)
                assertEquals(next.size, adapter.fullBinds)
                assertEquals(5, adapter.indexOffset)
                next.forEachIndexed { position, candidate ->
                    val holder = view.findViewHolderForAdapterPosition(position) as CandidateViewHolder
                    val text = candidateText(holder)
                    assertEquals(candidate.text + " comment", text.text.toString())
                    assertEquals(Typeface.SERIF, text.typeface)
                    assertEquals(28f * text.resources.displayMetrics.scaledDensity, text.textSize, 0.01f)
                    val commentStart = candidate.text.length + 1
                    val commentPaint = TextPaint(text.paint).apply {
                        (text.text as Spanned).getSpans(commentStart, commentStart + 1,
                            CharacterStyle::class.java).forEach { it.updateDrawState(this) }
                    }
                    assertEquals(Typeface.MONOSPACE, commentPaint.typeface)
                    assertEquals(FontProviders.commentFontSizePx(text.context).toFloat(), commentPaint.textSize, 0.01f)
                    assertEquals(candidate.selectionTarget, holder.selectionFor(adapter.candidates[position]))
                    assertEquals(position + 5, holder.idx)
                    assertNull(holder.selectionFor(null))
                    assertNull(holder.selectionFor(commentedCandidate("not-yet-displayed", 2, position + 5)))
                }
                adapter.fullBinds = 0
                adapter.updateCandidates(next, 10, indexOffset = 5)
                layout(view)
                assertEquals(1, fullChanges)
                assertEquals(0, adapter.fullBinds)
            }
        } finally { FontProviders.provider = original }
    }

    private fun commentedCandidate(text: String, version: Long, index: Int) =
        CandidateWord("", text, "comment", true).apply {
            selectionTarget = CandidateSelectionTarget(CandidateActionContext(9, version), index, false)
        }

    private fun candidateText(holder: CandidateViewHolder) =
        (holder.ui.root.getChildAt(0) as ViewGroup).getChildAt(0) as AutoScaleTextView
}
