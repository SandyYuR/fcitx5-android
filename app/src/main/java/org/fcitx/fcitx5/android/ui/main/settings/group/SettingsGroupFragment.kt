/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.group

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/**
 * 通用分组页：按 [SettingsGroupSpec] 渲染一组带图标的跳转入口。
 *
 * 找不到分组规格时（例如规格表被改而路由仍指向旧 id）只显示空页，不崩溃——
 * 设置页因为一个过期路由而闪退的代价远大于空白页。
 */
class SettingsGroupFragment : PaddingPreferenceFragment() {

    private val args by lazyRoute<SettingsRoute.SettingsGroup>()
    private val viewModel: MainViewModel by activityViewModels()

    private val spec: SettingsGroupSpec? get() = SettingsGroupSpecs.find(args.id)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            val spec = this@SettingsGroupFragment.spec ?: return@apply
            spec.entries(requireContext()).forEach { entry ->
                addPreference(entry.title, entry.summary, entry.icon) {
                    launch(entry.action)
                }
            }
        }
    }

    /**
     * 搜索跳转的滚动定位。
     *
     * 本页不是 `ManagedPreferenceFragment` 的子类（它不持有 provider），
     * 所以不会自动接入，需要像 `KeyboardModesFragment` 那样手工调用。
     * 放在 `onViewCreated`（super 之后）：`onCreatePreferences` 执行时 `listView`
     * 还可能为空。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollToPendingPreference(viewModel)
    }

    override fun onResume() {
        super.onResume()
        val spec = spec ?: return
        viewModel.setToolbarTitle(getString(spec.title))
    }

    private fun launch(action: SettingsGroupAction) = when (action) {
        is SettingsGroupAction.Route -> navigateWithAnim(action.route)
        is SettingsGroupAction.Launch ->
            startActivity(Intent(requireContext(), action.activityClass))
    }
}
