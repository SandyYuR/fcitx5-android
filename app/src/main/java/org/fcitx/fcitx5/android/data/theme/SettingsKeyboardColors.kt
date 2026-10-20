/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.MaterialColorUtilitiesHelper
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.TonalPalette
import com.google.android.material.color.utilities.Variant
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences.Selection

/**
 * Adapter to the color engine already shipped in Material Components 1.14.0. Its color utilities
 * and resource loader are library-group APIs; keep this dependency isolated when updating MDC.
 * ResourcesLoader needs Android 11. Older systems retain their existing settings palette.
 */
@SuppressLint("RestrictedApi")
object SettingsKeyboardColors {
    /** Only resolved colors participate: changing a keyboard dimension must not recreate settings. */
    data class Palette(
        val accent: Int,
        val secondary: Int,
        val tertiary: Int,
        val background: Int,
        val keyBackground: Int,
        val dark: Boolean,
        val contrast: Double
    )

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun current(context: Context, selection: Selection): Palette? {
        if (!SettingsUiPreferences.usesKeyboardColors(selection)) return null
        // ThemeManager resolves system day/night, random slots and custom Monet mappings for us.
        val theme = ThemeManager.activeTheme
        val backdrop = ColorUtils.compositeColors(theme.backgroundColor,
            if (theme.isDark) Color.BLACK else Color.WHITE)
        val background = ColorUtils.compositeColors(theme.keyboardColor, backdrop)
        val keyBackground = ColorUtils.compositeColors(theme.keyBackgroundColor, background)
        return Palette(
            accent = ColorUtils.compositeColors(theme.accentKeyBackgroundColor, keyBackground),
            secondary = ColorUtils.compositeColors(theme.altKeyBackgroundColor, keyBackground),
            tertiary = ColorUtils.compositeColors(theme.genericActiveBackgroundColor, keyBackground),
            background = background,
            keyBackground = keyBackground,
            dark = SettingsUiPreferences.isDark(context, selection),
            contrast = when (selection.contrast) {
                SettingsUiPreferences.CONTRAST_MEDIUM -> 0.5
                SettingsUiPreferences.CONTRAST_HIGH -> 1.0
                else -> 0.0
            }
        )
    }

    private fun colorResources(palette: Palette): Map<Int, Int> {
        val accent = Hct.fromInt(palette.accent)
        val secondary = Hct.fromInt(palette.secondary)
        val tertiary = Hct.fromInt(palette.tertiary)
        val background = Hct.fromInt(palette.background)
        val keyBackground = Hct.fromInt(palette.keyBackground)
        // Preserve keyboard hues, with low-chroma surfaces and Material's foreground/contrast rules.
        // TONAL_SPOT keeps containers readable even for white, black or neon keyboard accents.
        val scheme = DynamicScheme(accent, Variant.TONAL_SPOT, palette.dark, palette.contrast,
            TonalPalette.fromHct(accent),
            TonalPalette.fromHueAndChroma(secondary.hue, secondary.chroma.coerceAtMost(24.0)),
            TonalPalette.fromHct(tertiary),
            TonalPalette.fromHueAndChroma(background.hue, background.chroma.coerceAtMost(8.0)),
            TonalPalette.fromHueAndChroma(keyBackground.hue, keyBackground.chroma.coerceAtMost(12.0)))
        val colors = MaterialColorUtilitiesHelper.createColorResourcesIdsToColorValues(scheme).toMutableMap()
        val roles = MaterialDynamicColors()
        // MDC's personalized overlay predates fixed roles. Supply them too, so every component
        // uses the keyboard palette instead of inheriting fixed colors from the base theme.
        colors[R.color.settings_keyboard_primary_fixed] = roles.primaryFixed().getArgb(scheme)
        colors[R.color.settings_keyboard_primary_fixed_dim] = roles.primaryFixedDim().getArgb(scheme)
        colors[R.color.settings_keyboard_on_primary_fixed] = roles.onPrimaryFixed().getArgb(scheme)
        colors[R.color.settings_keyboard_on_primary_fixed_variant] = roles.onPrimaryFixedVariant().getArgb(scheme)
        colors[R.color.settings_keyboard_secondary_fixed] = roles.secondaryFixed().getArgb(scheme)
        colors[R.color.settings_keyboard_secondary_fixed_dim] = roles.secondaryFixedDim().getArgb(scheme)
        colors[R.color.settings_keyboard_on_secondary_fixed] = roles.onSecondaryFixed().getArgb(scheme)
        colors[R.color.settings_keyboard_on_secondary_fixed_variant] = roles.onSecondaryFixedVariant().getArgb(scheme)
        colors[R.color.settings_keyboard_tertiary_fixed] = roles.tertiaryFixed().getArgb(scheme)
        colors[R.color.settings_keyboard_tertiary_fixed_dim] = roles.tertiaryFixedDim().getArgb(scheme)
        colors[R.color.settings_keyboard_on_tertiary_fixed] = roles.onTertiaryFixed().getArgb(scheme)
        colors[R.color.settings_keyboard_on_tertiary_fixed_variant] = roles.onTertiaryFixedVariant().getArgb(scheme)
        return colors
    }

    /** Apply after AppCompat has finished choosing the activity's Resources/night configuration. */
    fun applyToActivity(activity: Activity, palette: Palette) {
        if (!isAvailable()) return
        if (ColorResourcesOverride.getInstance()?.applyIfPossible(activity, colorResources(palette)) == true) {
            applyOverlay(activity)
        }
    }

    /** MDC creates a configuration-scoped Resources instance for each preview. */
    fun wrapContext(context: Context, palette: Palette): Context {
        if (!isAvailable()) return context
        val wrapped = ColorResourcesOverride.getInstance()?.wrapContextIfPossible(context, colorResources(palette)) ?: context
        if (wrapped !== context) applyOverlay(wrapped)
        return wrapped
    }

    private fun applyOverlay(context: Context) {
        context.theme.applyStyle(R.style.ThemeOverlay_FcitxSettings_KeyboardColors, true)
    }
}
