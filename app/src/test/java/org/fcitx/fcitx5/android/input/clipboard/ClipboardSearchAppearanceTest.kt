/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.Chip
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class ClipboardSearchAppearanceTest {
    // Deliberately different from the app palette: a copied raw keyboard slot must fail these checks.
    private val keyboard = ThemePreset.PixelLight.copy(
        clipboardEntryColor = 0xfffff000.toInt(), keyTextColor = 0xff0011ff.toInt(),
        accentKeyBackgroundColor = 0xff009900.toInt(), accentKeyTextColor = 0xffff00ff.toInt(),
        keyPressHighlightColor = 0xff00ffff.toInt(), popupBackgroundColor = 0xffaa1100.toInt())

    @Test fun cardsGroupsAndFeedbackUseEverySelectedPanelPalette() {
        val originalSelection = SettingsUiPreferences.read(appContext)
        val originalTheme = ReflectionHelpers.getStaticField<Theme?>(ThemeManager::class.java, "_activeTheme")
        try {
            ReflectionHelpers.setStaticField(ThemeManager::class.java, "_activeTheme", keyboard)
            val base = SettingsUiPreferences.Selection(mode = SettingsUiPreferences.MODE_LIGHT)
            val choices = listOf(base, base.copy(mode = SettingsUiPreferences.MODE_DARK),
                base.copy(palette = SettingsUiPreferences.PALETTE_AMBER),
                base.copy(mode = SettingsUiPreferences.MODE_DARK, palette = SettingsUiPreferences.PALETTE_WISTERIA),
                base.copy(mode = SettingsUiPreferences.MODE_DARK, pureBlack = true),
                base.copy(contrast = SettingsUiPreferences.CONTRAST_HIGH),
                base.copy(followKeyboardTheme = true),
                base.copy(mode = SettingsUiPreferences.MODE_DARK, followKeyboardTheme = true))
            choices.forEach { selection ->
                SettingsUiPreferences.save(appContext, selection)
                val context = SettingsUiPreferences.themedContext(appContext, selection)
                fun color(attr: Int) = context.settingsColor(attr)
                val colors = ClipboardColors.panel(context)
                val ui = ClipboardUi(context, keyboard, searchMode = true)
                val buttons = children(ui.categoryBar).filterIsInstance<Chip>()
                assertEquals(ClipboardCategory.values().size, buttons.size)
                ClipboardCategory.values().forEach { category ->
                    ui.setSelectedCategory(category)
                    buttons.forEach { chip ->
                        val selected = chip.text.toString() == context.getString(category.titleRes)
                        assertEquals(selected, chip.isChecked)
                        assertEquals(selected, chip.isSelected)
                        assertEquals(color(if (selected) androidx.appcompat.R.attr.colorPrimary else
                            com.google.android.material.R.attr.colorSurfaceContainerHighest), chip.chipBackgroundColor!!.defaultColor)
                        assertEquals(color(if (selected) com.google.android.material.R.attr.colorOnPrimary else
                            com.google.android.material.R.attr.colorOnSurface), chip.currentTextColor)
                        assertEquals(if (selected) ColorUtils.setAlphaComponent(colors.onPrimary, 0x30) else colors.ripple,
                            chip.rippleColor!!.defaultColor)
                        assertEquals(color(com.google.android.material.R.attr.colorOutlineVariant), chip.chipStrokeColor!!.defaultColor)
                    }
                }
                val entry = ClipboardEntryUi(context, keyboard, 12f, colors)
                assertEquals(color(com.google.android.material.R.attr.colorSurfaceBright), (entry.root.background as GradientDrawable).color!!.defaultColor)
                assertEquals(color(com.google.android.material.R.attr.colorOnSurface), entry.textView.currentTextColor)
                assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceVariant), entry.pin.imageTintList!!.defaultColor)
                assertEquals(color(com.google.android.material.R.attr.colorSurfaceBright), (entry.preview.background as GradientDrawable).color!!.defaultColor)
                val placeholder = ReflectionHelpers.getField<ImageView>(entry, "imagePlaceholder")
                assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceVariant), placeholder.imageTintList!!.defaultColor)
                assertEquals(color(com.google.android.material.R.attr.colorSurfaceBright), (placeholder.background as GradientDrawable).color!!.defaultColor)
                val rippleState = ReflectionHelpers.getField<Any>(entry.root.foreground, "mState")
                assertEquals(colors.ripple, ReflectionHelpers.getField<ColorStateList>(rippleState, "mColor").defaultColor)
                assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceVariant), ReflectionHelpers.getField<TextView>(ui, "noResults").currentTextColor)
                assertEquals(color(com.google.android.material.R.attr.colorSurfaceInverse), colors.snackbar)
                assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceInverse), colors.onSnackbar)
                assertEquals(color(com.google.android.material.R.attr.colorPrimaryInverse), colors.snackbarAction)
                assertEquals(color(com.google.android.material.R.attr.colorOnSurface), context.settingsColor(android.R.attr.textColorPrimary))
                assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceVariant), context.settingsColor(android.R.attr.colorControlNormal))
                assertEquals(color(com.google.android.material.R.attr.colorSurfaceContainerHigh), context.settingsColor(android.R.attr.colorBackgroundFloating))
                assertTrue("Selected group needs legible text: $selection", ColorUtils.calculateContrast(colors.onPrimary, colors.primary) >= 4.5)
                assertTrue("Card needs legible text: $selection", ColorUtils.calculateContrast(colors.text, colors.card) >= 4.5)
                assertTrue("Undo needs legible text: $selection", ColorUtils.calculateContrast(colors.onSnackbar, colors.snackbar) >= 4.5)
                if (!selection.followKeyboardTheme) assertNotEquals(keyboard.clipboardEntryColor, colors.card)
            }
        } finally {
            SettingsUiPreferences.save(appContext, originalSelection)
            ReflectionHelpers.setStaticField(ThemeManager::class.java, "_activeTheme", originalTheme)
        }
    }

    @Test fun completeGroupsWrapInsideTheAvailableWidthWithLargeFontsAndRtl() {
        for (width in listOf(280, 320, 393, 600)) for (scale in listOf(1f, 1.6f, 2f)) for (rtl in listOf(false, true)) {
            val configuration = Configuration(appContext.resources.configuration).apply { fontScale = scale }
            val context = SettingsUiPreferences.themedContext(appContext.createConfigurationContext(configuration), SettingsUiPreferences.read(appContext))
            val ui = ClipboardUi(context, keyboard, searchMode = true)
            ui.setSelectedCategory(ClipboardCategory.Remote)
            val groups = ui.categoryBar
            groups.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val available = width - 32 // Panel padding plus the rounded-corner inset.
            groups.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            groups.layout(0, 0, groups.measuredWidth, groups.measuredHeight)
            val chips = children(groups).filterIsInstance<Chip>()
            chips.forEach { chip ->
                val case = "width=$width scale=$scale rtl=$rtl ${chip.text}"
                assertTrue("Left boundary: $case", chip.left >= groups.paddingLeft)
                assertTrue("Right boundary: $case", chip.right <= available - groups.paddingRight)
                assertTrue("Bottom boundary: $case", chip.bottom <= groups.height - groups.paddingBottom)
                assertTrue("Touch height: $case", chip.height >= 48)
                assertEquals("Complete label: $case", chip.text.toString(), chip.layout.text.toString())
                assertEquals("No truncated label: $case", 0, chip.layout.getEllipsisCount(0))
            }
            if (width == 600 && scale == 1f) assertEquals("Compact at normal font when all groups fit", 1, chips.map { it.top }.distinct().size)
            if (width == 280) assertTrue("Narrow screen must wrap whole buttons", chips.map { it.top }.distinct().size > 1)
        }
    }

    @Test fun ordinaryKeyboardClipboardKeepsItsOwnThemeColorsAndCompactGroupHeight() {
        val colors = ClipboardColors.keyboard(keyboard)
        val ui = ClipboardUi(appContext, keyboard)
        ui.setSelectedCategory(ClipboardCategory.Favorites)
        ui.categoryBar.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        children(ui.categoryBar).filterIsInstance<TextView>().forEach {
            assertEquals("The keyboard page retains its original 30 dp groups", 30, it.measuredHeight)
        }
        val selected = children(ui.categoryBar).filterIsInstance<TextView>().single { it.isSelected }
        assertEquals(keyboard.accentKeyTextColor, selected.currentTextColor)
        assertEquals(keyboard.accentKeyBackgroundColor, ((selected.background as RippleDrawable).getDrawable(0) as GradientDrawable).color!!.defaultColor)
        val entry = ClipboardEntryUi(appContext, keyboard, 12f)
        assertEquals(keyboard.clipboardEntryColor, (entry.root.background as GradientDrawable).color!!.defaultColor)
        assertEquals(keyboard.keyTextColor, entry.textView.currentTextColor)
        assertEquals(keyboard.popupBackgroundColor, colors.snackbar)
        assertEquals(keyboard.popupTextColor, colors.onSnackbar)
    }

    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
}
