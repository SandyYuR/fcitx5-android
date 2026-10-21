/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.fcitx.fcitx5.android.R

/** Saved shortcuts can still contain old engine routes; all open the single Rime settings page. */
class LegacyEngineSettingsFragment : Fragment() {
    override fun onStart() {
        super.onStart()
        val controller = findNavController()
        val legacyDestination = controller.currentDestination?.id ?: return
        controller.navigate(SettingsRoute.InputMethodConfig(getString(R.string.rime_settings), "rime")) {
            popUpTo(legacyDestination) { inclusive = true }
            launchSingleTop = true
        }
    }
}
