/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.app.backup.BackupAgentHelper
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.io.IOException

/** Platform backup contains only this settings-shell snapshot, never the main prefs file. */
class UiSettingsBackupAgent : BackupAgentHelper() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        UiSettingsBackup.copy(PreferenceManager.getDefaultSharedPreferences(this),
            getSharedPreferences(UiSettingsBackup.FILE, Context.MODE_PRIVATE), clear = true)
        super.onFullBackup(data)
    }

    override fun onRestoreFinished() {
        UiSettingsBackup.copy(getSharedPreferences(UiSettingsBackup.FILE, Context.MODE_PRIVATE),
            PreferenceManager.getDefaultSharedPreferences(this), clear = false)
        super.onRestoreFinished()
    }
}

internal object UiSettingsBackup {
    const val FILE = "system_backup_ui"
    private val strings = setOf("settings_ui_mode", "settings_ui_palette", "settings_ui_recipe",
        "settings_ui_contrast", "settings_ui_manual_palette")
    private val booleans = setOf("settings_ui_initialized", "settings_ui_pure_black", "settings_ui_follow_keyboard_theme")

    fun copy(source: SharedPreferences, target: SharedPreferences, clear: Boolean) {
        val editor = target.edit()
        if (clear) editor.clear()
        for ((key, value) in source.all) when {
            key in strings && value is String && value.length <= 64 -> editor.putString(key, value)
            key in booleans && value is Boolean -> editor.putBoolean(key, value)
        }
        if (!editor.commit()) throw IOException("Unable to persist system UI backup settings.")
    }
}
