/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateOverflowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun horizontalTextKeepsNaturalWidthAndShowsTailInsideViewport() = onMain {
        val ui = candidateUi()
        layout(ui)

        val (viewport, text) = views(ui)
        assertTrue("text should keep its natural width", text.width > viewport.width)
        assertEquals(
            -(text.width - viewport.width).toFloat(),
            text.translationX,
            0.5f,
        )
    }

    @Test
    fun identicalRelayoutPreservesManualScrollButContentAndResizeFollowTail() = onMain {
        val ui = candidateUi()
        layout(ui)
        val (viewport, text) = views(ui)
        val tailOffset = text.translationX

        dragRight(ui.root, 24f)
        val manualOffset = text.translationX
        assertTrue("dragging right should reveal the leading text", manualOffset > tailOffset)
        layout(ui)
        assertEquals(manualOffset, text.translationX, 0.5f)

        ui.updateCandidate(CandidateWord("", LONG_TEXT + "-new", "", false))
        layout(ui)
        assertEquals(-(text.width - viewport.width).toFloat(), text.translationX, 0.5f)

        dragRight(ui.root, 20f)
        assertNotEquals(text.translationX, -(text.width - viewport.width).toFloat())
        layout(ui, width = 260)
        assertEquals(-(text.width - viewport.width).toFloat(), text.translationX, 0.5f)
    }

    @Test
    fun horizontalDragDoesNotClickCandidate() = onMain {
        val ui = candidateUi()
        layout(ui)
        val text = views(ui).second
        val tailOffset = text.translationX
        var clicks = 0
        ui.root.setOnClickListener { clicks++ }

        dispatch(ui.root, MotionEvent.ACTION_DOWN, 40f, 40f, 0)
        dispatch(ui.root, MotionEvent.ACTION_MOVE, 100f, 40f, 16)
        dispatch(ui.root, MotionEvent.ACTION_UP, 100f, 40f, 32)

        assertEquals(0, clicks)
        assertTrue("drag should move the text away from the tail", text.translationX > tailOffset)
    }

    @Test
    fun nonHorizontalCandidateKeepsProportionalTextMode() = onMain {
        val ui = candidateUi(enableOverflow = false)
        layout(ui)
        val (viewport, text) = views(ui)

        assertEquals(AutoScaleTextView.Mode.Proportional, text.scaleMode)
        assertTrue("scaled text should fit its viewport", text.width <= viewport.width)
        assertEquals(0f, text.translationX, 0.5f)
    }

    @Test
    fun horizontalAdapterEnablesOverflowMode() = onMain {
        val adapter = HorizontalCandidateViewAdapter(ThemePreset.MaterialLight)
        val holder = adapter.onCreateViewHolder(FrameLayout(targetContext), 0)
        val text = (holder.ui.root.getChildAt(0) as ViewGroup).getChildAt(0) as AutoScaleTextView

        assertEquals(AutoScaleTextView.Mode.None, text.scaleMode)
    }

    @Test
    fun horizontalAdapterDisablesOverflowMode() = onMain {
        val adapter = HorizontalCandidateViewAdapter(
            ThemePreset.MaterialLight,
            initialHorizontalOverflowEnabled = false,
        )
        val holder = adapter.onCreateViewHolder(FrameLayout(targetContext), 0)
        val text = (holder.ui.root.getChildAt(0) as ViewGroup).getChildAt(0) as AutoScaleTextView

        assertEquals(AutoScaleTextView.Mode.Proportional, text.scaleMode)
    }

    private fun candidateUi(enableOverflow: Boolean = true): CandidateItemUi {
        return CandidateItemUi(
            targetContext,
            ThemePreset.MaterialLight,
            Typeface.MONOSPACE,
        ).also { ui ->
            ui.root.longPressEnabled = false
            if (enableOverflow) ui.enableHorizontalOverflow()
            ui.configureHighlightSpacing(0, 0)
            ui.updateCandidate(CandidateWord("", LONG_TEXT, "", false))
        }
    }

    private fun layout(ui: CandidateItemUi, width: Int = 220) {
        ui.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        ui.root.layout(0, 0, width, 80)
    }

    private fun views(ui: CandidateItemUi): Pair<ViewGroup, AutoScaleTextView> {
        val viewport = ui.root.getChildAt(0) as ViewGroup
        return viewport to (viewport.getChildAt(0) as AutoScaleTextView)
    }

    private fun dragRight(root: android.view.View, distance: Float) {
        dispatch(root, MotionEvent.ACTION_DOWN, 40f, 40f, 0)
        dispatch(root, MotionEvent.ACTION_MOVE, 40f + distance, 40f, 16)
        dispatch(root, MotionEvent.ACTION_UP, 40f + distance, 40f, 32)
    }

    private fun dispatch(root: android.view.View, action: Int, x: Float, y: Float, time: Long) {
        val event = MotionEvent.obtain(0, time, action, x, y, 0)
        try {
            root.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                result = block()
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val LONG_TEXT = "a-very-long-candidate-value-that-must-overflow-the-candidate-slot"
    }
}
