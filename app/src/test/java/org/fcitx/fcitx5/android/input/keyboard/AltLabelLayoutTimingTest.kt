/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
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
        ThemeManager.prefs.moveMainTextForAltLabel.setValue(true)
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

    @Test fun dualLabelsStayInsideKeysWithAndWithoutMovingMainContent() {
        for (moveMain in listOf(false, true)) {
            ThemeManager.prefs.moveMainTextForAltLabel.setValue(moveMain)
            val text = AltTextKeyView(context, ThemePreset.PixelLight,
                KeyDef.Appearance.AltText("Q", "1", "Q", 23f, altText1 = "~").apply {
                    directionalSwipeLabels = true
                })
            val image = ImageAltTextKeyView(context, ThemePreset.PixelLight,
                KeyDef.Appearance.ImageAltText(R.drawable.ic_baseline_backspace_24, "清空", altText1 = "删除").apply {
                    directionalSwipeLabels = true
                })
            for (scale in listOf(1f, 1.3f, 1f)) {
                for (key in listOf(text, image)) key.setTextScale(scale)
                for (height in (48..88) + 52) {
                    for ((key, labels) in listOf(text to listOf(text.altText, text.altText1),
                        image to listOf(image.altText, image.altText1))) {
                        layout(key, height)
                        for (label in labels) {
                            val parent = label.parent as View
                            val description = "${key.javaClass.simpleName}, move=$moveMain, scale=$scale, height=$height, label=${label.text}"
                            assertEquals(description, View.VISIBLE, label.visibility)
                            assertTrue("Top must stay inside the key: $description (${label.top})",
                                label.top >= key.vMargin)
                            assertTrue("Bottom must stay inside the key: $description (${label.bottom}/${parent.height})",
                                label.bottom <= parent.height - key.vMargin)
                        }
                    }
                }
            }
        }
    }

    @Test fun dualLabelsKeepIdenticalCenteredBoundsWhenMovementIsToggled() {
        val text = AltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.AltText("Q", "1", "Q", 23f, altText1 = "~").apply {
                directionalSwipeLabels = true
            })
        val image = ImageAltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.ImageAltText(R.drawable.ic_baseline_backspace_24, "清空", altText1 = "删除").apply {
                directionalSwipeLabels = true
            })
        val parts = listOf(text to listOf(text.mainText, text.altText, text.altText1),
            image to listOf(image.img, image.altText, image.altText1))
        for (height in listOf(52, 88, 40, 52)) {
            ThemeManager.prefs.moveMainTextForAltLabel.setValue(false)
            text.refreshAltTextLayout()
            image.refreshAltTextLayout()
            val previous = parts.map { (key, views) ->
                layout(key, height)
                views.map { Rect(it.left, it.top, it.right, it.bottom) }
            }
            ThemeManager.prefs.moveMainTextForAltLabel.setValue(true)
            text.refreshAltTextLayout()
            image.refreshAltTextLayout()
            parts.forEachIndexed { index, (key, views) ->
                layout(key, height)
                assertEquals("${key.javaClass.simpleName} at ${height}dp", previous[index],
                    views.map { Rect(it.left, it.top, it.right, it.bottom) })
                if (height >= 52) {
                    val parent = views.first().parent as View
                    assertEquals(parent.height / 2f, (views.first().top + views.first().bottom) / 2f, 1f)
                    views.drop(1).forEach { label ->
                        assertEquals(Gravity.CENTER, (label as TextView).gravity)
                        assertEquals(parent.width / 2f, (label.left + label.right) / 2f, 1f)
                    }
                }
            }
        }
    }

    @Test fun singleBottomLabelStillMovesTheMainContentWhenEnabled() {
        val key = AltTextKeyView(context, ThemePreset.PixelLight,
            KeyDef.Appearance.AltText("Q", "~", "Q", 23f))
        ThemeManager.prefs.moveMainTextForAltLabel.setValue(false)
        key.refreshAltTextLayout()
        layout(key, 64)
        val parent = key.mainText.parent as View
        assertEquals(parent.height / 2f, (key.mainText.top + key.mainText.bottom) / 2f, 1f)
        val centeredTop = key.mainText.top
        ThemeManager.prefs.moveMainTextForAltLabel.setValue(true)
        key.refreshAltTextLayout()
        layout(key, 64)
        assertTrue(key.mainText.top < centeredTop)
        assertEquals(View.GONE, key.altText1.visibility)
    }
}
