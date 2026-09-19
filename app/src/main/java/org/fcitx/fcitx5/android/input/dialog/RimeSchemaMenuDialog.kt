/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dialog

import android.app.AlertDialog
import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.manager.SubModeManager
import timber.log.Timber

/**
 * Rime 方案切换菜单：语言键长按弹出。
 *
 * 与布局编辑器的「不同方案不同布局」共用 SubModeManager 的 selector 语义
 * （状态区方案菜单 → 分隔符之前的条目 → 去掉首位西文伪条目），点选后经
 * [FcitxAPI.activateAction] 把动作 id 写回引擎，引擎侧切方案并广播状态更新。
 */
object RimeSchemaMenuDialog {
    suspend fun build(
        fcitx: FcitxAPI,
        service: FcitxInputMethodService,
        context: Context
    ): AlertDialog {
        val entries: List<Pair<String, Int>> =
            runCatching { SubModeManager.resolveSchemaMenuEntries(fcitx.statusArea()) }
                .onFailure { Timber.w(it, "Failed to resolve rime schema menu") }
                .getOrElse { emptyList() }
        val enabledName: String? = runCatching { fcitx.currentIme() }
            .getOrNull()
            ?.subMode
            ?.let { it.label.ifEmpty { it.name } }
            ?.trim()
        val labels: List<String> = entries.map { it.first }
        // enabledIndex 找不到时传 -1（无高亮），与 InputMethodListAdapter 约定一致。
        val enabledIndex: Int = enabledName?.let { labels.indexOf(it) } ?: -1
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.rime_schema_menu)
            .setSingleChoiceItems(labels.toTypedArray(), enabledIndex) { _, which: Int ->
                val actionId: Int? = entries.getOrNull(which)?.second
                if (actionId != null) {
                    val connection: FcitxConnection = service.fcitx
                    connection.launchOnReady { api: FcitxAPI -> api.activateAction(actionId) }
                }
                dialog.dismiss()
            }
            .create()
        return dialog
    }
}
