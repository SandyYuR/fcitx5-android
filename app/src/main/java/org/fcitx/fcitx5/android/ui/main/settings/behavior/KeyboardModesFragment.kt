/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference

/**
 * 浮动键盘与单手键盘。
 *
 * 这两个功能原本**没有任何设置入口**：状态存在 `AppPrefs.internal` 里，
 * 只能通过工具栏/状态区按钮切换，位置与大小则靠拖拽隐式保存。用户若不碰巧
 * 点到那个图标，永远不知道功能存在——这就是本页存在的理由。
 *
 * 这里直接操作 `internal` 偏好（它们注册在 `ManagedPreferenceInternal` 上，
 * 没有 UI 元数据），所以不用 `ManagedPreferenceFragment`，而是显式建 Preference。
 * 开关的 `key` 与偏好键一致，`SwitchPreference` 落盘的正是同一份 SharedPreferences。
 */
class KeyboardModesFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var floatingSwitch: Preference? = null
    private var oneHandSwitch: Preference? = null
    private var oneHandRightSwitch: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val prefs = AppPrefs.getInstance().internal

        val floating = MySwitchPreference(requireContext()).apply {
            key = prefs.floatingModeEnabled.key
            setTitle(R.string.floating_keyboard_enabled)
            setSummary(R.string.floating_keyboard_enabled_summary)
            setDefaultValue(false)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            // 用 onPreferenceChange 而不是 onPreferenceTreeClick：SwitchPreference
            // 的 onClick 先调 super.onClick()（即先派发 tree click）再切换取值，
            // 在 tree click 里读到的是**旧值**，判断必然出错。
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) oneHandSwitch?.let { (it as MySwitchPreference).isChecked = false }
                syncOneHandOptions()
                true
            }
        }

        // 浮动与单手是互斥的两种形态，同时开启没有意义，故打开其一时关掉另一个。
        val oneHand = MySwitchPreference(requireContext()).apply {
            key = prefs.oneHandModeEnabled.key
            setTitle(R.string.one_hand_keyboard_enabled)
            setSummary(R.string.one_hand_keyboard_enabled_summary)
            setDefaultValue(false)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) floatingSwitch?.let { (it as MySwitchPreference).isChecked = false }
                syncOneHandOptions()
                true
            }
        }

        val oneHandRight = MySwitchPreference(requireContext()).apply {
            key = prefs.oneHandOnRightPortrait.key
            setTitle(R.string.one_hand_keyboard_on_right)
            setSummary(R.string.one_hand_keyboard_on_right_summary)
            setDefaultValue(true)
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }

        floatingSwitch = floating
        oneHandSwitch = oneHand
        oneHandRightSwitch = oneHandRight

        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(floating)
            addPreference(oneHand)
            addPreference(oneHandRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollToPendingPreference(viewModel)
    }

    override fun onResume() {
        super.onResume()
        viewModel.setToolbarTitle(getString(R.string.keyboard_modes_title))
        // 开关可能刚被工具栏按钮改过，每次进入按实际状态同步一次。
        syncSwitchStates()
    }

    /** 把两个开关的勾选状态与底层偏好对齐（工具栏按钮也能改它们）。 */
    private fun syncSwitchStates() {
        val prefs = AppPrefs.getInstance().internal
        (floatingSwitch as? MySwitchPreference)?.isChecked = prefs.floatingModeEnabled.getValue()
        (oneHandSwitch as? MySwitchPreference)?.isChecked = prefs.oneHandModeEnabled.getValue()
        syncOneHandOptions()
    }

    /** 「靠右显示」只在单手键盘开启时才有意义。 */
    private fun syncOneHandOptions() {
        val oneHandEnabled = AppPrefs.getInstance().internal.oneHandModeEnabled.getValue()
        oneHandRightSwitch?.isEnabled = oneHandEnabled
    }
}
