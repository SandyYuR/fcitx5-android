/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/** Android input/window compatibility preferences; Rime is configured from its own page. */
class AdvancedSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().advanced) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(R.string.developer, icon = R.drawable.ic_baseline_code_24) {
            navigateWithAnim(SettingsRoute.Developer)
        }
    }
}
