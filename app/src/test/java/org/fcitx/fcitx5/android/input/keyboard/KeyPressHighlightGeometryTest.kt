/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compare rendered contours after the real foreground has assigned its layer bounds. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [28, 35], qualifiers = "w393dp-h851dp-mdpi",
    application = FcitxApplication::class, shadows = [EditorApplicationShadow::class]
)
class KeyPressHighlightGeometryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = ThemeManager.prefs
    private val theme = ThemePreset.PixelLight.copy(
        // Use one opaque color so native color quantization cannot affect alpha comparisons.
        keyBackgroundColor = Color.WHITE,
        altKeyBackgroundColor = Color.WHITE,
        accentKeyBackgroundColor = Color.WHITE,
        spaceBarColor = Color.WHITE,
        keyShadowColor = Color.TRANSPARENT,
        keyPressHighlightColor = Color.WHITE
    )
    private var previousBorder = true
    private var previousStroke = false
    private var previousRipple = false
    private var previousOval = true
    private var previousHorizontalMargin = 2
    private var previousVerticalMargin = 3
    private var previousRadius = 12

    @Before
    fun configureGeometry() {
        previousBorder = prefs.keyBorder.getValue()
        previousStroke = prefs.keyBorderStroke.getValue()
        previousRipple = prefs.keyRippleEffect.getValue()
        previousOval = prefs.specialKeyOvalShape.getValue()
        previousHorizontalMargin = prefs.keyHorizontalMargin.getValue()
        previousVerticalMargin = prefs.keyVerticalMargin.getValue()
        previousRadius = prefs.keyRadius.getValue()
        prefs.keyBorder.setValue(true)
        prefs.keyBorderStroke.setValue(false)
        prefs.keyRippleEffect.setValue(true)
        prefs.specialKeyOvalShape.setValue(true)
        prefs.keyHorizontalMargin.setValue(5)
        prefs.keyVerticalMargin.setValue(9)
        prefs.keyRadius.setValue(12)
    }

    @After
    fun restorePreferences() {
        prefs.keyBorder.setValue(previousBorder)
        prefs.keyBorderStroke.setValue(previousStroke)
        prefs.keyRippleEffect.setValue(previousRipple)
        prefs.specialKeyOvalShape.setValue(previousOval)
        prefs.keyHorizontalMargin.setValue(previousHorizontalMargin)
        prefs.keyVerticalMargin.setValue(previousVerticalMargin)
        prefs.keyRadius.setValue(previousRadius)
    }

    private inner class ProbeKey(def: KeyDef.Appearance) : KeyView(context, theme, def) {
        val face get() = appearanceView
    }

    private fun key(
        border: KeyDef.Appearance.Border = KeyDef.Appearance.Border.Default,
        viewId: Int = -1
    ) = ProbeKey(KeyDef.Appearance.Text(" ", 20f, border = border, viewId = viewId)).apply {
        measure(
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, measuredWidth, measuredHeight)
        drawOnce()
    }

    private fun ProbeKey.drawOnce() {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }

    private fun contour(drawable: Drawable): IntArray {
        val bitmap = Bitmap.createBitmap(120, 72, Bitmap.Config.ARGB_8888)
        return try {
            drawable.draw(Canvas(bitmap))
            IntArray(bitmap.width * bitmap.height).also { pixels ->
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                for (i in pixels.indices) pixels[i] = Color.alpha(pixels[i])
            }
        } finally { bitmap.recycle() }
    }

    private fun ProbeKey.pressContour(): Drawable {
        val foreground = face.foreground
        return if (foreground is RippleDrawable) {
            foreground.findDrawableByLayerId(android.R.id.mask)
        } else {
            foreground.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
            foreground.current
        }
    }

    private fun assertSameContour(description: String, expected: Drawable, actual: Drawable) {
        val expectedPixels = contour(expected)
        val actualPixels = contour(actual)
        val differences = expectedPixels.indices.count { expectedPixels[it] != actualPixels[it] }
        assertEquals(
            "$description: $differences pixels differ; expected bounds=${expected.bounds}, actual bounds=${actual.bounds}",
            0, differences
        )
    }

    private fun ProbeKey.keycapFill(): Drawable {
        val background = face.background
        if (background !is LayerDrawable) return background
        // Compare the actual cap fill, excluding its separate shadow or stroke.
        val cap = background.getDrawable(background.numberOfLayers - 1)
        return (checkNotNull(cap.constantState).newDrawable().mutate() as GradientDrawable).apply {
            setStroke(0, Color.TRANSPARENT)
            bounds = cap.bounds
        }
    }

    private fun assertMatchesKeycap(key: ProbeKey) {
        val fill = key.keycapFill()
        assertSameContour("pressed mask must cover the keycap", fill, key.pressContour())
        key.face.foreground.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_activated)
        assertSameContour("activated fill must cover the keycap", fill, key.face.foreground)
    }

    @Test
    fun rippleMaskUsesTheSameInsetAsTheKeycap() {
        val key = key()
        val foreground = key.face.foreground as RippleDrawable
        val contentPadding = Rect()
        foreground.getDrawable(0).getPadding(contentPadding)
        println("contentPadding=$contentPadding maskBounds=${key.pressContour().bounds}")
        assertMatchesKeycap(key)
    }

    @Test
    fun rippleContourMatchesOutlinedKeycaps() {
        prefs.keyBorderStroke.setValue(true)
        assertMatchesKeycap(key())
        for (viewId in listOf(R.id.button_return, R.id.button_layout_switch)) {
            assertMatchesKeycap(key(border = KeyDef.Appearance.Border.Special, viewId = viewId))
        }
        prefs.keyBorder.setValue(false)
        assertMatchesKeycap(key(border = KeyDef.Appearance.Border.On))
    }

    @Test
    fun forcedBorderRetainsRoundedHighlightWithGlobalBordersOff() {
        prefs.keyBorder.setValue(false)
        for (ripple in listOf(false, true)) {
            prefs.keyRippleEffect.setValue(ripple)
            assertMatchesKeycap(key(border = KeyDef.Appearance.Border.On))
        }
    }

    @Test
    fun borderOffUsesRectangularHighlightWithGlobalBordersOn() {
        for (stroke in listOf(false, true)) {
            prefs.keyBorderStroke.setValue(stroke)
            for (ripple in listOf(false, true)) {
                prefs.keyRippleEffect.setValue(ripple)
                val key = key(border = KeyDef.Appearance.Border.Off)
                assertNull(key.face.background)
                val expected = insetRadiusDrawable(5, 9, 0f).apply { setBounds(0, 0, 120, 72) }
                assertSameContour("unbordered highlight must stay rectangular", expected, key.pressContour())
            }
        }
    }

    @Test
    fun pillKeyRetainsItsContourAfterThemeRefresh() {
        for (border in listOf(false, true)) {
            prefs.keyBorder.setValue(border)
            for (viewId in listOf(R.id.button_return, R.id.button_layout_switch)) {
                val key = key(border = KeyDef.Appearance.Border.Special, viewId = viewId)
                assertMatchesKeycap(key)
                key.updateTheme(theme.copy(name = "refreshed"))
                key.drawOnce()
                assertMatchesKeycap(key)
            }
        }
    }

    @Test
    fun borderlessSpacebarRetainsItsOwnInsetAndRadius() {
        prefs.keyBorder.setValue(false)
        val key = key(border = KeyDef.Appearance.Border.Special, viewId = R.id.button_space)
        assertMatchesKeycap(key)
        key.updateTheme(theme.copy(name = "refreshed"))
        key.drawOnce()
        assertMatchesKeycap(key)
    }

    @Test
    fun transparentPlaceholderStillHasNoHighlight() {
        val key = ProbeKey(KeyDef.Appearance.Text(" ", 20f).apply { transparentBackground = true })
        key.isPressed = true
        assertNull(key.face.background)
        assertNull(key.face.foreground)
        key.updateTheme(theme.copy(name = "refreshed"))
        assertNull(key.face.background)
        assertNull(key.face.foreground)
    }
}
