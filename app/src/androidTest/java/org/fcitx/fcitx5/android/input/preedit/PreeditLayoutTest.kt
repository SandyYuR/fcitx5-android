/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Exercises Android measurement, text wrapping and the actual drawable, without starting the IME. */
class PreeditLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun longPreeditAndAuxTextStayInsideSymmetricCorners() = onMain {
        val ui = createUi()
        update(ui, LONG_TEXT, auxUp = "prefix ", auxDown = LONG_TEXT, cursor = 12)
        layout(ui, 600)

        assertBodyBounds(ui, 52, 548)
        textViews(ui).forEach { text ->
            assertTrue("long text must wrap", text.lineCount > 1)
            assertEquals(text.text.length, text.layout.getLineEnd(text.lineCount - 1))
            val available = text.width - text.paddingLeft - text.paddingRight
            for (line in 0 until text.lineCount) {
                assertTrue("line $line exceeds its text area", text.layout.getLineMax(line) <= available + 1f)
            }
        }
        assertEquals(568, ui.actualContentWidth)

        val bitmap = backgroundBitmap(ui)
        try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width / 2) {
                    val left = Color.alpha(bitmap.getPixel(x, y))
                    val right = Color.alpha(bitmap.getPixel(bitmap.width - 1 - x, y))
                    assertTrue("background is asymmetric at ($x, $y)", abs(left - right) <= 2)
                }
                for (x in 568 until bitmap.width) {
                    assertEquals("flare must end before the card corner", 0, Color.alpha(bitmap.getPixel(x, y)))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun sidePaddingAndOneHandBlankSpaceAreHandledIndependently() = onMain {
        val ui = createUi()
        update(ui, LONG_TEXT)
        for ((left, right) in listOf(8 to 8, 64 to 64, 0 to 180, 180 to 0)) {
            ui.root.setPadding(left, 0, right, 0)
            layout(ui, 600)
            assertBodyBounds(ui, max(32, left) + 20, 600 - max(32, right) - 20)
            assertEquals(600 - max(32, right), ui.actualContentWidth)
        }
    }

    @Test
    fun shortTextKeepsNaturalWidthAndShrinksAgainAfterLongText() = onMain {
        val ui = createUi()
        update(ui, "ni")
        layout(ui, 600)
        val shortWidth = ui.actualContentWidth
        val shortHeight = ui.root.height
        assertTrue(shortWidth < 300)

        update(ui, LONG_TEXT)
        layout(ui, 600)
        assertEquals(568, ui.actualContentWidth)
        assertTrue(ui.root.height > shortHeight)

        update(ui, "ni")
        layout(ui, 600)
        assertEquals(shortWidth, ui.actualContentWidth)
        assertEquals(shortHeight, ui.root.height)
    }

    @Test
    fun resizingRewrapsTextAndZeroRadiiKeepFullWidth() = onMain {
        val ui = createUi()
        update(ui, LONG_TEXT)
        layout(ui, 820)
        assertBodyBounds(ui, 52, 768)
        val wideHeight = ui.root.height

        layout(ui, 420)
        assertBodyBounds(ui, 52, 368)
        assertTrue(ui.root.height > wideHeight)

        // The floating candidate window shares PreeditUi but supplies no decoration radii.
        val plain = createUi(toolbarRadius = 0f, preeditRadius = 0f)
        update(plain, LONG_TEXT)
        layout(plain, 420)
        assertBodyBounds(plain, 0, 420)
        assertEquals(420, plain.actualContentWidth)
        plain.update(FcitxEvent.InputPanelEvent.Data())
        assertEquals(0, plain.actualContentWidth)
    }

    private fun createUi(toolbarRadius: Float = 32f, preeditRadius: Float = 20f) = PreeditUi(
        instrumentation.targetContext,
        ThemePreset.MaterialLight,
        setupTextView = { setPadding(12, 0, 12, 0) },
        cardTopRadiusProvider = { toolbarRadius },
        preeditRadiusProvider = { preeditRadius }
    ).apply { configureDecoration(Color.WHITE) }

    private fun update(ui: PreeditUi, text: String, auxUp: String = "", auxDown: String = "", cursor: Int = -1) {
        fun formatted(value: String, position: Int = -1) = FormattedText(arrayOf(value), intArrayOf(0), position)
        ui.update(FcitxEvent.InputPanelEvent.Data(
            preedit = formatted(text, cursor),
            auxUp = formatted(auxUp),
            auxDown = formatted(auxDown),
            tabs = emptyArray()
        ))
        // Use deterministic pixel metrics without changing the user's font preferences.
        textViews(ui).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40f)
            it.typeface = Typeface.MONOSPACE
        }
    }

    private fun layout(ui: PreeditUi, width: Int) {
        ui.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
    }

    private fun wrapper(ui: PreeditUi) = (ui.root as ViewGroup).getChildAt(0) as ViewGroup

    private fun content(ui: PreeditUi) = wrapper(ui).getChildAt(0) as ViewGroup

    private fun textViews(ui: PreeditUi) = content(ui).let { group ->
        (0 until group.childCount).map { group.getChildAt(it) as TextView }
    }

    private fun assertBodyBounds(ui: PreeditUi, left: Int, right: Int) {
        val wrapper = wrapper(ui)
        val content = content(ui)
        assertEquals(left, wrapper.left + content.left)
        assertEquals(right, wrapper.left + content.right)
        textViews(ui).filter { it.visibility == View.VISIBLE }.forEach {
            assertTrue("text view exceeds the body", it.right <= content.width)
        }
    }

    private fun backgroundBitmap(ui: PreeditUi): Bitmap {
        val bitmap = Bitmap.createBitmap(ui.root.width, ui.root.height, Bitmap.Config.ARGB_8888)
        val wrapper = wrapper(ui)
        Canvas(bitmap).apply {
            translate(wrapper.left.toFloat(), wrapper.top.toFloat())
            wrapper.background.draw(this)
        }
        return bitmap
    }

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
        val LONG_TEXT = "cang mang de tian ya shi wo de ai mian yan de qing shan jiao xia hua zheng kai ".repeat(3)
    }
}
