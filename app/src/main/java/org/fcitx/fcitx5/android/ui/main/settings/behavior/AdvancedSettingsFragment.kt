/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/**
 * 「高级」页：兼容性开关 + 引擎附加组件入口。
 *
 * 内容来源有两处：
 * - 开关项由 [AppPrefs.getInstance().advanced] 的 UI 元数据自动渲染
 *   （含 `hide_key_config`——它控制「全局选项」里 Fcitx 快捷键族的显隐，
 *   2026-09-29 由「引擎配置」中转页移回本页，因为那个中转分组已取消）；
 * - 「附加组件」是一个手工跳转项。
 *
 * 原先这里挂着「引擎配置」二级入口，收着 Fcitx 全局选项 / 中州韵设置 / 附加组件三页。
 * 2026-09-29 按「用户想调什么」重新分层：全局选项与中州韵设置是「怎么输入」，
 * 上移到「输入与候选」；只有附加组件留在这里——它是引擎侧插件管理，
 * 与兼容性开关同属「平时不动、需要时才来」的一类。
 *
 * 「浏览用户数据目录 / 导出 / 导入」已拆到 [DataBackupFragment]——导入导出是用户
 * 主动执行的一次性任务，与开关混在一页会让「高级」既不像是危险操作区、也不像是设置区。
 */
class AdvancedSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().advanced) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            R.string.addons,
            onClick = {
                navigateWithAnim(SettingsRoute.AddonList)
            }
        )
    }
}
