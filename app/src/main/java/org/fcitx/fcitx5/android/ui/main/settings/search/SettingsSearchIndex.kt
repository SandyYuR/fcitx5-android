/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.search

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FontsetEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PopupEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import org.fcitx.fcitx5.android.ui.main.settings.icon.IconThemeListActivity

/**
 * 一条可被搜索到的设置项。
 *
 * 设计取舍：这里是一张**静态目录**，而不是运行时反射遍历 PreferenceScreen。
 * 原因是本项目的设置项分散在三种载体上：ManagedPreference（AppPrefs/ThemePrefs）、
 * Fcitx 引擎动态生成的配置树、以及若干独立 Activity。静态目录能用同一套结构
 * 覆盖这三者，并且可以给条目补上「同义词 / 英文别名」，让用户搜「键盘高度」
 * 或「height」「jianti」都能命中——这是反射遍历做不到的。
 *
 * 代价是新增设置项时要同步登记一条，见 [SettingsSearchIndex.entries] 的注释。
 */
data class SettingsSearchEntry(
    /** 条目标题，直接复用该设置项自己的字符串资源，避免两处文案漂移。 */
    @StringRes val titleRes: Int,
    /** 面包屑（所属层级），用于结果行副标题以及在无标题命中时兜底匹配。 */
    val pathRes: List<Int>,
    /** 同义词与英文别名，全部小写；仅参与匹配，不展示。 */
    val keywords: List<String> = emptyList(),
    /** 跳转目标之一：导航图内的路由。 */
    val route: SettingsRoute? = null,
    /** 跳转目标之二：独立 Activity（如图标主题列表）。 */
    val activityClass: Class<out Activity>? = null,
    /**
     * 目标页内要滚动定位到的偏好键。
     *
     * 为空表示「只跳页、不定位」——包括两类情况：目标是分组页/列表页（没有具体项），
     * 以及目标项是个纯动作（如「导出用户数据」，它没有偏好键可寻）。
     *
     * 键由 [SettingsSearchIndex.preferenceKeys] 按标题统一映射，见那里的说明。
     */
    val preferenceKey: String? = null
) {

    /** 面包屑文本，如「Android · 虚拟键盘」。 */
    fun path(context: Context): String =
        pathRes.joinToString(" · ") { context.getString(it) }

    /** 标题文本。 */
    fun title(context: Context): String = context.getString(titleRes)

    /**
     * 是否命中查询串。命中范围＝标题 ∪ 面包屑 ∪ 别名。
     * 把面包屑也纳入匹配，是为了让「键盘」这类层级词能带出该页下的全部子项。
     *
     * 匹配规则本体在 [SettingsSearchQuery]（纯函数、有单测），这里只负责拼接待匹配文本。
     */
    fun matches(context: Context, query: String): Boolean =
        SettingsSearchQuery.matches(
            "${title(context)} ${path(context)} ${keywords.joinToString(" ")}",
            query
        )
}

/**
 * 设置项搜索目录。
 *
 * ⚠️ 维护约定：新增/改名/移动设置项时，必须同步更新这里的条目，否则该项在搜索中
 * 不可达。条目按设置页的自然浏览顺序排列，同一页的子项紧跟其页面条目。
 */
object SettingsSearchIndex {

    private const val LAYOUT = KeyboardGroupFragment.GROUP_LAYOUT
    private const val BEHAVIOR = KeyboardGroupFragment.GROUP_BEHAVIOR
    private const val FEEDBACK = KeyboardGroupFragment.GROUP_FEEDBACK
    private const val TOOLBAR = KeyboardGroupFragment.GROUP_TOOLBAR
    private const val EDITORS = KeyboardGroupFragment.GROUP_EDITORS
    private const val CANDIDATE = KeyboardGroupFragment.GROUP_CANDIDATE
    private const val VOICE = KeyboardGroupFragment.GROUP_VOICE

    /** 复用同一份面包屑列表，避免每行都建一个新 List。 */
    private val P_ANDROID = listOf(R.string.settings_search_group_android)
    private val P_KEYBOARD = listOf(R.string.settings_search_group_android, R.string.virtual_keyboard)
    private val P_THEME = listOf(R.string.settings_search_group_android, R.string.theme)
    private val P_ADVANCED = listOf(R.string.settings_search_group_android, R.string.advanced)
    private val P_DATA = listOf(R.string.settings_search_group_android, R.string.settings_group_data)
    private val P_APPEARANCE =
        listOf(R.string.settings_search_group_android, R.string.settings_group_appearance)
    private val P_CONVENIENCE =
        listOf(R.string.settings_search_group_android, R.string.settings_group_convenience)
    /** 「输入与候选」：2026-09-29 起收着中州韵设置与全局选项。 */
    private val P_INPUT =
        listOf(R.string.settings_search_group_android, R.string.settings_group_input)
    private val P_CANDIDATES =
        listOf(R.string.settings_search_group_android, R.string.candidates_window)

    /** 首页一级分组的入口本身也可被搜到。 */
    private fun groupRoute(id: String) = SettingsRoute.SettingsGroup(id)

    private fun keyboard(group: Int) = SettingsRoute.KeyboardGroup(group)

    private fun rimeRoute(context: Context): SettingsRoute.InputMethodConfig =
        SettingsRoute.InputMethodConfig(context.getString(R.string.rime_settings), "rime")

    /**
     * 标题 → 目标页内的偏好键，用于搜索跳转后的「滚动定位」。
     *
     * 做成集中一张表而不是写进每个条目，是因为这些键与 `AppPrefs` 里的键一一对应、
     * 且同一个键可能被多条条目共用（例如「空格键长按行为」既是分组项也是独立项）。
     * 集中维护让「改键名」只需要动一处。
     *
     * ⚠️ 这里只登记**确实会在目标页单独成行**的项。分组/页面级条目（如「键盘」「候选窗口」）
     * 以及纯动作项（如「导出用户数据」）不登记——它们没有可滚动定位的目标行，
     * 登记了只会让界面莫名跳动。键名必须与 `AppPrefs`/`ThemePrefs` 完全一致。
     */
    private val PREFERENCE_KEYS: Map<Int, String> = mapOf(
        /* 键盘 · 布局与尺寸 */
        R.string.keyboard_height to "keyboard_height_percent",
        R.string.keyboard_side_padding to "keyboard_side_padding",
        R.string.keyboard_bottom_padding to "keyboard_bottom_padding",
        R.string.expand_keypress_area to "expand_keypress_area",
        R.string.split_keyboard_enabled to "split_keyboard_enabled",
        R.string.split_keyboard_use_landscape_layout to "split_keyboard_use_landscape_layout",
        R.string.split_keyboard_duplicate_middle to "split_keyboard_duplicate_middle",
        R.string.split_keyboard_align_halves to "split_keyboard_align_halves",

        /* 键盘 · 按键行为 */
        R.string.popup_on_key_press to "popup_on_key_press",
        R.string.keyboard_long_press_delay to "keyboard_long_press_delay",
        R.string.swipe_symbol_behavior to "swipe_symbol_behavior",
        R.string.keep_keyboard_letters_uppercase to "keep_keyboard_letters_uppercase",
        R.string.reset_keyboard_on_focus_change to "reset_keyboard_on_focus_change",
        R.string.space_long_press_behavior to "space_long_press_behavior",
        R.string.space_key_label_mode to "space_key_label_mode",
        R.string.space_swipe_move_cursor to "space_swipe_move_cursor",
        R.string.show_lang_switch_key to "show_lang_switch_key",

        /* 键盘 · 按键反馈 */
        R.string.button_haptic_feedback to "haptic_on_keypress",
        R.string.button_up_haptic_feedback to "haptic_on_keyup",
        R.string.haptic_on_repeat to "haptic_on_repeat",
        R.string.button_vibration_milliseconds to "button_vibration_press_milliseconds",
        R.string.button_vibration_amplitude to "button_vibration_press_amplitude",
        R.string.button_sound to "sound_on_keypress",
        R.string.button_sound_volume to "button_sound_volume",
        R.string.custom_key_sound to "custom_key_sound",

        /* 键盘 · 工具栏 */
        R.string.toolbar_height_percent to "toolbar_height_percent",
        R.string.inline_suggestions to "inline_suggestions",
        R.string.toolbar_num_row_on_password to "toolbar_num_row_on_password",

        /* 键盘 · 候选栏样式 */
        R.string.horizontal_candidate_style to "horizontal_candidate_style",
        R.string.horizontal_candidate_overflow_scroll to
            "horizontal_candidate_overflow_scroll",
        R.string.highlight_first_candidate to "highlight_first_candidate",
        R.string.show_candidate_index_badge to "show_candidate_index_badge",
        R.string.candidate_index_badge_position to "candidate_index_badge_position",
        R.string.expanded_candidate_style to "expanded_candidate_style",

        /* 键盘 · 语音 */
        R.string.show_voice_input_button to "show_voice_input_button",
        R.string.preferred_voice_input to "preferred_voice_input",

        /* 键盘形态（原先无入口的两个功能） */
        R.string.floating_keyboard_enabled to "floating_mode_enabled",
        R.string.auto_floating_landscape to "auto_floating_landscape",
        R.string.one_hand_keyboard_enabled to "one_hand_mode_enabled",
        R.string.one_hand_keyboard_on_right to "one_hand_on_right_portrait",

        /* 候选窗口 */
        R.string.show_candidates_window to "show_candidates_window",
        R.string.candidates_font_size to "candidates_window_font_size",
        R.string.candidates_window_radius to "candidates_window_radius",
        R.string.candidates_position to "virtual_keyboard_candidates_position",
        R.string.candidates_window_padding to "candidates_window_padding",
        R.string.candidates_orientation to "candidates_window_orientation",
        R.string.candidates_window_min_width to "candidates_window_min_width",
        R.string.candidate_highlight_radius to "candidate_highlight_radius",
        R.string.physical_keyboard_horizontal_candidate_bar to
            "physical_keyboard_horizontal_candidate_bar",

        /* 剪贴板 */
        R.string.clipboard_listening to "clipboard_enable",
        R.string.clipboard_limit_local to "clipboard_limit_local",
        R.string.clipboard_suggestion to "clipboard_suggestion",
        R.string.clipboard_mask_sensitive to "clipboard_mask_sensitive",
        R.string.clipboard_return_after_paste to "clipboard_return_after_paste",
        R.string.clipboard_limit_remote to "clipboard_limit_remote",
        R.string.clipboard_limit_media to "clipboard_limit_media",
        R.string.clipboard_suggestion_timeout to "clipboard_item_timeout",

        /* 表情和符号 */
        R.string.hide_unsupported_emojis to "hide_unsupported_emojis",
        R.string.default_emoji_skin_tone to "default_emoji_skin_tone",

        /* 文本编辑 */
        R.string.text_editing_style to "text_editing_style",
        R.string.text_editing_cursor_step to "text_editing_cursor_step_dp",
        R.string.text_editing_cursor_long_press_delay to "text_editing_cursor_long_press_delay",

        /* 高级 */
        R.string.ignore_sys_cursor to "ignore_system_cursor",
        R.string.hide_key_config to "hide_key_config",
        R.string.disable_animation to "disable_animation",
        R.string.ignore_system_window_insets to "ignore_system_window_insets",
        R.string.vivo_keypress_workaround to "vivo_keypress_workaround",
        R.string.keyboard_height_percent_base to "keyboard_height_percent_base",
        // 「实时日志」是一项纯动作（点击直接开 LogActivity），它的 Preference 没有设置 key，
        // 因此这里不登记——登记了也找不到目标行，只会让键一直挂着不被消费。
        R.string.verbose_log to "verbose_log",

        /* 主题 · 配置 tab（在 ThemeFragment 内，由该页自行滚动） */
        R.string.key_radius to "key_radius",
        R.string.preedit_radius to "preedit_radius",
        R.string.toolbar_radius to "toolbar_radius",
        R.string.key_border to "key_border",
        R.string.key_border_stroke to "key_border_stroke",
        R.string.key_ripple_effect to "key_ripple_effect",
        R.string.move_main_text_for_alt_label to "move_main_text_for_alt_label",
        R.string.special_key_oval_shape to "special_key_oval_shape",
        R.string.text_editing_button_radius to "text_editing_button_radius",
        R.string.clipboard_entry_radius to "clipboard_entry_radius",
        R.string.candidate_bar_highlight_radius to "candidate_bar_highlight_radius",
        R.string.candidate_bar_highlight_inset to "candidate_bar_highlight_inset",
        R.string.punctuation_position to "punctuation_position",
        R.string.navbar_background to "navbar_background",
        R.string.follow_system_day_night_theme to "follow_system_dark_mode",
        R.string.light_mode_theme to "light_mode_theme",
        R.string.dark_mode_theme to "dark_mode_theme"
    )

    /**
     * 构建目录。[context] 用于解析 rime 输入法配置页的标题参数。
     *
     * 末尾统一用 [PREFERENCE_KEYS] 补上滚动定位键：条目自己声明的优先，
     * 没有声明的按标题查表。这样新增条目时不必记得手工传 `preferenceKey`。
     */
    fun entries(context: Context): List<SettingsSearchEntry> =
        buildEntries(context).map { entry ->
            entry.preferenceKey?.let { entry }
                ?: entry.copy(preferenceKey = PREFERENCE_KEYS[entry.titleRes])
        }

    private fun buildEntries(context: Context): List<SettingsSearchEntry> = listOf(

        /* ===== Fcitx：引擎侧 =====
         *
         * 2026-09-29 取消「引擎配置」中转分组后的归属：
         * 中州韵设置与全局选项是「怎么输入」，挂「输入与候选」；
         * 附加组件是引擎侧插件管理，与兼容性开关同属「平时不动」的一类，挂「高级」。
         */
        SettingsSearchEntry(
            R.string.global_options, P_INPUT,
            listOf("global", "fcitx", "quanju", "触发键", "trigger"),
            route = SettingsRoute.GlobalConfig
        ),
        SettingsSearchEntry(
            R.string.rime_settings, P_INPUT,
            listOf("rime", "zhongzhouyun", "中州韵", "方案", "schema", "预编辑", "preedit", "部署", "deploy"),
            route = rimeRoute(context)
        ),
        SettingsSearchEntry(
            R.string.addons, P_ADVANCED,
            listOf("addon", "fujian", "插件", "组件"),
            route = SettingsRoute.AddonList
        ),

        /* ===== 首页一级分组本身 ===== */
        SettingsSearchEntry(
            R.string.settings_group_input, P_ANDROID,
            listOf("input", "输入", "候选"),
            route = groupRoute(SettingsGroupSpecs.ID_INPUT)
        ),
        SettingsSearchEntry(
            R.string.settings_group_appearance, P_ANDROID,
            listOf("appearance", "外观", "waiguan", "主题", "美化"),
            route = groupRoute(SettingsGroupSpecs.ID_APPEARANCE)
        ),
        SettingsSearchEntry(
            R.string.settings_group_convenience, P_ANDROID,
            listOf("convenience", "便捷", "bianjie", "工具"),
            route = groupRoute(SettingsGroupSpecs.ID_CONVENIENCE)
        ),
        SettingsSearchEntry(
            R.string.text_editing, P_CONVENIENCE,
            listOf("text editing", "文本编辑", "编辑器", "foxy", "滑动", "触控板"),
            route = SettingsRoute.TextEditing
        ),
        SettingsSearchEntry(
            R.string.text_editing_style, P_CONVENIENCE,
            listOf("text editing style", "编辑风格", "默认风格", "Foxy滑动风格"),
            route = SettingsRoute.TextEditing
        ),
        SettingsSearchEntry(
            R.string.text_editing_cursor_step, P_CONVENIENCE,
            listOf("cursor step", "光标步长", "触控板步长", "dp"),
            route = SettingsRoute.TextEditing
        ),
        SettingsSearchEntry(
            R.string.text_editing_cursor_long_press_delay, P_CONVENIENCE,
            listOf("long press delay", "长按延迟", "选取延迟", "ms"),
            route = SettingsRoute.TextEditing
        ),
        SettingsSearchEntry(
            R.string.keyboard_modes_title, P_KEYBOARD,
            listOf("floating", "one hand", "split", "浮动", "单手", "分体", "fudong", "danshou", "fenti", "拖动"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.floating_keyboard_enabled, P_KEYBOARD,
            listOf("floating", "浮动键盘", "fudong"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.auto_floating_landscape, P_KEYBOARD,
            listOf("auto floating", "landscape floating", "横屏浮动", "横屏自动浮动", "hengping"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.one_hand_keyboard_enabled, P_KEYBOARD,
            listOf("one hand", "单手键盘", "danshou"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.one_hand_keyboard_on_right, P_KEYBOARD,
            listOf("one hand right", "靠右", "左手", "右手"),
            route = SettingsRoute.KeyboardModes
        ),

        /* ===== Android：主题与外观 ===== */
        SettingsSearchEntry(
            R.string.theme, P_ANDROID,
            listOf("theme", "zhuti", "配色", "夜间", "dark", "亮色", "暗色"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.icon_theme, P_ANDROID,
            listOf("icon", "tubiao", "图标", "图标包"),
            activityClass = IconThemeListActivity::class.java
        ),
        SettingsSearchEntry(
            R.string.key_radius, P_THEME,
            listOf("radius", "圆角", "yuanjiao", "corner"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.preedit_radius, P_THEME,
            listOf("preedit", "预编辑", "编码区", "输入区", "圆角", "radius", "corner"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.toolbar_radius, P_THEME,
            listOf(
                "toolbar radius", "toolbar corner", "top corner", "工具栏圆角", "工具栏上方圆角",
                "顶部圆角", "工具栏", "圆角", "radius"
            ),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.key_border, P_THEME,
            listOf("border", "边框", "biankuang", "描边", "stroke"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.key_border_stroke, P_THEME,
            listOf("stroke", "描边", "边框描边", "biankuangmiaobian"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.special_key_oval_shape, P_THEME,
            listOf("oval", "圆形按键", "椭圆", "操作按键", "gboard"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.text_editing_button_radius, P_THEME,
            listOf("text editing", "文本编辑按钮", "编辑按钮圆角"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.clipboard_entry_radius, P_THEME,
            listOf("clipboard radius", "剪贴板圆角", "条目圆角"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.candidate_bar_highlight_radius, P_THEME,
            listOf("candidate bar highlight", "候选栏高亮圆角", "候选高亮圆角", "工具栏候选圆角", "houxuanlan"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.candidate_bar_highlight_inset, P_THEME,
            listOf("candidate bar highlight padding", "候选栏高亮边距", "高亮边距", "工具栏候选边距"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.key_ripple_effect, P_THEME,
            listOf("ripple", "水波纹", "shuiwewen", "波纹", "涟漪"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.move_main_text_for_alt_label, P_THEME,
            listOf("main text", "主字符", "主标签", "副标签", "居中", "按键文字", "center"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.punctuation_position, P_THEME,
            listOf("punctuation", "标点", "biaodian", "符号位置"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.navbar_background, P_THEME,
            listOf("navbar", "导航栏", "daohanglan"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.follow_system_day_night_theme, P_THEME,
            listOf("dark mode", "夜间", "yejian", "跟随系统", "深色"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.light_mode_theme, P_THEME,
            listOf("light theme", "亮色", "liangse", "日间"),
            route = SettingsRoute.Theme
        ),
        SettingsSearchEntry(
            R.string.dark_mode_theme, P_THEME,
            listOf("dark theme", "暗色", "anse", "夜间"),
            route = SettingsRoute.Theme
        ),

        /* ===== Android：虚拟键盘 ===== */
        SettingsSearchEntry(
            R.string.virtual_keyboard, P_ANDROID,
            listOf("keyboard", "jianpan", "键盘"),
            route = SettingsRoute.VirtualKeyboard
        ),
        SettingsSearchEntry(
            R.string.keyboard_category_layout, P_KEYBOARD,
            listOf("layout", "尺寸", "chicun", "高度", "边距", "大小"),
            route = keyboard(LAYOUT)
        ),
        SettingsSearchEntry(
            R.string.keyboard_height, P_KEYBOARD,
            listOf("height", "高度", "gaodu"),
            route = keyboard(LAYOUT)
        ),
        SettingsSearchEntry(
            R.string.keyboard_side_padding, P_KEYBOARD,
            listOf("side padding", "两侧边距", "边距", "宽度"),
            route = keyboard(LAYOUT)
        ),
        SettingsSearchEntry(
            R.string.keyboard_bottom_padding, P_KEYBOARD,
            listOf("bottom padding", "底部边距", "底部"),
            route = keyboard(LAYOUT)
        ),
        SettingsSearchEntry(
            R.string.expand_keypress_area, P_KEYBOARD,
            listOf("expand", "扩展", "按键范围", "边缘"),
            route = keyboard(LAYOUT)
        ),
        SettingsSearchEntry(
            R.string.split_keyboard_enabled, P_KEYBOARD,
            listOf("split", "分体", "fenti", "折叠屏", "平板", "双手"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.split_keyboard_calibration_title, P_KEYBOARD,
            listOf("calibration", "校准", "阈值", "中缝", "gap"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.split_keyboard_use_landscape_layout, P_KEYBOARD,
            listOf("landscape", "横屏", "分体横屏", "折叠屏尺寸"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.split_keyboard_duplicate_middle, P_KEYBOARD,
            listOf("duplicate middle", "mirror", "中间键", "复制中间键", "双侧", "g v", "靠边字母"),
            route = SettingsRoute.KeyboardModes
        ),
        SettingsSearchEntry(
            R.string.split_keyboard_align_halves, P_KEYBOARD,
            listOf(
                "align", "centre", "center", "squeeze", "narrow", "gap",
                "对齐", "中缝", "居中", "压缩", "变窄", "键宽", "键宽度"
            ),
            route = SettingsRoute.KeyboardModes
        ),

        SettingsSearchEntry(
            R.string.keyboard_category_behavior, P_KEYBOARD,
            listOf("behavior", "按键行为", "anjian"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.popup_on_key_press, P_KEYBOARD,
            listOf("popup", "弹出", "tiaochu", "放大"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.keyboard_long_press_delay, P_KEYBOARD,
            listOf("long press", "长按", "chang'an", "延迟", "delay"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.swipe_symbol_behavior, P_KEYBOARD,
            listOf("swipe", "划动", "huadong", "上划", "下划", "符号"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.keep_keyboard_letters_uppercase, P_KEYBOARD,
            listOf("uppercase", "大写", "daxie"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.reset_keyboard_on_focus_change, P_KEYBOARD,
            listOf("focus", "焦点", "jiaodian", "切换"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.space_long_press_behavior, P_KEYBOARD,
            listOf("space", "空格", "kongge", "长按"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.space_key_label_mode, P_KEYBOARD,
            listOf("space label", "空格标签", "子模式", "submode"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.space_swipe_move_cursor, P_KEYBOARD,
            listOf("cursor", "光标", "guangbiao", "划动空格"),
            route = keyboard(BEHAVIOR)
        ),
        SettingsSearchEntry(
            R.string.show_lang_switch_key, P_KEYBOARD,
            listOf("language", "语言", "yuyan", "中英", "切换键"),
            route = keyboard(BEHAVIOR)
        ),

        SettingsSearchEntry(
            R.string.keyboard_category_feedback, P_KEYBOARD,
            listOf("feedback", "反馈", "fankui", "振动", "音效", "触感"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.button_haptic_feedback, P_KEYBOARD,
            listOf("haptic", "振动", "zhendong", "触感"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.button_up_haptic_feedback, P_KEYBOARD,
            listOf("haptic up", "松开振动", "抬起"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.haptic_on_repeat, P_KEYBOARD,
            listOf("repeat", "重复振动", "连击振动", "长按重复"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.button_vibration_milliseconds, P_KEYBOARD,
            listOf("vibration", "振动时长", "时长", "毫秒"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.button_vibration_amplitude, P_KEYBOARD,
            listOf("amplitude", "振幅", "振动幅度", "强度"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.button_sound, P_KEYBOARD,
            listOf("sound", "音效", "按键音", "anjianyin"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.button_sound_volume, P_KEYBOARD,
            listOf("volume", "音量", "yinliang"),
            route = keyboard(FEEDBACK)
        ),
        SettingsSearchEntry(
            R.string.custom_key_sound, P_KEYBOARD,
            listOf("custom sound", "自定义按键音", "导入音频"),
            route = keyboard(FEEDBACK)
        ),

        SettingsSearchEntry(
            R.string.keyboard_category_toolbar, P_KEYBOARD,
            listOf("toolbar", "工具栏", "gongjulan"),
            route = keyboard(TOOLBAR)
        ),
        SettingsSearchEntry(
            R.string.inline_suggestions, P_KEYBOARD,
            listOf("suggestion", "自动填充", "内嵌建议", "联想"),
            route = keyboard(TOOLBAR)
        ),
        SettingsSearchEntry(
            R.string.toolbar_num_row_on_password, P_KEYBOARD,
            listOf("password", "密码", "mima", "数字行"),
            route = keyboard(TOOLBAR)
        ),
        // 用户想"让工具栏更高/按钮更大"时会来搜这些词。
        // 注意同义词里**保留**了「候选栏」「候选项」「候选大小」这类说法：用户想放大候选项时
        // 直觉上会这么搜，而这里的正解是先把它调高（给字腾出高度），再去「字体设定」调大
        // `cand_font`——工具栏百分比本身不再改候选字号（见 ToolbarMetrics 类注释）。
        SettingsSearchEntry(
            R.string.toolbar_height_percent, P_KEYBOARD,
            listOf(
                "toolbar size", "工具栏", "工具栏大小", "gongjulan", "size",
                "候选栏", "候选项", "候选字", "候选大小", "放大", "houxuan"
            ),
            route = keyboard(TOOLBAR)
        ),
        // 候选栏样式已从「工具栏」拆出，2026-09-29 起只从「输入与候选」进入
        // （键盘页不再挂它，避免同一目标两条路径）。
        SettingsSearchEntry(
            R.string.keyboard_category_candidate, P_INPUT,
            listOf("candidate", "候选", "候选栏", "houxuan"),
            route = keyboard(CANDIDATE)
        ),
        SettingsSearchEntry(
            R.string.horizontal_candidate_style, P_INPUT,
            listOf("horizontal candidate", "候选栏样式", "候选宽度"),
            route = keyboard(CANDIDATE)
        ),
        SettingsSearchEntry(
            R.string.highlight_first_candidate, P_INPUT,
            listOf("highlight", "高亮", "gaoliang", "第一个候选"),
            route = keyboard(CANDIDATE)
        ),
        SettingsSearchEntry(
            R.string.show_candidate_index_badge, P_INPUT,
            listOf("candidate index", "candidate number", "候选序号", "候选项序号", "角标"),
            route = keyboard(CANDIDATE)
        ),
        SettingsSearchEntry(
            R.string.candidate_index_badge_position, P_INPUT,
            listOf("candidate badge position", "候选角标位置", "角标位置", "左上", "右上", "右下", "左下"),
            route = keyboard(CANDIDATE)
        ),
        SettingsSearchEntry(
            R.string.expanded_candidate_style, P_INPUT,
            listOf("expanded candidate", "展开候选", "候选列表"),
            route = keyboard(CANDIDATE)
        ),
        SettingsSearchEntry(
            R.string.horizontal_candidate_overflow_scroll, P_INPUT,
            listOf("candidate scroll", "候选滚动", "长候选", "候选词过长", "横向滚动"),
            route = keyboard(CANDIDATE)
        ),
        // 语音是一种输入方式，已从工具栏组拆出。
        SettingsSearchEntry(
            R.string.keyboard_category_voice, P_KEYBOARD,
            listOf("voice", "语音", "yuyin", "话筒"),
            route = keyboard(VOICE)
        ),
        SettingsSearchEntry(
            R.string.show_voice_input_button, P_KEYBOARD,
            listOf("voice button", "显示语音按钮", "话筒"),
            route = keyboard(VOICE)
        ),
        SettingsSearchEntry(
            R.string.preferred_voice_input, P_KEYBOARD,
            listOf("voice input", "首选语音", "语音服务"),
            route = keyboard(VOICE)
        ),
        SettingsSearchEntry(
            R.string.edit_buttons, P_KEYBOARD,
            listOf("buttons", "编辑按钮", "按钮", "anniu", "排序"),
            route = keyboard(TOOLBAR)
        ),

        SettingsSearchEntry(
            R.string.keyboard_category_editors, P_KEYBOARD,
            listOf("editors", "自定义", "键盘布局自定义", "键盘自定义", "增强选项", "工具"),
            route = keyboard(EDITORS)
        ),
        // 「字体设定」原先藏在「增强选项」这个空容器里，2026-09-28 移到「外观」分组下。
        // 「弹出字符设定」2026-09-29 又移到「键盘 → 键盘布局自定义」：它编辑的是长按按键
        // 弹出的字符映射，属于键盘的内容定义，不是外观。
        // 两项都是 Activity，搜索结果直接拉起编辑器本身，不经分组页中转（少一层点击）。
        SettingsSearchEntry(
            R.string.edit_fontset, P_APPEARANCE,
            listOf("font", "字体", "ziti", "字型"),
            activityClass = FontsetEditorActivity::class.java
        ),
        SettingsSearchEntry(
            R.string.edit_popup_preset, P_KEYBOARD,
            listOf("popup preset", "弹出字符", "长按候选", "长按字符"),
            activityClass = PopupEditorActivity::class.java
        ),
        SettingsSearchEntry(
            R.string.edit_text_keyboard_layout, P_KEYBOARD,
            listOf("layout editor", "键盘定义", "布局编辑", "自定义键盘"),
            route = keyboard(EDITORS)
        ),
        SettingsSearchEntry(
            R.string.text_keyboard_layout_file_select_title, P_KEYBOARD,
            listOf("layout file", "键盘定义配置", "切换配置"),
            route = keyboard(EDITORS)
        ),
        SettingsSearchEntry(
            R.string.numeric_layout_override_title, P_KEYBOARD,
            listOf("numeric", "数字键盘", "shuzi", "数字布局"),
            route = keyboard(EDITORS)
        ),
        SettingsSearchEntry(
            R.string.web_editor_bridge_title, P_KEYBOARD,
            listOf("web editor", "在线编辑器", "网页编辑", "浏览器"),
            route = keyboard(EDITORS)
        ),

        /* ===== Android：候选窗口 ===== */
        SettingsSearchEntry(
            R.string.candidates_window, P_ANDROID,
            listOf("candidates", "候选", "houxuan", "候选窗口"),
            route = SettingsRoute.CandidatesWindow
        ),
        // 纯动作项（一次性写入一组数值），没有偏好键可定位，位置固定在页面首位。
        SettingsSearchEntry(
            R.string.candidates_preset_title, P_CANDIDATES,
            listOf("preset", "预设", "yishe", "紧凑", "标准", "宽松", "快捷"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.show_candidates_window, P_ANDROID,
            listOf("candidates mode", "显示候选窗口", "悬浮候选"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.candidates_font_size, P_ANDROID,
            listOf("font size", "候选字体", "字号"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.candidates_window_radius, P_ANDROID,
            listOf("radius", "候选圆角", "圆角"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.candidates_position, P_ANDROID,
            listOf("position", "候选位置", "位置"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.candidates_window_padding, P_ANDROID,
            listOf("padding", "候选边距", "内边距"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.candidates_orientation, P_ANDROID,
            listOf("orientation", "方向", "fangxiang", "横排", "竖排", "候选排列"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.candidates_window_min_width, P_ANDROID,
            listOf("min width", "最小宽度", "宽度", "kuandu"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.candidate_highlight_radius, P_ANDROID,
            listOf("highlight", "高亮圆角", "选中圆角", "gaoliang"),
            route = SettingsRoute.CandidatesWindow
        ),
        SettingsSearchEntry(
            R.string.physical_keyboard_horizontal_candidate_bar, P_ANDROID,
            listOf("physical keyboard", "物理键盘", "外接键盘", "水平候选栏"),
            route = SettingsRoute.CandidatesWindow
        ),

        /* ===== Android：剪贴板 ===== */
        SettingsSearchEntry(
            R.string.clipboard, P_ANDROID,
            listOf("clipboard", "剪贴板", "jiantieban", "粘贴"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_listening, P_ANDROID,
            listOf("clipboard history", "记录剪贴板", "历史记录"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_limit_local, P_ANDROID,
            listOf("limit", "上限", "条数", "历史上限"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_suggestion, P_ANDROID,
            listOf("suggestion", "剪贴板提示", "推荐"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_mask_sensitive, P_ANDROID,
            listOf("sensitive", "敏感", "mingan", "隐私", "密码"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_return_after_paste, P_ANDROID,
            listOf("return", "粘贴后返回", "返回"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_limit_remote, P_ANDROID,
            listOf("remote limit", "远端上限", "同步上限", "远端条数"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_limit_media, P_ANDROID,
            listOf("media limit", "媒体上限", "图片上限", "媒体条数"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_suggestion_timeout, P_ANDROID,
            listOf("timeout", "超时", "chaoshi", "提示时长"),
            route = SettingsRoute.Clipboard
        ),
        SettingsSearchEntry(
            R.string.clipboard_sync_settings, P_ANDROID,
            listOf("sync", "同步", "tongbu", "服务器", "局域网", "设备"),
            route = SettingsRoute.Clipboard
        ),

        /* ===== Android：表情和符号 ===== */
        SettingsSearchEntry(
            R.string.emoji_and_symbols, P_ANDROID,
            listOf("emoji", "symbol", "表情", "符号", "biaoqing", "颜文字", "kaomoji"),
            route = SettingsRoute.Symbol
        ),
        SettingsSearchEntry(
            R.string.hide_unsupported_emojis, P_ANDROID,
            listOf("unsupported emoji", "隐藏不支持", "表情兼容"),
            route = SettingsRoute.Symbol
        ),
        SettingsSearchEntry(
            R.string.default_emoji_skin_tone, P_ANDROID,
            listOf("skin tone", "肤色", "fuse", "表情肤色"),
            route = SettingsRoute.Symbol
        ),
        SettingsSearchEntry(
            R.string.symbol_catalog_symbols_title, P_ANDROID,
            listOf("catalog", "数据源", "符号库", "自定义符号"),
            route = SettingsRoute.Symbol
        ),
        SettingsSearchEntry(
            R.string.symbol_catalog_emoji_title, P_ANDROID,
            listOf("catalog", "数据源", "表情库", "自定义表情"),
            route = SettingsRoute.Symbol
        ),
        SettingsSearchEntry(
            R.string.symbol_catalog_kaomoji_title, P_ANDROID,
            listOf("catalog", "数据源", "颜文字库"),
            route = SettingsRoute.Symbol
        ),

        /* ===== Android：数据与备份 ===== */
        SettingsSearchEntry(
            R.string.settings_group_data, P_ANDROID,
            listOf("data", "backup", "数据", "备份", "导入导出"),
            route = SettingsRoute.DataBackup
        ),
        SettingsSearchEntry(
            R.string.export_user_data, P_DATA,
            listOf("export", "导出", "daochu", "备份"),
            route = SettingsRoute.DataBackup
        ),
        SettingsSearchEntry(
            R.string.import_user_data, P_DATA,
            listOf("import", "导入", "daoru", "恢复"),
            route = SettingsRoute.DataBackup
        ),
        SettingsSearchEntry(
            R.string.browse_user_data_dir, P_DATA,
            listOf("data dir", "用户数据目录", "文件", "目录"),
            route = SettingsRoute.DataBackup
        ),

        /* ===== Android：高级 ===== */
        SettingsSearchEntry(
            R.string.advanced, P_ANDROID,
            listOf("advanced", "高级", "gaoji", "其它", "其他"),
            route = SettingsRoute.Advanced
        ),
        SettingsSearchEntry(
            R.string.ignore_sys_cursor, P_ADVANCED,
            listOf("cursor", "系统光标", "光标位置"),
            route = SettingsRoute.Advanced
        ),
        // 2026-09-29 归位：开关从「引擎配置」中转页移回「高级」本页
        // （那个中转分组已取消：全局选项与中州韵设置改挂「输入与候选」，
        // 附加组件与本开关一起回到「高级」）。
        SettingsSearchEntry(
            R.string.hide_key_config, P_ADVANCED,
            listOf("key config", "快捷键", "kuaijiejian", "隐藏快捷键", "trigger keys"),
            route = SettingsRoute.Advanced
        ),
        SettingsSearchEntry(
            R.string.disable_animation, P_ADVANCED,
            listOf("animation", "动画", "donghua", "禁用动画"),
            route = SettingsRoute.Advanced
        ),
        SettingsSearchEntry(
            R.string.ignore_system_window_insets, P_ADVANCED,
            listOf("insets", "边衬区", "windowinsets", "系统窗口"),
            route = SettingsRoute.Advanced
        ),
        SettingsSearchEntry(
            R.string.vivo_keypress_workaround, P_ADVANCED,
            listOf("vivo", "origin", "按键检测", "兼容"),
            route = SettingsRoute.Advanced
        ),
        SettingsSearchEntry(
            R.string.keyboard_height_percent_base, P_ADVANCED,
            listOf("height base", "高度基准", "屏幕尺寸", "真实尺寸", "计算基准"),
            route = SettingsRoute.Advanced
        ),

        /* ===== 其它 ===== */
        SettingsSearchEntry(
            R.string.developer, P_ADVANCED,
            listOf("developer", "开发者", "kaifazhe", "调试"),
            route = SettingsRoute.Developer
        ),
        SettingsSearchEntry(
            R.string.real_time_logs, P_ADVANCED,
            listOf("log", "日志", "rizhi"),
            route = SettingsRoute.Developer
        ),
        // 「详细记录日志」的标题在 DeveloperFragment 里用 setTitle 直接设置（偏好本体定义在
        // AppPrefs.Internal，没有走 R.string 元数据），所以覆盖测试扫不到它——
        // 这条曾经只剩 PREFERENCE_KEYS 里的映射键、没有条目引用，成了孤儿键。
        SettingsSearchEntry(
            R.string.verbose_log, P_ADVANCED,
            listOf("verbose", "详细日志", "xiangxirizhi", "诊断", "排查"),
            route = SettingsRoute.Developer
        ),
        SettingsSearchEntry(
            R.string.restart_fcitx_instance, P_ADVANCED,
            listOf("restart", "重启", "chongqi"),
            route = SettingsRoute.Developer
        ),
        SettingsSearchEntry(
            R.string.about, P_ANDROID,
            listOf("about", "关于", "guanyu", "版本"),
            route = SettingsRoute.About
        ),
        SettingsSearchEntry(
            R.string.license, P_ANDROID,
            listOf("license", "许可", "xuke", "开源协议"),
            route = SettingsRoute.License
        )
    )

    /**
     * 按查询串过滤目录。
     *
     * [entries] 由调用方缓存并传入：目录是静态的，没必要每敲一个字都重建一遍
     * （约 90 条 data class）。
     */
    fun filter(
        context: Context,
        entries: List<SettingsSearchEntry>,
        query: String
    ): List<SettingsSearchEntry> {
        if (query.isBlank()) return emptyList()
        return entries.filter { it.matches(context, query) }
    }
}
