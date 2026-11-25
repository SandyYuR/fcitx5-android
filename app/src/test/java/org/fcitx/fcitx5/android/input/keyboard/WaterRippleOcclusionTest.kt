/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Color
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 水波纹遮挡判定：只有真正画出一层可见键底的键才应该挖空涟漪。
 *
 * 透明键和空白占位键如果也参与挖空，键内没有波纹而键外有，交界处就会显出一条轮廓。
 * 这里不画图，只验证 [KeyView.shouldOccludeWaterRipple] 对「实际填充色」的判断：
 * 描边、阴影和按压高亮都可能让 Drawable 看起来有内容，但填充色 alpha 为 0 就是透明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28, 35], qualifiers = "w393dp-h851dp-xhdpi",
    application = FcitxApplication::class, shadows = [EditorApplicationShadow::class]
)
class WaterRippleOcclusionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousKeyBorder = true

    @Before
    fun enableKeyBorders() {
        previousKeyBorder = ThemeManager.prefs.keyBorder.getValue()
        ThemeManager.prefs.keyBorder.setValue(true)
    }

    @After
    fun restoreKeyBorders() {
        ThemeManager.prefs.keyBorder.setValue(previousKeyBorder)
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
