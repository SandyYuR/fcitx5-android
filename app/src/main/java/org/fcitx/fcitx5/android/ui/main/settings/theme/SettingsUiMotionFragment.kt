/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences.Selection
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences.Style
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.applySettingsPageMotion
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference

class SettingsUiMotionFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var selection = Selection()
    private lateinit var enter: ListPreference
    private lateinit var returning: ListPreference
    private lateinit var duration: ListPreference
    private lateinit var predictive: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        selection = SettingsUiMotionPreferences.read(requireContext())
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        category(R.string.settings_ui_motion_pages)
        val styles = listOf(
            Style.HORIZONTAL to R.string.settings_ui_motion_horizontal,
            Style.VERTICAL to R.string.settings_ui_motion_vertical,
            Style.SCALE to R.string.settings_ui_motion_scale,
            Style.FADE to R.string.settings_ui_motion_fade,
            Style.NONE to R.string.settings_ui_motion_none
        )
        enter = stylePreference(SettingsUiMotionPreferences.KEY_ENTER,
            R.string.settings_ui_motion_enter, styles) { update(selection.copy(enter = it)) }
        returning = stylePreference(SettingsUiMotionPreferences.KEY_RETURN,
            R.string.settings_ui_motion_return,
            listOf(Style.FOLLOW to R.string.settings_ui_motion_follow) + styles
        ) { update(selection.copy(returning = it)) }
        duration = ListPreference(requireContext()).apply {
            key = SettingsUiMotionPreferences.KEY_DURATION
            setTitle(R.string.settings_ui_motion_duration)
            entries = arrayOf(getString(R.string.settings_ui_motion_fast),
                getString(R.string.settings_ui_motion_standard), getString(R.string.settings_ui_motion_slow))
            entryValues = arrayOf("180", "300", "450")
            isPersistent = false
            isIconSpaceReserved = false
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                update(selection.copy(durationMillis = value.toString().toLong()))
                true
            }
        }.also { preferenceScreen.addPreference(it) }

        category(R.string.settings_ui_motion_gestures)
        predictive = SwitchPreferenceCompat(requireContext()).apply {
            key = SettingsUiMotionPreferences.KEY_PREDICTIVE
            setTitle(R.string.settings_ui_motion_predictive)
            setSummary(if (Build.VERSION.SDK_INT >= 34) R.string.settings_ui_motion_predictive_summary
                else R.string.settings_ui_motion_predictive_unavailable)
            isPersistent = false
            isIconSpaceReserved = false
            isEnabled = Build.VERSION.SDK_INT >= 34
            setOnPreferenceChangeListener { _, value ->
                update(selection.copy(predictiveBack = value as Boolean), recreate = true)
                true
            }
        }.also { preferenceScreen.addPreference(it) }
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setSummary(R.string.settings_ui_motion_info)
            isSelectable = false
            isIconSpaceReserved = false
        })
        category(R.string.settings_ui_motion_reset_category)
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.settings_ui_motion_reset)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val recreate = !selection.predictiveBack
                update(Selection(), recreate)
                true
            }
        })
        render()
    }

    private fun category(title: Int) {
        preferenceScreen.addPreference(PreferenceCategory(requireContext()).apply { setTitle(title) })
    }

    private fun stylePreference(
        key: String, title: Int, options: List<Pair<Style, Int>>, changed: (Style) -> Unit
    ) = ListPreference(requireContext()).apply {
        this.key = key
        setTitle(title)
        entries = options.map { getString(it.second) }.toTypedArray()
        entryValues = options.map { it.first.value }.toTypedArray()
        isPersistent = false
        isIconSpaceReserved = false
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, value ->
            changed(Style.fromValue(value.toString(), Style.HORIZONTAL))
            true
        }
    }.also { preferenceScreen.addPreference(it) }

    private fun update(value: Selection, recreate: Boolean = false) {
        selection = value
        SettingsUiMotionPreferences.save(requireContext(), value)
        if (view != null) applySettingsPageMotion(value)
        render()
        if (recreate) requireActivity().recreate()
    }

    private fun render() {
        enter.value = selection.enter.value
        returning.value = selection.returning.value
        duration.value = selection.durationMillis.toString()
        duration.isEnabled = selection.hasAnimation
        predictive.isEnabled = Build.VERSION.SDK_INT >= 34 && selection.returnStyle != Style.NONE
        predictive.setSummary(when {
            Build.VERSION.SDK_INT < 34 -> R.string.settings_ui_motion_predictive_unavailable
            selection.returnStyle == Style.NONE -> R.string.settings_ui_motion_predictive_no_animation
            else -> R.string.settings_ui_motion_predictive_summary
        })
        predictive.isChecked = Build.VERSION.SDK_INT >= 34 && selection.predictiveBack
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollToPendingPreference(viewModel)
    }
}
