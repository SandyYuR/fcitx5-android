/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference

class ManagedThemePreference(
    sharedPreferences: SharedPreferences,
    key: String,
    defaultValue: Theme,
) : ManagedPreference<Theme>(
    sharedPreferences, key, defaultValue
) {

    override fun setValue(value: Theme) {
        sharedPreferences.edit { putString(key, value.name) }
    }

    override fun getValue(): Theme =
        sharedPreferences.getString(key, null)?.let { name ->
            ThemeManager.getAllThemes().find { it.name == name }
        } ?: defaultValue

    /**
     * 保存的主题名原文。
     *
     * [getValue] 在主题被删除或改名后会回落到默认主题，看不出用户原本选的是哪一个；
     * 主题改名需要把旧名迁到新名上，只能读原文判断。
     */
    fun storedName(): String? = sharedPreferences.getString(key, null)

    override fun putValueTo(editor: SharedPreferences.Editor) {
        // Preserve the selected name while the asynchronously loaded list is not ready yet.
        editor.putString(key, sharedPreferences.getString(key, null) ?: defaultValue.name)
    }

}
