/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.expanded

import android.app.Activity
import android.graphics.Typeface
import android.os.Looper
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.paging.PagingData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateActionContext
import org.fcitx.fcitx5.android.core.CandidateSelectionTarget
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.font.FontProviderApi
import org.fcitx.fcitx5.android.input.font.FontProviders
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
class PagingCandidateViewAdapterTest {
    @Test fun fontChangeStillAcceptsTheNewOffsetBeforeAnyHolderConsumesIt() = fixture { view, adapter, fonts, candidates ->
        fonts.publish()
        adapter.fullBinds = 0
        adapter.refreshWithOffset(8, 11)
        assertEquals(8, adapter.offset)
        assertEquals(11L, adapter.generation)
        layout(view)
        assertEquals(2, adapter.fullBinds)
        val holder = view.findViewHolderForAdapterPosition(0) as CandidateViewHolder
        assertEquals(8, holder.idx)
        assertFontAndTarget(holder, adapter, candidates)
    }

    @Test fun fontChangeStillAcceptsANewCandidateGenerationAtTheSameOffset() = fixture { view, adapter, fonts, candidates ->
        fonts.publish()
        adapter.fullBinds = 0
        adapter.refreshWithOffset(5, 12)
        assertEquals(5, adapter.offset)
        assertEquals(12L, adapter.generation)
        layout(view)
        assertEquals(2, adapter.fullBinds)
        assertFontAndTarget(view.findViewHolderForAdapterPosition(0) as CandidateViewHolder, adapter, candidates)
    }

    @Test fun fontOnlyRefreshRebindsEqualTextAndAnIdenticalRequestDoesNotRebindAgain() = fixture { view, adapter, fonts, candidates ->
        fonts.publish()
        adapter.fullBinds = 0
        adapter.refreshWithOffset(5, 11)
        layout(view)
        assertEquals(2, adapter.fullBinds)
        assertFontAndTarget(view.findViewHolderForAdapterPosition(0) as CandidateViewHolder, adapter, candidates)
        adapter.fullBinds = 0
        adapter.refreshWithOffset(5, 11)
        layout(view)
        assertEquals(0, adapter.fullBinds)
    }

    @Test fun bindingOneRowBeforeTheNewOffsetDoesNotConsumeThePageFontRefresh() = fixture { view, adapter, fonts, candidates ->
        fonts.publish()
        adapter.onBindViewHolder(view.findViewHolderForAdapterPosition(0) as CandidateViewHolder, 0)
        adapter.fullBinds = 0
        adapter.refreshWithOffset(8, 11)
        layout(view)
        assertEquals(8, adapter.offset)
        assertEquals(11L, adapter.generation)
        assertEquals(2, adapter.fullBinds)
        assertFontAndTarget(view.findViewHolderForAdapterPosition(0) as CandidateViewHolder, adapter, candidates)
        assertFontAndTarget(view.findViewHolderForAdapterPosition(1) as CandidateViewHolder, adapter, candidates, 1)
    }

    @Test fun samePagingRequestReachesTheAdapterThroughTheRealWindowAfterOneRowBinds() = fixture { view, adapter, fonts, candidates ->
        val window = Window(adapter, view.layoutManager!!)
        fonts.publish()
        adapter.onBindViewHolder(view.findViewHolderForAdapterPosition(0) as CandidateViewHolder, 0)
        adapter.fullBinds = 0
        val request = ExpandedCandidateRefreshRequest(5, 11, fonts.fontDataVersion)
        val runRefresh = BaseExpandedCandidateWindow::class.java.getDeclaredMethod(
            "runOffsetRefresh", ExpandedCandidateRefreshRequest::class.java,
        ).apply { isAccessible = true }
        runRefresh.invoke(window, request)
        layout(view)
        assertEquals(5, adapter.offset)
        assertEquals(11L, adapter.generation)
        assertEquals(2, adapter.fullBinds)
        assertFontAndTarget(view.findViewHolderForAdapterPosition(0) as CandidateViewHolder, adapter, candidates)
        assertFontAndTarget(view.findViewHolderForAdapterPosition(1) as CandidateViewHolder, adapter, candidates, 1)
        adapter.fullBinds = 0
        runRefresh.invoke(window, request)
        layout(view)
        assertEquals(0, adapter.fullBinds)
    }

    private class Window(
        override val adapter: PagingCandidateViewAdapter,
        override val layoutManager: RecyclerView.LayoutManager,
    ) : BaseExpandedCandidateWindow<Window>(Window::class) {
        override fun onCreateCandidateLayout(): ExpandedCandidateLayout = error("font refresh must preserve the existing layout")
        override fun prevPage() {}
        override fun nextPage() {}
    }

    private fun assertFontAndTarget(holder: CandidateViewHolder, adapter: PagingCandidateViewAdapter,
        candidates: List<CandidateWord>, position: Int = 0) {
        val candidate = candidates[position]
        val text = (holder.ui.root.getChildAt(0) as ViewGroup).getChildAt(0) as AutoScaleTextView
        assertEquals("body comment", text.text.toString())
        assertEquals(Typeface.SERIF, text.typeface)
        assertEquals(28f * text.resources.displayMetrics.scaledDensity, text.textSize, 0.01f)
        val commentPaint = TextPaint(text.paint).apply {
            (text.text as Spanned).getSpans(5, 6, CharacterStyle::class.java).forEach { it.updateDrawState(this) }
        }
        assertEquals(Typeface.MONOSPACE, commentPaint.typeface)
        assertEquals(FontProviders.commentFontSizePx(text.context).toFloat(), commentPaint.textSize, 0.01f)
        // Styling/offset changes never manufacture a target for the still displayed page.
        assertEquals(candidate.selectionTarget, holder.selectionFor(adapter.peek(position)))
        assertNull(holder.selectionFor(null))
        assertNull(holder.selectionFor(CandidateWord("1 ", "not-yet-displayed", "comment", true).apply {
            selectionTarget = CandidateSelectionTarget(CandidateActionContext(9, 12), 8, true)
        }))
    }

    private class Adapter : PagingCandidateViewAdapter(ThemePreset.MaterialLight) {
        var fullBinds = 0
        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            fullBinds++
            super.onBindViewHolder(holder, position)
        }
    }

    private class Fonts : FontProviderApi {
        override fun clearCache() {}
        override val fontTypefaceMap = mutableMapOf<String, Typeface?>(
            "cand_font" to Typeface.MONOSPACE, "comment_font" to Typeface.SERIF,
        )
        override val fontSizeMap = mutableMapOf("cand_font" to 20f, "comment_font" to 12f)
        override var fontDataVersion = 1L
        fun publish() {
            fontTypefaceMap["cand_font"] = Typeface.SERIF
            fontTypefaceMap["comment_font"] = Typeface.MONOSPACE
            fontSizeMap["cand_font"] = 28f
            fontSizeMap["comment_font"] = 18f
            fontDataVersion++
            FontProviders.provider = this
        }
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun fixture(block: (RecyclerView, Adapter, Fonts, List<CandidateWord>) -> Unit) {
        val previousFonts = FontProviders.provider
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val owner = Owner()
        try {
            val fonts = Fonts()
            FontProviders.provider = fonts
            val adapter = Adapter()
            adapter.refreshWithOffset(5, 11)
            val candidates = (5..6).mapIndexed { position, index ->
                CandidateWord("${position + 1} ", "body", "comment", true).apply {
                    selectionTarget = CandidateSelectionTarget(CandidateActionContext(9, 11), index, true)
                }
            }
            val view = RecyclerView(controller.get()).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                this.adapter = adapter
            }
            controller.get().setContentView(view)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            adapter.submitData(owner.lifecycle, PagingData.from(candidates))
            layout(view)
            assertEquals(2, adapter.itemCount)
            assertNotNull(view.findViewHolderForAdapterPosition(0))
            assertNotNull(view.findViewHolderForAdapterPosition(1))
            block(view, adapter, fonts, candidates)
        } finally {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
            FontProviders.provider = previousFonts
        }
    }

    private fun layout(view: RecyclerView) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1400, 400)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
