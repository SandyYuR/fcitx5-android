/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.group

import android.app.Activity
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FontsetEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.icon.IconThemeListActivity

/** 分组内一条目的跳转目标。 */
sealed interface SettingsGroupAction {
    data class Route(val route: SettingsRoute) : SettingsGroupAction
    data class Launch(val activityClass: Class<out Activity>) : SettingsGroupAction
}

/** 分组内一条设置入口。 */
data class SettingsGroupEntry(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    @StringRes val summary: Int? = null,
    val action: SettingsGroupAction
) {
    companion object {
        fun route(
            @StringRes title: Int,
            @DrawableRes icon: Int,
            route: SettingsRoute,
            @StringRes summary: Int? = null
        ) = SettingsGroupEntry(title, icon, summary, SettingsGroupAction.Route(route))

        fun activity(
            @StringRes title: Int,
            @DrawableRes icon: Int,
            activityClass: Class<out Activity>,
            @StringRes summary: Int? = null
        ) = SettingsGroupEntry(title, icon, summary, SettingsGroupAction.Launch(activityClass))
    }
}

/**
 * 设置首页的一级分组。
 *
 * 设计说明：分组页用**一张规格表 + 一个通用 Fragment** 实现，而不是为每个分组
 * 各写一个 Fragment——分组的全部内容就是「若干条带图标的跳转入口」，没有各自
 * 独有的状态或逻辑，建多个几乎相同的类只会增加维护面。
 *
 * 分组成立的标准是「用户想调某样东西时会怎么找」，而不是「代码里存在哪个类」。
 * 注意「键盘」一组没有出现在这里：它直接复用既有的 VirtualKeyboard 页
 * （那边已是五个子分组的列表），另建一个分组页只会产生两个重复入口。
 */
data class SettingsGroupSpec(
    val id: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
    /** 条目在页面上的显示顺序。需要 Context 是因为 rime 配置页的标题要参与路由参数。 */
    val entries: (Context) -> List<SettingsGroupEntry>
)

object SettingsGroupSpecs {

    const val ID_INPUT = "input"
    const val ID_APPEARANCE = "appearance"
    const val ID_CONVENIENCE = "convenience"

    private val all: List<SettingsGroupSpec> = listOf(
        SettingsGroupSpec(
            id = ID_INPUT,
            title = R.string.settings_group_input,
            summary = R.string.settings_group_input_summary,
            icon = R.drawable.ic_status_rime,
            entries = { ctx ->
                val rimeTitle = ctx.getString(R.string.rime_settings)
                listOf(
                    // 方案选择、部署、同步都在这里，是用户最常来的入口。
                    SettingsGroupEntry.route(
                        R.string.rime_settings,
                        R.drawable.ic_status_rime,
                        SettingsRoute.InputMethodConfig(rimeTitle, "rime")
                    ),
                    // 2026-09-29 由「高级 → 引擎配置」上移到本组：它调的是「怎么输入」
                    // （触发键、共享状态等），与中州韵设置同属一类，放进「高级」纯属按实现分层。
                    SettingsGroupEntry.route(
                        R.string.global_options,
                        R.drawable.ic_baseline_tune_24,
                        SettingsRoute.GlobalConfig
                    ),
                    // 候选栏样式与候选窗口同属「候选」族，两处都留在本组；
                    // 键盘页不再重复挂这一项（同一目标两条路径会让用户怀疑哪个是真的）。
                    SettingsGroupEntry.route(
                        R.string.keyboard_category_candidate,
                        R.drawable.ic_baseline_list_alt_24,
                        SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_CANDIDATE)
                    ),
                    SettingsGroupEntry.route(
                        R.string.candidates_window,
                        R.drawable.ic_baseline_tune_24,
                        SettingsRoute.CandidatesWindow
                    )
                )
            }
        ),

        SettingsGroupSpec(
            id = ID_APPEARANCE,
            title = R.string.settings_group_appearance,
            summary = R.string.settings_group_appearance_summary,
            icon = R.drawable.ic_baseline_palette_24,
            entries = {
                listOf(
                    SettingsGroupEntry.route(
                        R.string.theme,
                        R.drawable.ic_baseline_palette_24,
                        SettingsRoute.Theme
                    ),
                    SettingsGroupEntry.activity(
                        R.string.icon_theme,
                        R.drawable.ic_icon_theme_24,
                        IconThemeListActivity::class.java
                    ),
                    // 原先藏在「增强选项」这个空容器里，现在放到外观下。
                    SettingsGroupEntry.activity(
                        R.string.edit_fontset,
                        R.drawable.ic_baseline_text_format_24,
                        FontsetEditorActivity::class.java
                    )
                    // 2026-09-29：「弹出字符设定」改挂「键盘 → 键盘布局自定义」组
                    // （`KeyboardGroupFragment.GROUP_EDITORS`）——它改的是长按按键弹出的
                    // 字符映射，属于键盘的内容/定义，与外观无关；放在这里会让「外观」
                    // 的小字（主题、图标和字体）对不上内容。
                )
            }
        ),

        SettingsGroupSpec(
            id = ID_CONVENIENCE,
            title = R.string.settings_group_convenience,
            summary = R.string.settings_group_convenience_summary,
            icon = R.drawable.ic_clipboard,
            entries = {
                listOf(
                    SettingsGroupEntry.route(
                        R.string.clipboard,
                        R.drawable.ic_clipboard,
                        SettingsRoute.Clipboard
                    ),
                    SettingsGroupEntry.route(
                        R.string.emoji_and_symbols,
                        R.drawable.ic_baseline_emoji_symbols_24,
                        SettingsRoute.Symbol
                    ),
                    // 语音是一种输入方式，混在「工具栏与候选栏」里语义不对。
                    SettingsGroupEntry.route(
                        R.string.keyboard_category_voice,
                        R.drawable.ic_baseline_keyboard_voice_24,
                        SettingsRoute.KeyboardGroup(KeyboardGroupFragment.GROUP_VOICE)
                    )
                )
            }
        )
    )

    fun find(id: String): SettingsGroupSpec? = all.firstOrNull { it.id == id }
}
