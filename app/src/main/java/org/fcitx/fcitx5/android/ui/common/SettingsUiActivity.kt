/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences

/** Base for app-owned settings shells; IME windows and transient overlay activities do not use it. */
abstract class SettingsUiActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(SettingsUiPreferences.themeResource(this))
        super.onCreate(savedInstanceState)
        if (SettingsUiPreferences.usesSystemDynamicColors(this)) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
    }
}
