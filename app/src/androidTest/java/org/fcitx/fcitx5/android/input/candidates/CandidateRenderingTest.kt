/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviderApi
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.ui.main.update.UpdateMirrorListActivity
import org.junit.Assert.*
import org.junit.Test

/** Android component rendering and cache regression; these are not input latency measurements. */
class CandidateRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun plainAndCommentedCandidatesMatchTheOriginalSpanRendering() = withWindow { host ->
        for (theme in listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark)) {
            for (overflow in listOf(false, true)) {
                val fixture = createFixture(host, theme, overflow)
                try {
                    for (word in listOf(
                        CandidateWord("1", "MMMMMMMM", ""),
                        CandidateWord("1", "你", ""),
                        CandidateWord("1", "A🙂B", ""),
                        CandidateWord("1", "a-long-candidate-with-a-long-tail", " \t"),
                        CandidateWord("1", "MMMM", "iiii", true),
                        CandidateWord("1", "MMMM", "iiii", false),
                    )) {
                        fixture.ui.updateCandidate(word)
                        for (active in listOf(false, true, false)) {
                            fixture.ui.setActive(active)
                            assertOriginalRendering(fixture, word, active)
                        }
                    }
                } finally { fixture.removeFrom(host) }
            }
        }
    }

    @Test fun plainHighlightKeepsContentColorsAndPixels() = withWindow { host ->
        for (theme in listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark)) {
            val fixture = createFixture(host, theme, overflow = true)
            try {
                for (comment in listOf("", " \t\n")) {
                    val word = CandidateWord("1", "MMMMMMMM", comment)
                    fixture.ui.updateCandidate(word)
                    layoutCandidate(fixture)
                    val original = fixture.text.text
                    assertFalse(fixture.text.isLayoutRequested)
                    for (active in listOf(true, false, true)) {
                        fixture.ui.setActive(active)
                        assertEquals(original.toString(), fixture.text.text.toString())
                        assertEquals(
                            if (active) theme.genericActiveForegroundColor else theme.candidateTextColor,
                            fixture.text.currentTextColor,
                        )
                        assertOriginalRendering(fixture, word, active)
                    }
                }
            } finally { fixture.removeFrom(host) }
        }
    }

    @Test fun fontRefreshAndBlankCommentTransitionsKeepMetricsAndPixels() = withWindow { host ->
        val fixture = createFixture(host, ThemePreset.MaterialLight, overflow = true)
        try {
            val plain = CandidateWord("1", "MMMMMMMM", "")
            fixture.ui.updateCandidate(plain)
            assertOriginalRendering(fixture, plain, active = false)
            val oldWidth = fixture.text.measuredWidth
            val original = fixture.text.text

            installFonts(body = Typeface.SERIF, bodySize = 28f, commentSize = 18f)
            fixture.ui.refreshConfiguredFont()
            fixture.reference.typeface = Typeface.SERIF
            assertEquals(fixture.reference.typeface, fixture.text.typeface)
            assertEquals(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28f, fixture.text.resources.displayMetrics),
                fixture.text.textSize,
                0.01f,
            )
            assertEquals(original.toString(), fixture.text.text.toString())
            assertTrue("Changing fonts must still invalidate text metrics", fixture.text.isLayoutRequested)
            assertOriginalRendering(fixture, plain, active = false)
            assertNotEquals("The font fixture must change natural width", oldWidth, fixture.text.measuredWidth)

            val commented = CandidateWord("1", "MMMMMMMM", "iiii", true)
            fixture.ui.updateCandidate(commented)
            fixture.ui.setActive(true)
            assertOriginalRendering(fixture, commented, active = true)
            val oldCommentWidth = fixture.text.measuredWidth
            installFonts(body = Typeface.SERIF, bodySize = 28f, comment = Typeface.MONOSPACE, commentSize = 24f)
            fixture.ui.applyConfiguredCommentTypeface(Typeface.MONOSPACE)
            fixture.ui.refreshConfiguredFont()
            assertOriginalRendering(fixture, commented, active = true)
            assertNotEquals("Comment font/size refresh must update natural width", oldCommentWidth, fixture.text.measuredWidth)

            for (word in listOf(plain.copy(comment = " \n"), commented, plain)) {
                fixture.ui.updateCandidate(word)
                for (active in listOf(false, true, false)) {
                    fixture.ui.setActive(active)
                    assertOriginalRendering(fixture, word, active)
                }
            }
        } finally { fixture.removeFrom(host) }
    }

    private class Fixture(
        val ui: CandidateItemUi,
        val text: AutoScaleTextView,
        val reference: AutoScaleTextView,
        val overflow: Boolean,
    ) {
        fun removeFrom(host: FrameLayout) {
            host.removeView(ui.root)
            host.removeView(reference)
        }
    }

    private fun createFixture(host: FrameLayout, theme: Theme, overflow: Boolean): Fixture {
        installFonts()
        val context = ContextThemeWrapper(host.context, R.style.Theme_InputViewTheme)
        val ui = CandidateItemUi(context, theme).apply {
            root.layoutDirection = View.LAYOUT_DIRECTION_LTR
            root.longPressEnabled = false
            setHorizontalOverflowEnabled(overflow)
            configureHighlightSpacing(0, 0)
            applyConfiguredCommentTypeface(Typeface.SERIF)
        }
        val text = (ui.root.getChildAt(0) as ViewGroup).getChildAt(0) as AutoScaleTextView
        val reference = AutoScaleTextView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isSingleLine = true
            gravity = Gravity.CENTER
            background = null
            scaleMode = if (overflow) AutoScaleTextView.Mode.None else AutoScaleTextView.Mode.Proportional
        }
        host.addView(ui.root, FrameLayout.LayoutParams(220, 100))
        host.addView(reference, FrameLayout.LayoutParams(220, 100))
        assertTrue(ui.root.isAttachedToWindow)
        assertTrue(reference.isAttachedToWindow)
        return Fixture(ui, text, reference, overflow)
    }

    private fun assertOriginalRendering(fixture: Fixture, word: CandidateWord, active: Boolean) {
        val text = fixture.text
        val reference = fixture.reference
        val theme = fixture.ui.theme
        val foreground = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val commentColor = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        // Resolve the controlled fixture independently, rather than copying potentially
        // stale metrics from the product. Let TextView apply system font adjustments once.
        reference.typeface = FontProviders.resolveTypeface("cand_font")
        reference.setTextSize(TypedValue.COMPLEX_UNIT_SP, FontProviders.getFontSize("cand_font", 20f))
        assertEquals(reference.typeface, text.typeface)
        assertEquals(reference.textSize, text.textSize, 0.01f)
        reference.setTextColor(foreground)
        // The previous renderer styled the entire body even when no comment was present.
        reference.text = buildSpannedString {
            color(foreground) { append(word.text) }
            if (word.comment.isNotBlank()) {
                if (word.spaceBetweenComment) append(" ")
                inSpans(
                    CustomTypefaceSpan(FontProviders.resolveCommentTypeface(text.typeface)),
                    AbsoluteSizeSpan(FontProviders.commentFontSizePx(text.context), false),
                ) { color(commentColor) { append(word.comment) } }
            }
        }
        layoutCandidate(fixture)
        reference.measure(
            View.MeasureSpec.makeMeasureSpec(if (fixture.overflow) 0 else 220,
                if (fixture.overflow) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        reference.layout(0, 0, reference.measuredWidth, reference.measuredHeight)
        assertEquals(word.textWithComment(), text.text.toString())
        assertEquals(reference.measuredWidth, text.measuredWidth)
        assertEquals(reference.measuredHeight, text.measuredHeight)
        assertEquals(reference.baseline, text.baseline)
        val actual = pixels(text)
        val expected = pixels(reference)
        try {
            val colors = IntArray(expected.width * expected.height)
            expected.getPixels(colors, 0, expected.width, 0, 0, expected.width, expected.height)
            assertTrue("The reference must draw visible glyphs", colors.any { Color.alpha(it) > 0 })
            assertTrue("Rendering changed for $word, active=$active, overflow=${fixture.overflow}", expected.sameAs(actual))
        } finally {
            actual.recycle()
            expected.recycle()
        }
    }

    private fun layoutCandidate(fixture: Fixture) {
        fixture.ui.root.measure(View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        fixture.ui.root.layout(0, 0, 220, 100)
    }

    private fun pixels(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }

    private fun installFonts(body: Typeface = Typeface.MONOSPACE, bodySize: Float = 20f,
        comment: Typeface = Typeface.SERIF, commentSize: Float = 12f) {
        FontProviders.provider = object : FontProviderApi {
            override fun clearCache() {}
            override val fontTypefaceMap = mutableMapOf<String, Typeface?>("cand_font" to body, "comment_font" to comment)
            override val fontSizeMap = mutableMapOf("cand_font" to bodySize, "comment_font" to commentSize)
        }
    }

    private fun withWindow(block: (FrameLayout) -> Unit) {
        // This existing page only reads its configuration when opened; no action is invoked.
        // It supplies a real window without creating an editor or starting an input session.
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UpdateMirrorListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
        instrumentation.waitForIdleSync()
        val originalFonts = FontProviders.provider
        try {
            onMain {
                val host = FrameLayout(activity)
                activity.setContentView(host)
                assertTrue(host.isAttachedToWindow)
                block(host)
            }
        } finally {
            onMain {
                FontProviders.provider = originalFonts
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
