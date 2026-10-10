/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.dimensions.dp
import kotlin.math.roundToInt

class CandidateIndexBadgeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.createConfigurationContext(
        Configuration(instrumentation.targetContext.resources.configuration).apply {
            fontScale = 1f
        },
    )

    @Test
    fun everyCornerKeepsBadgeInsidePaddingAndResetsOldMargins() = onMain {
        val ui = candidateUi(CandidateWord("", "中", "", false), enableOverflow = true)
        val badge = badge(ui)
        for (number in NUMBERS) {
            for (position in CandidateIndexBadgePosition.entries) {
                badge.layoutParams = (badge.layoutParams as FrameLayout.LayoutParams).apply {
                    marginStart = -context.dp(4)
                    marginEnd = -context.dp(4)
                    topMargin = -context.dp(4)
                    bottomMargin = -context.dp(4)
                }
                ui.setIndexBadge(number.toString(), position)
                layout(ui)
                assertBadgeLayout(ui, number.toString(), position)
            }
        }
    }

    @Test
    fun badgeVisibilityAndPositionNeverChangeCandidateGeometry() = onMain {
        val candidates = listOf(
            CandidateWord("", "中", "", false),
            CandidateWord("", "中", "zhong", true),
            CandidateWord("", LONG_TEXT, "", false),
            CandidateWord("", LONG_TEXT, "comment", true),
        )
        for (enableOverflow in listOf(false, true)) {
            for (wrapWidth in listOf(false, true)) {
                for (candidate in candidates) {
                    val ui = candidateUi(candidate, enableOverflow)
                    layout(ui, wrapWidth)
                    assertEquals(View.GONE, badge(ui).visibility)
                    assertEquals(
                        if (enableOverflow) AutoScaleTextView.Mode.None
                        else AutoScaleTextView.Mode.Proportional,
                        text(ui).scaleMode,
                    )
                    if (wrapWidth) {
                        assertTrue(ui.root.measuredWidth >= context.dp(40))
                        assertTrue(ui.root.measuredWidth < context.dp(480))
                    } else if (candidate.text == LONG_TEXT) {
                        val viewport = content(ui)
                        val availableWidth = viewport.width - viewport.paddingLeft - viewport.paddingRight
                        assertEquals(enableOverflow, text(ui).width > availableWidth)
                    }
                    val before = geometry(ui)
                    val scenario = "${candidate.text}/${candidate.comment}, " +
                        "overflow=$enableOverflow, wrap=$wrapWidth"
                    for (number in NUMBERS) {
                        for (position in CandidateIndexBadgePosition.entries) {
                            ui.setIndexBadge(number.toString(), position)
                            layout(ui, wrapWidth)
                            assertBadgeLayout(ui, number.toString(), position)
                            assertEquals("$scenario, $number/$position", before, geometry(ui))

                            ui.setIndexBadge(null, position)
                            layout(ui, wrapWidth)
                            assertEquals(View.GONE, badge(ui).visibility)
                            assertEquals("$scenario, hidden/$position", before, geometry(ui))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun adapterToggleSupportsTwoDigitIndicesAndDefaultsToTopLeft() = onMain {
        for (keyboard in listOf(Configuration.KEYBOARD_NOKEYS, Configuration.KEYBOARD_QWERTY)) {
            val keyboardContext = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    this.keyboard = keyboard
                    keyboardHidden = Configuration.KEYBOARDHIDDEN_NO
                    hardKeyboardHidden = if (keyboard == Configuration.KEYBOARD_QWERTY) {
                        Configuration.HARDKEYBOARDHIDDEN_NO
                    } else {
                        Configuration.HARDKEYBOARDHIDDEN_YES
                    }
                },
            )
            val adapter = HorizontalCandidateViewAdapter(ThemePreset.MaterialLight)
            val holder = adapter.onCreateViewHolder(FrameLayout(keyboardContext), 0)
            val candidates = Array(99) { CandidateWord("", "候选${it + 1}", "", false) }
            adapter.updateCandidates(candidates, candidates.size)
            adapter.onBindViewHolder(holder, 9)
            assertEquals(View.GONE, badge(holder.ui).visibility)

            adapter.setCandidateIndexBadgeEnabled(true)
            for (number in NUMBERS) {
                adapter.onBindViewHolder(holder, number - 1)
                fixInstanceFonts(holder.ui)
                layout(holder.ui)
                assertBadgeLayout(holder.ui, number.toString(), CandidateIndexBadgePosition.TopLeft)
            }
            for (position in CandidateIndexBadgePosition.entries) {
                adapter.setCandidateIndexBadgePosition(position)
                adapter.onBindViewHolder(holder, 9)
                fixInstanceFonts(holder.ui)
                layout(holder.ui)
                assertBadgeLayout(holder.ui, "10", position)
            }

            adapter.setCandidateIndexBadgeEnabled(false)
            adapter.onBindViewHolder(holder, 9)
            assertEquals(View.GONE, badge(holder.ui).visibility)
            assertEquals("", badge(holder.ui).text.toString())
        }
    }

    private fun candidateUi(candidate: CandidateWord, enableOverflow: Boolean): CandidateItemUi {
        return CandidateItemUi(
            context,
            ThemePreset.MaterialLight,
            Typeface.MONOSPACE,
            Typeface.MONOSPACE,
        ).also { ui ->
            ui.root.minimumWidth = context.dp(40)
            ui.configureHighlightSpacing(context.dp(4), context.dp(8))
            ui.setHorizontalOverflowEnabled(enableOverflow)
            ui.updateCandidate(candidate)
            fixInstanceFonts(ui)
        }
    }

    private fun fixInstanceFonts(ui: CandidateItemUi) {
        ui.root.longPressEnabled = false
        ui.root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val text = text(ui)
        text.typeface = Typeface.MONOSPACE
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        val styled = SpannableString(text.text)
        for (span in styled.getSpans(0, styled.length, AbsoluteSizeSpan::class.java)) {
            val start = styled.getSpanStart(span)
            val end = styled.getSpanEnd(span)
            val flags = styled.getSpanFlags(span)
            styled.removeSpan(span)
            styled.setSpan(AbsoluteSizeSpan(sp(12f).roundToInt(), false), start, end, flags)
        }
        text.text = styled
        badge(ui).typeface = Typeface.MONOSPACE
    }

    private fun layout(ui: CandidateItemUi, wrapWidth: Boolean = false) {
        val width = context.dp(if (wrapWidth) 480 else 40)
        val height = context.dp(44)
        ui.root.measure(
            View.MeasureSpec.makeMeasureSpec(
                width,
                if (wrapWidth) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY,
            ),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
        assertEquals(height, ui.root.measuredHeight)
        if (!wrapWidth) assertEquals(width, ui.root.measuredWidth)
    }

    private fun assertBadgeLayout(
        ui: CandidateItemUi,
        text: String,
        position: CandidateIndexBadgePosition,
    ) {
        val root = ui.root
        val badge = badge(ui)
        val params = badge.layoutParams as FrameLayout.LayoutParams
        val atStart = position == CandidateIndexBadgePosition.TopLeft ||
            position == CandidateIndexBadgePosition.BottomLeft
        val atTop = position == CandidateIndexBadgePosition.TopLeft ||
            position == CandidateIndexBadgePosition.TopRight
        val expectedGravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or
            (if (atStart) Gravity.START else Gravity.END)
        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals(text, badge.text.toString())
        assertFalse(badge.includeFontPadding)
        assertEquals(sp(10f), badge.textSize, 0.01f)
        assertEquals(0f, badge.translationX, 0f)
        assertEquals(0f, badge.translationY, 0f)
        assertEquals(expectedGravity, params.gravity)
        assertEquals(if (atStart) context.dp(2) else 0, params.marginStart)
        assertEquals(if (atStart) 0 else context.dp(2), params.marginEnd)
        assertEquals(0, params.topMargin)
        assertEquals(0, params.bottomMargin)
        assertTrue(params.leftMargin >= 0 && params.rightMargin >= 0)
        val outerPadding = context.dp(4)
        assertEquals(Rect(outerPadding, outerPadding, outerPadding, outerPadding), padding(root))
        assertTrue(root.clipChildren)
        assertTrue(root.clipToPadding)
        assertTrue("badge must be measured", badge.width > 0 && badge.height > 0)
        val clip = Rect(
            outerPadding,
            outerPadding,
            root.width - outerPadding,
            root.height - outerPadding,
        )
        assertTrue("$text/$position: ${bounds(badge)} outside $clip", clip.contains(bounds(badge)))
        assertEquals(
            context.dp(2),
            if (atStart) badge.left - clip.left else clip.right - badge.right,
        )
        assertEquals(0, if (atTop) badge.top - clip.top else clip.bottom - badge.bottom)
    }

    private fun content(ui: CandidateItemUi) = ui.root.getChildAt(0) as ViewGroup

    private fun text(ui: CandidateItemUi) = content(ui).getChildAt(0) as AutoScaleTextView

    private fun badge(ui: CandidateItemUi) = ui.root.getChildAt(1) as TextView

    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)

    private fun padding(view: View) =
        Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)

    private fun geometry(view: View) = ViewGeometry(
        bounds(view),
        view.measuredWidth to view.measuredHeight,
        padding(view),
        view.translationX,
        view.translationY,
        view.baseline,
    )

    private fun geometry(ui: CandidateItemUi) = CandidateGeometry(
        geometry(ui.root),
        geometry(content(ui)),
        geometry(text(ui)),
        text(ui).textSize,
        text(ui).typeface,
        text(ui).scaleMode,
    )

    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        context.resources.displayMetrics,
    )

    private data class ViewGeometry(
        val bounds: Rect,
        val measuredSize: Pair<Int, Int>,
        val padding: Rect,
        val translationX: Float,
        val translationY: Float,
        val baseline: Int,
    )

    private data class CandidateGeometry(
        val root: ViewGeometry,
        val content: ViewGeometry,
        val text: ViewGeometry,
        val textSize: Float,
        val typeface: Typeface?,
        val scaleMode: AutoScaleTextView.Mode,
    )

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        val NUMBERS = listOf(1, 9, 10, 99)
        const val LONG_TEXT = "candidate-with-a-long-tail"
    }
}
