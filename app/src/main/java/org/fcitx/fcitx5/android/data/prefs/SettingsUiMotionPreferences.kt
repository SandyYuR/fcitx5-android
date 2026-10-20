/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** Motion belongs to the settings shell, independently of keyboard animation preferences. */
object SettingsUiMotionPreferences {
    const val KEY_ENTER = "settings_ui_motion_enter"
    const val KEY_RETURN = "settings_ui_motion_return"
    const val KEY_DURATION = "settings_ui_motion_duration"
    const val KEY_PREDICTIVE = "settings_ui_motion_predictive"

    enum class Style(val value: String) {
        FOLLOW("follow"), HORIZONTAL("horizontal"), VERTICAL("vertical"),
        SCALE("scale"), FADE("fade"), NONE("none");

        companion object {
            fun fromValue(value: String?, fallback: Style): Style =
                entries.firstOrNull { it.value == value } ?: fallback
        }
    }

    data class Selection(
        val enter: Style = Style.HORIZONTAL,
        val returning: Style = Style.FOLLOW,
        val durationMillis: Long = 300L,
        val predictiveBack: Boolean = true
    ) {
        val returnStyle: Style get() = if (returning == Style.FOLLOW) enter else returning
        val hasAnimation: Boolean get() = enter != Style.NONE || returnStyle != Style.NONE
    }

    fun read(context: Context): Selection {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enter = Style.fromValue(prefs.getString(KEY_ENTER, null), Style.HORIZONTAL)
            .takeUnless { it == Style.FOLLOW } ?: Style.HORIZONTAL
        return Selection(
            enter = enter,
            returning = Style.fromValue(prefs.getString(KEY_RETURN, null), Style.FOLLOW),
            durationMillis = prefs.getString(KEY_DURATION, null)?.toLongOrNull()
                ?.takeIf { it in setOf(180L, 300L, 450L) } ?: 300L,
            predictiveBack = prefs.getBoolean(KEY_PREDICTIVE, true)
        )
    }

    fun save(context: Context, selection: Selection) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_ENTER, selection.enter.value)
            putString(KEY_RETURN, selection.returning.value)
            putString(KEY_DURATION, selection.durationMillis.toString())
            putBoolean(KEY_PREDICTIVE, selection.predictiveBack)
        }
    }
}
