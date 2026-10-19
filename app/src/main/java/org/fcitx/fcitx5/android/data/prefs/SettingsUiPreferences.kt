/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import org.fcitx.fcitx5.android.R

/** Preferences for the app's settings shell, kept independent from keyboard ThemeManager data. */
object SettingsUiPreferences {
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    const val PALETTE_COMPAT = "compat"
    const val PALETTE_SYSTEM = "system"
    const val PALETTE_SEA_SALT = "sea_salt"
    const val PALETTE_PINE = "pine"
    const val PALETTE_BERRY = "berry"
    const val PALETTE_AMBER = "amber"
    const val PALETTE_WISTERIA = "wisteria"
    const val PALETTE_GRAPHITE = "graphite"

    const val RECIPE_CMF_2026 = "cmf2026"
    const val RECIPE_TONAL_2025 = "tonal2025"
    const val RECIPE_VIBRANT_2025 = "vibrant2025"
    const val RECIPE_NEUTRAL_2025 = "neutral2025"
    const val RECIPE_EXPRESSIVE_2025 = "expressive2025"
    const val RECIPE_CLASSIC_2021 = "classic2021"

    const val CONTRAST_STANDARD = "standard"
    const val CONTRAST_MEDIUM = "medium"
    const val CONTRAST_HIGH = "high"

    private const val KEY_INITIALIZED = "settings_ui_initialized"
    private const val KEY_MODE = "settings_ui_mode"
    private const val KEY_PALETTE = "settings_ui_palette"
    private const val KEY_RECIPE = "settings_ui_recipe"
    private const val KEY_CONTRAST = "settings_ui_contrast"

    data class Selection(
        val mode: String = MODE_SYSTEM,
        val palette: String = PALETTE_COMPAT,
        val recipe: String = RECIPE_CMF_2026,
        val contrast: String = CONTRAST_STANDARD
    )

    /**
     * Existing installations retain the app's purple color intent. New installations use
     * Android Monet when it exists, and fall back to the generated compatibility palette.
     */
    fun initialize(context: Context, hadInitializedSettings: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(KEY_INITIALIZED, false)) return
        prefs.edit {
            putString(KEY_MODE, MODE_SYSTEM)
            putString(
                KEY_PALETTE,
                if (!hadInitializedSettings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PALETTE_SYSTEM
                } else {
                    PALETTE_COMPAT
                }
            )
            putString(KEY_RECIPE, RECIPE_CMF_2026)
            putString(KEY_CONTRAST, CONTRAST_STANDARD)
            putBoolean(KEY_INITIALIZED, true)
        }
    }

    fun read(context: Context): Selection {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return normalize(Selection(
            mode = prefs.getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM,
            palette = prefs.getString(KEY_PALETTE, PALETTE_COMPAT) ?: PALETTE_COMPAT,
            recipe = prefs.getString(KEY_RECIPE, RECIPE_CMF_2026) ?: RECIPE_CMF_2026,
            contrast = prefs.getString(KEY_CONTRAST, CONTRAST_STANDARD) ?: CONTRAST_STANDARD
        ))
    }

    private fun normalize(selection: Selection): Selection = selection.copy(
        mode = selection.mode.takeIf { it in setOf(MODE_SYSTEM, MODE_LIGHT, MODE_DARK) } ?: MODE_SYSTEM,
        palette = selection.palette.takeIf { it in setOf(
            PALETTE_SYSTEM, PALETTE_COMPAT, PALETTE_SEA_SALT, PALETTE_PINE,
            PALETTE_BERRY, PALETTE_AMBER, PALETTE_WISTERIA, PALETTE_GRAPHITE
        ) } ?: PALETTE_COMPAT,
        recipe = selection.recipe.takeIf { it in setOf(
            RECIPE_CMF_2026, RECIPE_TONAL_2025, RECIPE_VIBRANT_2025,
            RECIPE_NEUTRAL_2025, RECIPE_EXPRESSIVE_2025, RECIPE_CLASSIC_2021
        ) } ?: RECIPE_CMF_2026,
        contrast = selection.contrast.takeIf {
            it in setOf(CONTRAST_STANDARD, CONTRAST_MEDIUM, CONTRAST_HIGH)
        } ?: CONTRAST_STANDARD
    )

    fun save(context: Context, selection: Selection) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_MODE, selection.mode)
            putString(KEY_PALETTE, selection.palette)
            putString(KEY_RECIPE, selection.recipe)
            putString(KEY_CONTRAST, selection.contrast)
        }
    }

    fun usesSystemDynamicColors(context: Context, selection: Selection = read(context)): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selection.palette == PALETTE_SYSTEM &&
            DynamicColors.isDynamicColorAvailable()

    fun isDark(context: Context, selection: Selection = read(context)): Boolean = when (selection.mode) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun themeResource(context: Context, selection: Selection = read(context)): Int {
        if (usesSystemDynamicColors(context, selection)) {
            return when (selection.mode) {
                MODE_LIGHT -> R.style.Theme_FcitxSettings_System_Light
                MODE_DARK -> R.style.Theme_FcitxSettings_System_Dark
                else -> R.style.Theme_FcitxSettings_System
            }
        }

        val family = if (selection.palette == PALETTE_SYSTEM) PALETTE_COMPAT else selection.palette
        val recipe = if (family == PALETTE_COMPAT) "compat" else selection.recipe
        val dark = if (selection.mode == MODE_SYSTEM) isDark(context, selection)
        else selection.mode == MODE_DARK
        val mode = if (dark) "dark" else "light"
        val styleName = "Theme.FcitxSettings.$family.$recipe.${selection.contrast}.$mode"
        return context.resources.getIdentifier(styleName, "style", context.packageName)
            .takeIf { it != 0 } ?: if (dark) {
                R.style.Theme_FcitxSettings_compat_compat_standard_dark
            } else R.style.Theme_FcitxSettings_compat_compat_standard_light
    }

    /** Inflate the preview with the exact theme used by settings activities, including OEM Monet. */
    fun themedContext(context: Context, selection: Selection): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark(context, selection)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), themeResource(context, selection))
        return if (usesSystemDynamicColors(context, selection)) DynamicColors.wrapContextIfAvailable(themed) else themed
    }
}
