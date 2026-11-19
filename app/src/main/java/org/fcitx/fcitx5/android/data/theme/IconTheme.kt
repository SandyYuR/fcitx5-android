/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * An icon theme defines custom icons for keyboard keys and toolbar buttons.
 * Each slot maps to an icon value: inline SVG string, emoji/text, or empty (fallback to built-in).
 */
@Serializable
data class IconTheme(
    @SerialName("name")
    val name: String,
    @SerialName("author")
    val author: String = "",
    @SerialName("version")
    val version: Int = 1,
    @SerialName("thumbnail_svg")
    val thumbnailSvg: String? = null,
    @SerialName("icons")
    val icons: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * Create a default icon theme with all slots empty (fallback to built-in drawables).
         */
        fun default(): IconTheme = IconTheme(
            name = "Default",
            author = "",
            version = 1,
            icons = emptyMap()
        )

        /** All icon slot keys. */
        val ALL_SLOTS: List<String> by lazy {
            KEY_SLOTS + LETTER_SLOTS + NUMPAD_KEY_SLOTS + TOOLBAR_SLOTS + SYSTEM_SLOTS
        }

        /**
         * Slots for keyboard keys.
         * These keys only support inline SVG as custom icon (no emoji/text).
         */
        val KEY_SLOTS = listOf(
            "keys.capslock.none",
            "keys.capslock.once",
            "keys.capslock.lock",
            "keys.backspace",
            "keys.return.default",
            "keys.return.go",
            "keys.return.search",
            "keys.return.send",
            "keys.return.next",
            "keys.return.previous",
            "keys.return.done",
            "keys.language",
            // 语言键在 ASCII mode 下的图标：与上面中文态槽位成对，主题可分别覆盖。
            "keys.language_ascii",
            "keys.space",
            // 逗号/句号键在部分布局里由 iconSlot 指定绘制图标；没有这两个槽位时主题
            // 作者在编辑器里找不到入口，只能手写 JSON。
            "keys.comma",
            "keys.period",
            "keys.numpad",
            "keys.emoji",
            "keys.symbols",
            "keys.unicode",
            "keys.pageup",
            "keys.pagedown",
            "keys.cursor_up",
            "keys.cursor_down",
            "keys.cursor_left",
            "keys.cursor_right",
            "keys.home",
            "keys.end",
            // 文本编辑栏（TextEditingWindow）底行按键：与主键盘一样是带图标的键，
            // 槽位同样只接受内联 SVG。归在按键分组里，避免再新增一整节文案与翻译。
            "text_editor.select_all",
            "text_editor.cut",
            "text_editor.copy",
            "text_editor.paste",
            "text_editor.backspace",
            "text_editor.layout"
        )

        /**
         * 数字键盘（numpad）各键的槽位。
         *
         * 数字键与运算符在布局里是普通 MacroKey / NumPadKey，没有自己的键型，所以槽位只能由
         * 布局 JSON 的 `iconSlot` 点名（见 `KeyDef.iconSlotOverride`）。单列一张表而不是塞进
         * [KEY_SLOTS]，是为了让图标主题编辑器里数字键盘单独成组——否则 26 个字母槽位后面
         * 再跟 20 多个 numpad 槽位，找键会很难受。
         */
        val NUMPAD_KEY_SLOTS: List<String> by lazy {
            ('0'..'9').map { "keys.numpad.$it" } + listOf(
                "keys.numpad.add",
                "keys.numpad.subtract",
                "keys.numpad.multiply",
                "keys.numpad.divide",
                "keys.numpad.equal",
                "keys.numpad.decimal",
                "keys.numpad.at",
                "keys.numpad.percent",
                "keys.numpad.space",
                // 数字键盘上切回字母的键（foxy 的 LayoutDefault，紫色四点图标）。
                "keys.numpad.layout"
            )
        }

        /**
         * Slots for the 26 letter keys, keyed by lowercase letter.
         *
         * 字母键由 [org.fcitx.fcitx5.android.input.keyboard.AlphabetKey] 生成，内置 qwerty 的
         * 字符是大写（"Q"…"M"），自定义布局里也可能写小写；槽位统一按小写字母命名，同一枚
         * 图标同时覆盖大小写两种键面，主题作者不必为 52 个键各画一遍。
         */
        val LETTER_SLOTS: List<String> by lazy { ('a'..'z').map { "keys.letter.$it" } }

        /**
         * Slots for toolbar (Kawaii Bar / Status Area) buttons.
         * These support emoji/text and inline SVG.
         */
        val TOOLBAR_SLOTS = listOf(
            "toolbar.undo",
            "toolbar.redo",
            "toolbar.cursor_move",
            "toolbar.floating_toggle",
            "toolbar.split_keyboard",
            "toolbar.clipboard",
            "toolbar.ai_assistant",
            "toolbar.js_tools",
            "toolbar.more",
            "toolbar.language_switch",
            // 语言切换按钮在 ASCII mode 下的图标：与上面中文态槽位成对，主题可分别覆盖。
            "toolbar.language_switch_ascii",
            "toolbar.theme",
            // 日夜主题切换与随机主题是两枚不同的按钮，槽位也不能共用一个。
            "toolbar.theme_toggle",
            "toolbar.random_theme",
            "toolbar.icon_theme",
            "toolbar.input_method_options",
            "toolbar.switch_input_method",
            "toolbar.rime_schema_menu",
            "toolbar.reload_config",
            "toolbar.virtual_keyboard",
            "toolbar.one_handed_keyboard",
            "toolbar.browse_user_data",
            "toolbar.settings_global",
            "toolbar.settings_ime",
            "toolbar.edit_layout",
            "toolbar.edit_fontset"
        )

        /**
         * Slots for system buttons (status area, hide keyboard, voice input).
         * These support emoji/text and inline SVG.
         */
        val SYSTEM_SLOTS = listOf(
            "system.toolbar_toggle",
            "system.hide_keyboard",
            "system.voice_input",
            // 扩展窗口（状态区 / 剪贴板 / 文本编辑）标题栏左侧的返回键是同一个 TitleUi，
            // 三处共用一个槽位，主题作者画一次即可。
            "system.back",
            // 状态区标题栏右侧：编辑器信息、输入法设置。
            "system.editor_info",
            "system.settings",
            // 剪贴板标题栏右侧：清空、搜索。
            "system.clipboard_delete",
            "system.clipboard_search"
        )

        /** Check if a slot is for keyboard keys (SVG only, no emoji/text). */
        fun isKeySlot(slot: String): Boolean = slot.startsWith("keys.")

        /**
         * 单个字母键对应的图标槽位；非字母（标点、多字符、功能键）返回 null。
         *
         * 只认 ASCII 字母：布局里的字母键既能是 "A" 也能是 "a"，两者落到同一个槽位。
         */
        fun letterSlotOf(character: String): String? {
            if (character.length != 1) return null
            val c = character[0]
            if (c !in 'a'..'z' && c !in 'A'..'Z') return null
            return "keys.letter.${c.lowercaseChar()}"
        }

        /** Check if a slot supports emoji/text input (toolbar or system). */
        fun supportsTextInput(slot: String): Boolean = slot.startsWith("toolbar.") || slot.startsWith("system.")
    }
}
