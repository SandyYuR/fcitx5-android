/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.app.Activity
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviderApi
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CandidateRenderingStateTest {
    @Test fun plainHighlightKeepsTextAndUpdatesColors() = fixture { ui, text ->
        for (comment in listOf("", " \t\n")) {
            ui.updateCandidate(CandidateWord("1", "candidate", comment))
            layout(ui)
            val original = text.text
            assertFalse(text.isLayoutRequested)
            for (active in listOf(true, false, true)) {
                ui.setActive(active)
                assertEquals("Highlight must retain the displayed content", original.toString(), text.text.toString())
                assertEquals(
                    if (active) ui.theme.genericActiveForegroundColor else ui.theme.candidateTextColor,
                    text.currentTextColor,
                )
            }
        }
    }

    @Test fun commentAndBlankTransitionsKeepBodyAndCommentColors() = fixture { ui, text ->
        for (comment in listOf("", "comment", " \t", "comment", "")) {
            for (active in listOf(false, true, false)) {
                ui.setActive(active)
                ui.updateCandidate(CandidateWord("1", "body", comment, true))
                assertEquals(if (comment.isBlank()) "body" else "body comment", text.text.toString())
                val foreground = if (active) ui.theme.genericActiveForegroundColor else ui.theme.candidateTextColor
                assertEquals(foreground, paintAt(text, 0).color)
                if (comment.isNotBlank()) {
                    val commentPaint = paintAt(text, 5)
                    assertEquals(
                        if (active) ui.theme.genericActiveForegroundColor else ui.theme.candidateCommentColor,
                        commentPaint.color,
                    )
                    assertEquals(Typeface.SERIF, commentPaint.typeface)
                }
            }
        }
    }

    @Test fun plainFontRefreshInvalidatesMetricsAndKeepsContent() = fixture { ui, text ->
        ui.updateCandidate(CandidateWord("1", "MMMMMMMM", ""))
        layout(ui)
        val original = text.text
        installFonts(body = Typeface.SERIF, bodySize = 28f, commentSize = 18f)
        ui.refreshConfiguredFont()
        assertEquals(Typeface.SERIF, text.typeface)
        assertEquals(28f * text.resources.displayMetrics.scaledDensity, text.textSize, 0.01f)
        assertEquals(original.toString(), text.text.toString())
        assertTrue("Font and size changes must still remeasure", text.isLayoutRequested)
    }

    @Test fun commentFontRefreshRebuildsMetricsEvenWithUnchangedText() = fixture { ui, text ->
        ui.updateCandidate(CandidateWord("1", "body", "comment", true))
        val before = text.text
        installFonts(body = Typeface.MONOSPACE, bodySize = 20f, commentSize = 18f)
        ui.applyConfiguredCommentTypeface(Typeface.MONOSPACE)
        ui.refreshConfiguredFont()
        assertEquals(before.toString(), text.text.toString())
        val commentPaint = paintAt(text, 5)
        assertEquals(Typeface.MONOSPACE, commentPaint.typeface)
        assertEquals(FontProviders.commentFontSizePx(text.context).toFloat(), commentPaint.textSize, 0.01f)
    }

    private fun fixture(block: (CandidateItemUi, AutoScaleTextView) -> Unit) {
        val originalFonts = FontProviders.provider
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            installFonts()
            val ui = CandidateItemUi(controller.get(), ThemePreset.MaterialLight)
            ui.applyConfiguredCommentTypeface(Typeface.SERIF)
            controller.get().setContentView(ui.root)
            val text = (ui.root.getChildAt(0) as ViewGroup).getChildAt(0) as AutoScaleTextView
            block(ui, text)
        } finally {
            controller.pause().stop().destroy()
            FontProviders.provider = originalFonts
        }
    }

    private fun installFonts(body: Typeface = Typeface.MONOSPACE, bodySize: Float = 20f, commentSize: Float = 12f) {
        FontProviders.provider = object : FontProviderApi {
            override fun clearCache() {}
            override val fontTypefaceMap = mutableMapOf<String, Typeface?>("cand_font" to body, "comment_font" to Typeface.SERIF)
            override val fontSizeMap = mutableMapOf("cand_font" to bodySize, "comment_font" to commentSize)
        }
    }

    private fun layout(ui: CandidateItemUi) {
        ui.root.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        ui.root.layout(0, 0, 500, 100)
    }

    private fun paintAt(text: AutoScaleTextView, position: Int): TextPaint = TextPaint(text.paint).apply {
        color = text.currentTextColor
        (text.text as? Spanned)?.getSpans(position, position + 1, CharacterStyle::class.java)?.forEach {
            it.updateDrawState(this)
        }
    }
}
