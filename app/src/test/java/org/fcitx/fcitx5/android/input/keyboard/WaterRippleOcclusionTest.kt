/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * 水波纹遮挡判定：只有真正画出一层可见键底的键才应该挖空涟漪。
 *
 * 透明键和空白占位键如果也参与挖空，键内没有波纹而键外有，交界处就会显出一条轮廓。
 * 同时验证 [KeyView.shouldOccludeWaterRipple]、实际键底像素以及水波纹层缓存的遮挡结果：
 * 描边、阴影和按压高亮都可能让 Drawable 看起来有内容，但填充色 alpha 为 0 就是透明。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [28, 35], qualifiers = "w393dp-h851dp-xhdpi",
    application = FcitxApplication::class, shadows = [EditorApplicationShadow::class]
)
class WaterRippleOcclusionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousKeyBorder = true
    private var previousBorderStroke = false
    private var previousRipple = false

    @Before
    fun enableKeyBorders() {
        previousKeyBorder = ThemeManager.prefs.keyBorder.getValue()
        previousBorderStroke = ThemeManager.prefs.keyBorderStroke.getValue()
        previousRipple = ThemeManager.prefs.keyRippleEffect.getValue()
        ThemeManager.prefs.keyBorder.setValue(true)
        ThemeManager.prefs.keyBorderStroke.setValue(false)
        ThemeManager.prefs.keyRippleEffect.setValue(true)
    }

    @After
    fun restoreKeyBorders() {
        ThemeManager.prefs.keyBorder.setValue(previousKeyBorder)
        ThemeManager.prefs.keyBorderStroke.setValue(previousBorderStroke)
        ThemeManager.prefs.keyRippleEffect.setValue(previousRipple)
    }

    private fun textKey(def: KeyDef.Appearance.Text = KeyDef.Appearance.Text("a", 20f)) =
        TextKeyView(context, ThemePreset.PixelLight, def)

    private fun placeholderKey(transparent: Boolean, backgroundColor: Int?) = TextKeyView(
        context, ThemePreset.PixelLight,
        PlaceholderKey(
            transparentBackground = transparent,
            backgroundColor = backgroundColor
        ).appearance as KeyDef.Appearance.Text
    )

    private inner class ProbeKey(theme: Theme, def: KeyDef.Appearance) : KeyView(context, theme, def) {
        val face get() = appearanceView
    }

    private fun stickyKey(normalColor: Int? = null, activeColor: Int? = null) = ProbeKey(
        ThemePreset.PixelLight.copy(keyShadowColor = Color.TRANSPARENT),
        KeyDef.Appearance.Text(
            "", 20f, variant = KeyDef.Appearance.Variant.Alternative, backgroundColor = normalColor
        ).apply { activeBackgroundColor = activeColor }
    ).apply { setStickyActive(true) }

    private fun measureAndLayout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun centerAlpha(view: View): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        } finally {
            bitmap.recycle()
        }
    }

    private fun fillAlpha(key: ProbeKey): Int {
        val bitmap = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
        return try {
            // Let the real hierarchy assign drawable state and bounds before drawing its keycap.
            key.draw(Canvas(bitmap))
            bitmap.eraseColor(Color.TRANSPARENT)
            // Foreground press/activated highlights are deliberately excluded from fill occlusion.
            key.face.background?.draw(Canvas(bitmap))
            Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        } finally {
            bitmap.recycle()
        }
    }

    private fun startRipple(ripple: KeyboardWaterRippleView) {
        ripple.startRipple(60f, 36f, Color.BLUE, maxRadius = 160f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    }

    private fun withRipple(
        key: ProbeKey,
        block: (FrameLayout, KeyboardWaterRippleView) -> Unit
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val root = FrameLayout(activity.get())
        val ripple = KeyboardWaterRippleView(activity.get())
        try {
            root.addView(ripple, FrameLayout.LayoutParams(120, 72))
            root.addView(key, FrameLayout.LayoutParams(120, 72))
            activity.get().setContentView(root)
            measureAndLayout(root)
            assertTrue("the real snapshot builder only consumes attached keys", key.isAttachedToWindow)
            ripple.setOccluders(listOf(key))
            startRipple(ripple)
            block(root, ripple)
        } finally {
            root.removeAllViews()
            activity.get().finish()
            activity.pause().stop().destroy()
        }
    }

    private fun assertFillAndSnapshot(key: ProbeKey, ripple: KeyboardWaterRippleView, expectedAlpha: Int) {
        val occludes = expectedAlpha > 0
        assertEquals("the actual keycap fill must match its current state", expectedAlpha, fillAlpha(key))
        assertEquals("occlusion must follow the current fill", occludes, key.shouldOccludeWaterRipple())
        assertEquals(
            "the existing ripple must refresh its cached occluders without another layout",
            !occludes, centerAlpha(ripple) > 0
        )
    }

    private fun assertActivationTransition(normalColor: Int, activeColor: Int) {
        val key = stickyKey(normalColor, activeColor)
        withRipple(key) { _, ripple ->
            val background = key.face.background
            assertFalse("enabling sticky appearance does not activate the key", key.isActivated)
            assertFillAndSnapshot(key, ripple, Color.alpha(normalColor))
            key.isActivated = true
            assertFillAndSnapshot(key, ripple, Color.alpha(activeColor))
            key.isActivated = false
            assertFillAndSnapshot(key, ripple, Color.alpha(normalColor))
            assertSame("activation must reuse the state drawable", background, key.face.background)
        }
    }

    @Test
    fun transparentNormalAndOpaqueActiveFollowActivationInExistingRipple() {
        assertActivationTransition(Color.TRANSPARENT, Color.RED)
    }

    @Test
    fun opaqueNormalAndTransparentActiveFollowActivationInExistingRipple() {
        assertActivationTransition(Color.RED, Color.TRANSPARENT)
    }

    @Test
    fun themeChangesRefreshInactiveAndActiveFillAndExistingSnapshots() {
        val transparentNormal = ThemePreset.PixelLight.copy(
            altKeyBackgroundColor = Color.TRANSPARENT,
            genericActiveBackgroundColor = Color.RED,
            keyShadowColor = Color.TRANSPARENT
        )
        val opaqueNormal = transparentNormal.copy(
            altKeyBackgroundColor = Color.RED,
            genericActiveBackgroundColor = Color.TRANSPARENT
        )
        val key = stickyKey().apply { updateTheme(transparentNormal) }
        withRipple(key) { _, ripple ->
            assertFillAndSnapshot(key, ripple, 0)
            key.updateTheme(opaqueNormal)
            assertFillAndSnapshot(key, ripple, 255)
            key.isActivated = true
            assertFillAndSnapshot(key, ripple, 0)
            key.updateTheme(transparentNormal)
            assertFillAndSnapshot(key, ripple, 255)
            key.isActivated = false
            assertFillAndSnapshot(key, ripple, 0)
        }
    }

    @Test
    fun disablingAndReenablingStickyAppearanceRestoresTheCurrentFillAndSnapshot() {
        for ((normal, active) in listOf(Color.TRANSPARENT to Color.RED, Color.RED to Color.TRANSPARENT)) {
            val key = stickyKey(normal, active).apply { isActivated = true }
            withRipple(key) { _, ripple ->
                assertFillAndSnapshot(key, ripple, Color.alpha(active))
                key.setStickyActive(false)
                assertTrue("disabling appearance capability must preserve activation", key.isActivated)
                assertFillAndSnapshot(key, ripple, Color.alpha(normal))
                key.setStickyActive(true)
                assertFillAndSnapshot(key, ripple, Color.alpha(active))
            }
        }
    }

    private fun occlusionListenerCount(key: KeyView): Int {
        val field = KeyView::class.java.getDeclaredField("waterRippleOcclusionChangedListeners")
        field.isAccessible = true
        return (field.get(key) as Collection<*>).size
    }

    @Test
    fun replacingAndReattachingOccludersKeepsOneSubscriptionAndRefreshesTheSnapshot() {
        val first = stickyKey(Color.TRANSPARENT, Color.RED)
        val replacement = stickyKey(Color.RED, Color.TRANSPARENT)
        withRipple(first) { root, ripple ->
            assertEquals(1, occlusionListenerCount(first))
            ripple.setOccluders(listOf(first))
            assertEquals("setting the same occluders must not duplicate listeners", 1, occlusionListenerCount(first))
            root.addView(replacement, FrameLayout.LayoutParams(120, 72))
            measureAndLayout(root)
            ripple.setOccluders(listOf(replacement))
            assertEquals(0, occlusionListenerCount(first))
            assertEquals(1, occlusionListenerCount(replacement))
            assertFillAndSnapshot(replacement, ripple, 255)

            root.removeView(ripple)
            assertEquals("detached ripple must release the key", 0, occlusionListenerCount(replacement))
            replacement.isActivated = true
            root.addView(ripple, 0, FrameLayout.LayoutParams(120, 72))
            measureAndLayout(root)
            assertEquals(1, occlusionListenerCount(replacement))
            startRipple(ripple)
            assertFillAndSnapshot(replacement, ripple, 0)
            replacement.isActivated = false
            assertFillAndSnapshot(replacement, ripple, 255)
        }
        assertEquals(0, occlusionListenerCount(replacement))
    }

    @Test
    fun occlusionListenersAreIndependentAndOnlyNotifiedForChangedOcclusion() {
        val key = stickyKey(Color.TRANSPARENT, Color.RED)
        val changes = mutableListOf<Boolean>()
        var secondListenerCalls = 0
        val first: () -> Unit = { changes.add(key.shouldOccludeWaterRipple()) }
        val second: () -> Unit = { secondListenerCalls++ }
        key.addWaterRippleOcclusionChangedListener(first)
        key.addWaterRippleOcclusionChangedListener(second)
        key.addWaterRippleOcclusionChangedListener(second)
        key.isActivated = false
        key.setStickyActive(true)
        key.isActivated = true
        key.isActivated = true
        key.updateTheme(ThemePreset.PixelLight.copy(keyShadowColor = Color.TRANSPARENT))
        assertEquals(listOf(true), changes)
        assertEquals(1, secondListenerCalls)
        key.removeWaterRippleOcclusionChangedListener(first)
        key.isActivated = false
        assertEquals(listOf(true), changes)
        assertEquals(2, secondListenerCalls)
        key.removeWaterRippleOcclusionChangedListener(second)
        key.isActivated = true
        assertEquals(2, secondListenerCalls)
    }

    @Test
    fun opaqueKeyBackgroundStillOccludesTheRipple() {
        assertTrue(textKey().shouldOccludeWaterRipple())
        assertTrue(
            textKey(
                KeyDef.Appearance.Text("a", 20f, backgroundColor = 0x80ffffff.toInt())
            ).shouldOccludeWaterRipple()
        )
    }

    @Test
    fun transparentKeyBackgroundDoesNotOccludeTheRipple() {
        assertFalse(
            textKey(
                KeyDef.Appearance.Text("a", 20f, backgroundColor = Color.TRANSPARENT)
            ).shouldOccludeWaterRipple()
        )
    }

    @Test
    fun emptyPlaceholderDoesNotOccludeTheRipple() {
        assertFalse(placeholderKey(transparent = true, backgroundColor = null).shouldOccludeWaterRipple())
    }

    @Test
    fun decoratedPlaceholderWithTransparentFillDoesNotOccludeTheRipple() {
        assertFalse(
            placeholderKey(
                transparent = false,
                backgroundColor = Color.TRANSPARENT
            ).shouldOccludeWaterRipple()
        )
    }

    @Test
    fun decoratedPlaceholderWithOpaqueFillStillOccludesTheRipple() {
        assertTrue(
            placeholderKey(
                transparent = false,
                backgroundColor = Color.WHITE
            ).shouldOccludeWaterRipple()
        )
    }

    @Test
    fun keyWithoutBorderFillDoesNotOccludeTheRipple() {
        assertFalse(
            textKey(
                KeyDef.Appearance.Text("a", 20f, border = KeyDef.Appearance.Border.Off)
            ).shouldOccludeWaterRipple()
        )
    }
}
