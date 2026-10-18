/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceManager
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
        return Selection(
            mode = prefs.getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM,
            palette = prefs.getString(KEY_PALETTE, PALETTE_COMPAT) ?: PALETTE_COMPAT,
            recipe = prefs.getString(KEY_RECIPE, RECIPE_CMF_2026) ?: RECIPE_CMF_2026,
            contrast = prefs.getString(KEY_CONTRAST, CONTRAST_STANDARD) ?: CONTRAST_STANDARD
        )
    }

    fun save(context: Context, selection: Selection) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_MODE, selection.mode)
            putString(KEY_PALETTE, selection.palette)
            putString(KEY_RECIPE, selection.recipe)
            putString(KEY_CONTRAST, selection.contrast)
        }
    }

    fun usesSystemDynamicColors(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && read(context).palette == PALETTE_SYSTEM

    fun isDark(context: Context, selection: Selection = read(context)): Boolean = when (selection.mode) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun themeResource(context: Context): Int {
        val selection = read(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selection.palette == PALETTE_SYSTEM) {
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
            .takeIf { it != 0 } ?: R.style.Theme_FcitxAppTheme
    }

    fun color(context: Context, selection: Selection, role: String): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selection.palette == PALETTE_SYSTEM) {
            val dark = isDark(context, selection)
            val colorName = when (role) {
                "primary" -> if (dark) "system_accent1_200" else "system_accent1_600"
                "on_primary" -> if (dark) "system_accent1_800" else "system_accent1_0"
                "primary_container" -> if (dark) "system_accent1_800" else "system_accent1_100"
                "secondary" -> if (dark) "system_accent2_200" else "system_accent2_600"
                "on_secondary" -> if (dark) "system_accent2_800" else "system_accent2_0"
                "tertiary" -> if (dark) "system_accent3_200" else "system_accent3_600"
                "on_tertiary" -> if (dark) "system_accent3_800" else "system_accent3_0"
                "surface" -> if (dark) "system_neutral1_900" else "system_neutral1_10"
                "on_surface" -> if (dark) "system_neutral1_100" else "system_neutral1_900"
                else -> null
            }
            val id = colorName?.let { context.resources.getIdentifier(it, "color", "android") } ?: 0
            if (id != 0) return context.getColor(id)
        }
        val family = if (selection.palette == PALETTE_SYSTEM) PALETTE_COMPAT else selection.palette
        val recipe = if (family == PALETTE_COMPAT) "compat" else selection.recipe
        val contrast = if (family == PALETTE_COMPAT) selection.contrast else selection.contrast
        val mode = if (isDark(context, selection)) "dark" else "light"
        val resourceName = "settings_ui_${family}_${recipe}_${contrast}_${mode}_$role"
        val id = context.resources.getIdentifier(resourceName, "color", context.packageName)
        return if (id != 0) context.getColor(id) else context.getColor(android.R.color.white)
    }
}
