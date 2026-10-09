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
import splitties.dimensions.dp
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
        val theme = org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
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
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            minimumWidth = context.dp(280)
            setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = context.dp(28f)
                setColor(theme.backgroundColor)
            }
        }
        root.addView(android.widget.TextView(context).apply {
            text = context.getString(R.string.rime_schema_menu)
            setTextColor(theme.keyTextColor)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, context.dp(8))
        }, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val buttonTint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(theme.accentKeyBackgroundColor, theme.keyTextColor)
        )
        entries.forEachIndexed { index, (label, actionId) ->
            root.addView(android.widget.RadioButton(context).apply {
                text = label
                isChecked = index == enabledIndex
                buttonTintList = buttonTint
                setTextColor(theme.keyTextColor)
                minHeight = context.dp(48)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    val connection: FcitxConnection = service.fcitx
                    connection.launchOnReady { api: FcitxAPI -> api.activateAction(actionId) }
                    dialog.dismiss()
                }
            }, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(48)
            ))
        }
        dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
        }
        return dialog
    }
}
