/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference

/**
 * 分体、浮动与单手键盘。
 *
 * 本页集中呈现三种"键盘形态"：
 * - **分体键盘**（`AppPrefs.keyboard` 下的 `split_keyboard_enabled` /
 *   `split_keyboard_use_landscape_layout` 两个开关，加一个校准 Activity 入口）。
 *   这几项原先挂在「键盘尺寸」分组里，2026 年归并到本页——分体同样是一种整体形态，
 *   与浮动/单手同族，放在尺寸几何里并不自解释。
 * - **浮动键盘**：启用开关，外加「横屏时自动使用浮动键盘」。
 * - **单手键盘**：启用开关与「靠右显示」。
 *
 * 浮动/单手的状态存在 `AppPrefs.internal` 里，原本**没有任何设置入口**，只能靠工具栏/
 * 状态区按钮切换。这里直接按偏好键建 `SwitchPreference`——它们落盘的是同一份
 * SharedPreferences，`key` 一致即可，与偏好注册在 `internal` 还是 `keyboard` 无关。
 * 分体开关改动经全局 `OnSharedPreferenceChangeListener` 仍会驱动 `InputView` 刷新
 * （`keyboard` 分组注册了这些键），本页只是换了个入口，不触碰刷新链路。
 */
class KeyboardModesFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var splitSwitch: Preference? = null
    private var splitLandscapeSwitch: Preference? = null
    private var splitDuplicateSwitch: Preference? = null
    private var calibrationPref: Preference? = null

    private var floatingSwitch: Preference? = null
    private var autoFloatingSwitch: Preference? = null

    private var oneHandSwitch: Preference? = null
    private var oneHandRightSwitch: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val internal = AppPrefs.getInstance().internal
        val keyboard = AppPrefs.getInstance().keyboard
        val screen = preferenceManager.createPreferenceScreen(ctx)

        // ===== 分体键盘 =====
        val splitCategory = category(R.string.keyboard_modes_category_split)
        screen.addPreference(splitCategory)

        val split = MySwitchPreference(ctx).apply {
            key = keyboard.splitKeyboardEnabled.key
            setTitle(R.string.split_keyboard_enabled)
            setDefaultValue(true)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            // 用 onPreferenceChange 读到的是**新值**，可据此联动下属项的可用性。
            setOnPreferenceChangeListener { _, newValue ->
                syncSplitOptions(newValue == true)
                true
            }
        }
        val splitLandscape = MySwitchPreference(ctx).apply {
            key = keyboard.splitKeyboardUseLandscapeLayout.key
            setTitle(R.string.split_keyboard_use_landscape_layout)
            setDefaultValue(false)
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        val splitDuplicate = MySwitchPreference(ctx).apply {
            key = keyboard.splitKeyboardDuplicateMiddleKey.key
            setTitle(R.string.split_keyboard_duplicate_middle)
            setSummary(R.string.split_keyboard_duplicate_middle_summary)
            setDefaultValue(true)
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        val calibration = Preference(ctx).apply {
            setTitle(R.string.split_keyboard_calibration_title)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SplitKeyboardCalibrationActivity::class.java))
                true
            }
        }
        splitSwitch = split
        splitLandscapeSwitch = splitLandscape
        splitDuplicateSwitch = splitDuplicate
        calibrationPref = calibration
        splitCategory.addPreference(split)
        splitCategory.addPreference(splitLandscape)
        splitCategory.addPreference(splitDuplicate)
        splitCategory.addPreference(calibration)

        // ===== 浮动键盘 =====
        val floatingCategory = category(R.string.keyboard_modes_category_floating)
        screen.addPreference(floatingCategory)

        // 浮动与单手是互斥的两种形态，同时开启没有意义，故打开其一时关掉另一个。
        val floating = MySwitchPreference(ctx).apply {
            key = internal.floatingModeEnabled.key
            setTitle(R.string.floating_keyboard_enabled)
            setSummary(R.string.floating_keyboard_enabled_summary)
            setDefaultValue(false)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) oneHandSwitch?.let { (it as MySwitchPreference).isChecked = false }
                syncOneHandOptions()
                true
            }
        }
        val autoFloating = MySwitchPreference(ctx).apply {
            key = internal.autoFloatingLandscape.key
            setTitle(R.string.auto_floating_landscape)
            setSummary(R.string.auto_floating_landscape_summary)
            setDefaultValue(false)
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        floatingSwitch = floating
        autoFloatingSwitch = autoFloating
        floatingCategory.addPreference(floating)
        floatingCategory.addPreference(autoFloating)

        // ===== 单手键盘 =====
        val oneHandCategory = category(R.string.keyboard_modes_category_one_hand)
        screen.addPreference(oneHandCategory)

        val oneHand = MySwitchPreference(ctx).apply {
            key = internal.oneHandModeEnabled.key
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
        val oneHandRight = MySwitchPreference(ctx).apply {
            key = internal.oneHandOnRightPortrait.key
            setTitle(R.string.one_hand_keyboard_on_right)
            setSummary(R.string.one_hand_keyboard_on_right_summary)
            setDefaultValue(true)
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        oneHandSwitch = oneHand
        oneHandRightSwitch = oneHandRight
        oneHandCategory.addPreference(oneHand)
        oneHandCategory.addPreference(oneHandRight)

        preferenceScreen = screen
    }

    private fun category(titleRes: Int) = PreferenceCategory(requireContext()).apply {
        setTitle(titleRes)
        isIconSpaceReserved = false
        isSingleLineTitle = false
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

    /** 把各开关的勾选状态与底层偏好对齐（工具栏按钮也能改浮动/单手）。 */
    private fun syncSwitchStates() {
        val internal = AppPrefs.getInstance().internal
        val keyboard = AppPrefs.getInstance().keyboard
        (splitSwitch as? MySwitchPreference)?.isChecked = keyboard.splitKeyboardEnabled.getValue()
        (floatingSwitch as? MySwitchPreference)?.isChecked = internal.floatingModeEnabled.getValue()
        (oneHandSwitch as? MySwitchPreference)?.isChecked = internal.oneHandModeEnabled.getValue()
        syncSplitOptions(keyboard.splitKeyboardEnabled.getValue())
        syncOneHandOptions()
    }

    /** 「分体时采用横屏布局」「中间键两侧各一枚」与「校准」只在分体开启时才有意义。 */
    private fun syncSplitOptions(enabled: Boolean) {
        splitLandscapeSwitch?.isEnabled = enabled
        splitDuplicateSwitch?.isEnabled = enabled
        calibrationPref?.isEnabled = enabled
    }

    /** 「靠右显示」只在单手键盘开启时才有意义。 */
    private fun syncOneHandOptions() {
        val oneHandEnabled = AppPrefs.getInstance().internal.oneHandModeEnabled.getValue()
        oneHandRightSwitch?.isEnabled = oneHandEnabled
    }
}
