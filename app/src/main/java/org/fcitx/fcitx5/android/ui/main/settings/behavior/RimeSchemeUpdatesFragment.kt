/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Bundle
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdateActivity
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdateActivity
import org.fcitx.fcitx5.android.utils.addPreference

/**
 * 「中州韵方案更新」：万象更新与自定义更新两个入口，从「数据与备份」页搬出来放到设置首页。
 */
class RimeSchemeUpdatesFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            addPreference(R.string.wanxiang_update, R.string.wanxiang_update_summary) {
                startActivity(Intent(context, WanxiangUpdateActivity::class.java))
            }
            addPreference(R.string.scheme_update_title, R.string.scheme_update_summary) {
                startActivity(Intent(context, RimeSchemeUpdateActivity::class.java))
            }
        }
    }
}
