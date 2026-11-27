/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Intent
import android.text.TextUtils
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute

class InputMethodConfigFragment : FcitxPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    override fun getPageTitle(): String = args.name

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig =
        fcitx.getImConfig(args.uniqueName)

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(args.uniqueName, newConfig)
    }

    override fun decoratePreferenceScreen(screen: PreferenceScreen) {
        if (args.uniqueName != RIME_UNIQUE_NAME) return
        screen.addPreference(object : Preference(screen.context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
        }.apply {
            key = "rime_engine_variants"
            setTitle(R.string.rime_engine_variants_title)
            setSummary(R.string.rime_engine_variants_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            widgetLayoutResource = R.layout.preference_settings_navigation
            intent = Intent(context, RimeEngineManagerActivity::class.java)
        })
    }

    /**
     * 「中州韵」页由引擎的 `desc` 树生成，密码框开关在引擎配置项之后追加。
     * 「输入类型」已迁到「启用的输入方案」页，按方案逐个调整；这里只保留仅对 Rime 生效的
     * 「密码框是否交给 Rime」开关。
     */
    override fun postDecoratePreferenceScreen(screen: PreferenceScreen) {
        if (args.uniqueName != RIME_UNIQUE_NAME) return
        val pref = AppPrefs.getInstance().keyboard.allowImeInPasswordFields
        val switch = MySwitchPreference(requireContext()).apply {
            key = pref.key
            setTitle(R.string.allow_ime_in_password_fields)
            setSummary(R.string.allow_ime_in_password_fields_summary)
            setDefaultValue(pref.defaultValue)
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        screen.addPreference(switch)
    }

    companion object {
        private const val RIME_UNIQUE_NAME = "rime"
    }
}
