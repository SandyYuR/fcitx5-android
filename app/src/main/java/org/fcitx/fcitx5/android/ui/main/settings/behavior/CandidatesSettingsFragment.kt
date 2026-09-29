/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.utils.toast

/**
 * 候选窗口设置。
 *
 * 顶部加了一项「快速预设」：候选窗口有 7 个纯数值的外观参数，普通用户不该先理解
 * dp/sp 才能调。预设一次性写好这组数值，之后仍可逐项微调——预设是**动作**而非模式，
 * 理由见 [CandidatesPresets] 的说明。
 */
class CandidatesSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().candidates) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val preset = Preference(requireContext()).apply {
            key = KEY_PRESET
            isPersistent = false
            isSingleLineTitle = false
            isIconSpaceReserved = false
            setTitle(R.string.candidates_preset_title)
            setSummary(R.string.candidates_preset_summary)
            setOnPreferenceClickListener {
                showPresetDialog()
                true
            }
        }

        // 放到首位：它作用于下面所有外观项，排在它们之前更符合阅读顺序。
        // `PreferenceGroup` 只有「追加」没有「按索引插入」，所以先取出原有项、
        // 清空、再按「预设 + 原顺序」放回。
        val existing = (0 until screen.preferenceCount).map { screen.getPreference(it) }
        screen.removeAll()
        screen.addPreference(preset)
        existing.forEach { screen.addPreference(it) }
    }

    private fun showPresetDialog() {
        val labels = CandidatesPresets.all.map { getString(it.titleRes) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.candidates_preset_title)
            .setItems(labels) { _, which ->
                val preset = CandidatesPresets.all.getOrNull(which) ?: return@setItems
                applyPreset(preset)
                requireContext().toast(
                    getString(R.string.candidates_preset_applied, getString(preset.titleRes))
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyPreset(preset: CandidatesPresets.Preset) {
        val candidates = AppPrefs.getInstance().candidates
        candidates.windowPadding.setValue(preset.windowPadding)
        candidates.fontSize.setValue(preset.fontSize)
        candidates.windowRadius.setValue(preset.windowRadius)
        candidates.candidateHighlightRadius.setValue(preset.highlightRadius)
        candidates.itemPaddingVertical.setValue(preset.itemPaddingVertical)
        candidates.itemPaddingHorizontal.setValue(preset.itemPaddingHorizontal)

        // 重建 PreferenceScreen 让各行显示新值。
        //
        // 为什么不逐项 `notifyChanged()`：它是 `protected`，外部无法调用。
        // 为什么不用 `SeekBarPreference.value = x`：那会**再写一次偏好**，
        // 等于把「刷新显示」和「修改数据」混在一起，将来改预设逻辑容易写出回环。
        // 本页总共 8 项，重建的代价可以忽略，且不会漏掉任何一项。
        onCreatePreferences(null, null)
    }

    private companion object {
        const val KEY_PRESET = "candidates_preset"
    }
}
