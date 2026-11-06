/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** Check actual child bounds after one traversal, before a layer switch can repair them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35], qualifiers = "w393dp-h851dp-xhdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class AltLabelLayoutTimingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    @Before fun useBottomLabels() {
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
    }

    private fun layout(key: KeyView, heightDp: Int) {
        key.measure(View.MeasureSpec.makeMeasureSpec(dp(60), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY))
        key.layout(0, 0, key.measuredWidth, key.measuredHeight)
    }

    private fun assertCorner(label: TextView, bottom: Boolean) {
        assertEquals(View.VISIBLE, label.visibility)
        assertEquals(Gravity.END, label.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        val parent = label.parent as View
        val params = label.layoutParams as ConstraintLayout.LayoutParams
        assertEquals("New corner constraints must be measured before children are laid out",
            parent.width - params.rightMargin, label.right)
        if (bottom) assertEquals(parent.height - params.bottomMargin, label.bottom)
        else assertEquals(params.topMargin, label.top)
    }

    @Test fun sandyShortNumberRowUsesCornersOnItsFirstLayout() {
        val row = Json.parseToJsonElement(File(
            "src/main/assets/bundled/keyboard_layouts/TextKeyboardLayout.Sandy-数字行26+万象九键.json"
        ).readText()).jsonObject.getValue("default").jsonArray.first().jsonArray
        for (index in listOf(0, 6, 7, 8, 9)) {
            val def = LayoutJsonUtils.createKeyDef(requireNotNull(LayoutJsonUtils.parseKeyJson(row[index].jsonObject)))!!
            val key = AltTextKeyView(context, ThemePreset.PixelLight, def.appearance as KeyDef.Appearance.AltText)
            layout(key, 40)
            assertCorner(key.altText1, bottom = true)
            assertTrue(key.shouldTriggerAltBySwipe(10, SwipeSymbolDirection.Up))
            assertFalse(key.shouldTriggerAltBySwipe(-10, SwipeSymbolDirection.Up))
        }
    }

    @Test fun ordinaryBottomLabelFallsBackBeforeItsFirstLayout() {
        val key = AltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.AltText("1", "!", "1", 23f))
        layout(key, 40)
        assertCorner(key.altText, bottom = false)
        assertTrue(key.shouldTriggerAltBySwipe(-10, SwipeSymbolDirection.Down))
    }

    @Test fun directionalTextAndIconLabelsUseBothCornersImmediately() {
        val text = AltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.AltText("1", "!", "1", 23f, altText1 = "@").apply {
                directionalSwipeLabels = true
            })
        val image = ImageAltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.ImageAltText(R.drawable.ic_baseline_backspace_24, "!", altText1 = "@").apply {
                directionalSwipeLabels = true
            })
        layout(text, 40)
        assertCorner(text.altText, bottom = false)
        assertCorner(text.altText1, bottom = true)
        layout(image, 40)
        assertCorner(image.altText, bottom = false)
        assertCorner(image.altText1, bottom = true)
    }

    @Test fun reusedKeysUpdateWithinTheSameTraversalWhenRowHeightChanges() {
        val text = AltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.AltText("1", "!", "1", 23f, altText1 = "@").apply {
                directionalSwipeLabels = true
            })
        val image = ImageAltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.ImageAltText(R.drawable.ic_baseline_backspace_24, "!", altText1 = "@").apply {
                directionalSwipeLabels = true
            })
        repeat(2) {
            for (key in listOf(text, image)) layout(key, 64)
            assertEquals(Gravity.CENTER, text.altText.gravity)
            assertEquals(Gravity.CENTER, image.altText.gravity)
            for (key in listOf(text, image)) layout(key, 40)
            assertCorner(text.altText, bottom = false)
            assertCorner(text.altText1, bottom = true)
            assertCorner(image.altText, bottom = false)
            assertCorner(image.altText1, bottom = true)
        }
    }

    @Test fun fontScaleChangesAtTheSameHeightKeepStackedAndCompactPositionsInSync() {
        val key = AltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.AltText("1", "!", "1", 23f))
        key.setTextScale(0.7f)
        layout(key, 40)
        assertEquals(Gravity.CENTER, key.altText.gravity)
        key.setTextScale(1f)
        layout(key, 40)
        assertCorner(key.altText, bottom = false)
        key.setTextScale(0.7f)
        layout(key, 40)
        assertEquals(Gravity.CENTER, key.altText.gravity)
        val params = key.altText.layoutParams as ConstraintLayout.LayoutParams
        assertEquals((key.altText.parent as View).height - params.bottomMargin, key.altText.bottom)
    }
}
