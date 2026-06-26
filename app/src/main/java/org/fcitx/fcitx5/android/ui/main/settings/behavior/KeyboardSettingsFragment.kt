/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor.ImeWebEditorBridgeServer
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class KeyboardSettingsFragment : PaddingPreferenceFragment() {

    private var editorsPref: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.keyboard_category_layout) {
                navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_LAYOUT))
            }
            addPreference(R.string.keyboard_category_behavior) {
                navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_BEHAVIOR))
            }
            addPreference(R.string.keyboard_category_feedback) {
                navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_FEEDBACK))
            }
            addPreference(R.string.keyboard_category_toolbar) {
                navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_TOOLBAR))
            }
            val p = Preference(context).apply {
                key = "editors_category"
                isSingleLineTitle = false
                isIconSpaceReserved = false
                setTitle(R.string.keyboard_category_editors)
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_EDITORS))
                    true
                }
            }
            editorsPref = p
            addPreference(p)
        }
    }

    override fun onResume() {
        super.onResume()
        val session = ImeWebEditorBridgeServer.currentSession()
        val pref = editorsPref ?: findPreference<Preference>("editors_category")
        pref?.summary = if (session != null) {
            getString(R.string.web_editor_bridge_running_indicator)
        } else null
    }
}
