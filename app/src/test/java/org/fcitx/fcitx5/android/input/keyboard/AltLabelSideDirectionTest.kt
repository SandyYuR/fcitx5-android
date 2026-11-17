/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.AltLabelSide
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「副标签侧边位置」里的左右是**物理**左右：布局方向为 RTL 时也不能翻面。
 *
 * 角落定位用的是 leftMargin/rightMargin 这类绝对边距，文字对齐也必须用 LEFT/RIGHT；
 * 混用 Gravity.START/END 会在 RTL 下把文字画到另一边（边距在左、字形在右）。
 * 这里既断言解析后的绝对对齐，也把标签真的画出来量墨迹，确认字形落在所选那一侧。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [28, 35], qualifiers = "w393dp-h851dp-xhdpi",
    application = FcitxApplication::class, shadows = [EditorApplicationShadow::class]
)
class AltLabelSideDirectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun singleLabelKey() = AltTextKeyView(
        context, ThemePreset.PixelLight, KeyDef.Appearance.AltText(" ", "1", " ", 23f)
    )

    private fun directionalKey() = AltTextKeyView(
        context, ThemePreset.PixelLight,
        KeyDef.Appearance.AltText(" ", "1", " ", 23f, altText1 = "2").apply {
            directionalSwipeLabels = true
        }
    )

    private fun layout(key: KeyView, layoutDirection: Int) {
        key.layoutDirection = layoutDirection
        key.measure(
            View.MeasureSpec.makeMeasureSpec(dp(120), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.EXACTLY)
        )
        key.layout(0, 0, key.measuredWidth, key.measuredHeight)
    }

    /** 把标签单独画到白底上，返回文字墨迹的水平中心（相对标签左边）。 */
    private fun inkCenterX(label: TextView): Float {
        val bitmap = Bitmap.createBitmap(label.width, label.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        label.draw(canvas)
        var minX = Int.MAX_VALUE
        var maxX = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.luminance(bitmap.getPixel(x, y)) < 0.35f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        assertTrue("标签「" + label.text + "」没有画出任何墨迹", maxX >= minX)
        return (minX + maxX) / 2f
    }

    private fun assertResolvedGravity(label: TextView, layoutDirection: Int, expected: Int) {
        assertEquals(
            "RTL 下标签对齐必须解析成物理方向",
            expected,
            Gravity.getAbsoluteGravity(label.gravity, layoutDirection) and
                    Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
        )
    }

    private fun assertOnSide(label: TextView, atLeft: Boolean, description: String) {
        val center = inkCenterX(label)
        val full = description + ", center=" + center + ", width=" + label.width
        if (atLeft) assertTrue(full, center < label.width / 3f)
        else assertTrue(full, center > label.width * 2 / 3f)
    }

    @Test
    fun rtlKeepsTheLabelOnTheChosenPhysicalSide() {
        try {
            ThemeManager.prefs.altLabelOnSide.setValue(true)
            for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                for (atLeft in listOf(true, false)) {
                    ThemeManager.prefs.altLabelSide.setValue(
                        if (atLeft) AltLabelSide.Left else AltLabelSide.Right
                    )
                    val key = singleLabelKey()
                    key.refreshAltTextLayout()
                    layout(key, layoutDirection)
                    val label = key.altText
                    assertResolvedGravity(
                        label, layoutDirection, if (atLeft) Gravity.LEFT else Gravity.RIGHT
                    )
                    assertOnSide(
                        label, atLeft,
                        "dir=" + layoutDirection + ", atLeft=" + atLeft
                    )
                }
            }
        } finally {
            ThemeManager.prefs.altLabelOnSide.setValue(false)
        }
    }

    @Test
    fun rtlKeepsBothDirectionalLabelsOnTheChosenPhysicalSide() {
        try {
            ThemeManager.prefs.altLabelOnSide.setValue(true)
            for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                for (atLeft in listOf(true, false)) {
                    ThemeManager.prefs.altLabelSide.setValue(
                        if (atLeft) AltLabelSide.Left else AltLabelSide.Right
                    )
                    val key = directionalKey()
                    key.refreshAltTextLayout()
                    layout(key, layoutDirection)
                    for (label in listOf(key.altText, key.altText1)) {
                        assertResolvedGravity(
                            label, layoutDirection, if (atLeft) Gravity.LEFT else Gravity.RIGHT
                        )
                        assertOnSide(
                            label, atLeft,
                            "dir=" + layoutDirection + ", atLeft=" + atLeft +
                                    ", text=" + label.text
                        )
                    }
                    // 位置变了，滑动方向不变：上标签仍上滑、下标签仍下滑。
                    assertTrue(key.shouldTriggerAltBySwipe(-10, SwipeSymbolDirection.Up))
                    assertTrue(key.shouldTriggerAltBySwipe(10, SwipeSymbolDirection.Up))
                }
            }
        } finally {
            ThemeManager.prefs.altLabelOnSide.setValue(false)
        }
    }
}
