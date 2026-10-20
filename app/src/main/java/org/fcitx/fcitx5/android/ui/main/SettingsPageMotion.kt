/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import androidx.fragment.app.Fragment
import androidx.transition.Transition
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences.Style

/** All transitions target the page's transition group, keeping title and content together. */
internal fun Fragment.applySettingsPageMotion(
    selection: SettingsUiMotionPreferences.Selection = SettingsUiMotionPreferences.read(requireContext())
) {
    fun transition(style: Style, forward: Boolean): Transition? {
        val result: Transition? = when (style) {
            Style.HORIZONTAL -> MaterialSharedAxis(MaterialSharedAxis.X, forward)
            Style.VERTICAL -> MaterialSharedAxis(MaterialSharedAxis.Y, forward)
            Style.SCALE -> MaterialSharedAxis(MaterialSharedAxis.Z, forward)
            Style.FADE -> MaterialFadeThrough()
            Style.NONE -> null
            Style.FOLLOW -> error("Resolve the return style before creating a transition")
        }
        return result?.apply { duration = selection.durationMillis }
    }

    enterTransition = transition(selection.enter, true)
    exitTransition = transition(selection.enter, true)
    reenterTransition = transition(selection.returnStyle, false)
    returnTransition = transition(selection.returnStyle, false)
}
