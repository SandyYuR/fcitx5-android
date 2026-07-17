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
        val ALL_SLOTS: List<String> by lazy { KEY_SLOTS + TOOLBAR_SLOTS + SYSTEM_SLOTS }

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
            "keys.quickphrase",
            "keys.space",
            "keys.numpad",
            "keys.emoji",
            "keys.symbols",
            "keys.unicode",
            "keys.pageup",
            "keys.pagedown"
        )

        /**
         * Slots for toolbar (Kawaii Bar / Status Area) buttons.
         * These support emoji/text and inline SVG.
         */
        val TOOLBAR_SLOTS = listOf(
            "toolbar.undo",
            "toolbar.redo",
            "toolbar.cursor_move",
            "toolbar.floating_toggle",
            "toolbar.clipboard",
            "toolbar.more",
            "toolbar.language_switch",
            "toolbar.theme",
            "toolbar.icon_theme",
            "toolbar.input_method_options",
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
         * Slots for system buttons (toolbar toggle, hide keyboard, voice input).
         * These support emoji/text and inline SVG.
         */
        val SYSTEM_SLOTS = listOf(
            "system.toolbar_toggle",
            "system.hide_keyboard",
            "system.voice_input"
        )

        /** Check if a slot is for keyboard keys (SVG only, no emoji/text). */
        fun isKeySlot(slot: String): Boolean = slot.startsWith("keys.")

        /** Check if a slot supports emoji/text input (toolbar or system). */
        fun supportsTextInput(slot: String): Boolean = slot.startsWith("toolbar.") || slot.startsWith("system.")
    }
}
