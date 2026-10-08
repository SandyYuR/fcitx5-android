/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.utils.DeviceUtil
import timber.log.Timber

object AppPrefsMigration {

    private const val MIGRATION_VERSION_KEY = "app_prefs_migration_version"
    private const val KEYPRESS_WORKAROUND_MIGRATION_VERSION = 2

    fun apply(sharedPreferences: SharedPreferences) {
        if (sharedPreferences.getInt(MIGRATION_VERSION_KEY, 0) >=
            KEYPRESS_WORKAROUND_MIGRATION_VERSION
        ) {
            return
        }

        val enabled = DeviceUtil.isVivoOriginOS || DeviceUtil.isMIUI
        sharedPreferences.edit(commit = true) {
            putBoolean("vivo_keypress_workaround", enabled)
            putInt(MIGRATION_VERSION_KEY, KEYPRESS_WORKAROUND_MIGRATION_VERSION)
        }
        Timber.i("Migrated keypress workaround: enabled=$enabled")
    }
}
