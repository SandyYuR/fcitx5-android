/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.CallSuper
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference

abstract class ManagedPreferenceFragment(private val preferenceProvider: ManagedPreferenceProvider) :
    PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val evaluator = ManagedPreferenceVisibilityEvaluator(preferenceProvider) {
        lifecycleScope.launch {
            it.forEach { (key, enable) ->
                findPreference<Preference>(key)?.isEnabled = enable
            }
        }
    }

    open fun onPreferenceUiCreated(screen: PreferenceScreen) {}

    @CallSuper
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        evaluator.evaluateVisibility()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(preferenceManager.context).also { screen ->
                preferenceProvider.createUi(screen)
                onPreferenceUiCreated(screen)
            }
    }

    /**
     * 处理搜索跳转带来的滚动定位。
     *
     * 放在 `onViewCreated` 而非 `onCreatePreferences`：后者执行时 `listView` 还可能为空
     * （`onCreateView` 尚未返回），而 `onViewCreated` 时列表已就绪。
     * `scrollToPreference` 自身会等待目标项出现在 adapter 中，因此这里不必额外轮询。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollToPendingPreference(viewModel)
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            lifecycleScope.launch(Dispatchers.IO) {
                AppPrefs.getInstance().syncToDeviceEncryptedStorage()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        evaluator.destroy()
        super.onDestroy()
    }
}
