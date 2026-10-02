/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor.ImeWebEditorBridgeServer
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class KeyboardSettingsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var editorsPref: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.keyboard_category_layout) {
                navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_LAYOUT))
            }
            // 「分体、浮动与单手键盘」紧跟「键盘尺寸」：两者都是键盘整体尺寸/形态的调整，
            // 放一起比夹在工具栏之后更符合用户查找直觉。分体/浮动/单手原先要么散落在
            // 「键盘尺寸」里（分体），要么完全没有设置入口（浮动/单手，只能靠工具栏图标发现）。
            addPreference(R.string.keyboard_modes_title, R.string.keyboard_modes_summary) {
                navigateWithAnim(SettingsRoute.KeyboardModes)
            }
            addPreference(R.string.keyboard_category_behavior) {
                navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_BEHAVIOR))
            }
            addPreference(R.string.keyboard_category_feedback) {
                navigateWithAnim(SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_FEEDBACK))
            }
            // 2026-09-29：候选栏样式不再从本页挂出——它已归「输入与候选」，
            // 同一目标挂两条路径会让用户怀疑哪个才是真的。本页的「工具栏」组
            // 现在只剩工具栏自身两项（见 KEYS_BY_GROUP），标题也据此改了名。
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

    /**
     * 搜索跳转的滚动定位。
     *
     * 本页是 `PaddingPreferenceFragment` 的子类而非 `ManagedPreferenceFragment`，
     * 不会自动接入，需要手工调用。放在 `onViewCreated`（super 之后）：
     * `onCreatePreferences` 执行时 `listView` 还可能为空。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollToPendingPreference(viewModel)
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
