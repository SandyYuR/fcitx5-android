/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme

/** Editor colors only: reads existing key fields without changing layout data.
 * Inspired by boomker/fcitx5-android@1d85a0d5, with this project's key variants.
 */
internal class LayoutKeyColors(private val systemColor: (String) -> Int? = { null }) {
    fun background(key: Map<String, Any?>, theme: Theme): Int =
        resolveOverride(key, "backgroundColor", theme) ?: when (key["type"]) {
            "CapsKey", "LayoutSwitchKey", "CommaKey", "SymbolKey", "BackspaceKey", "MiniSpaceKey" -> theme.altKeyBackgroundColor
            "ReturnKey" -> theme.accentKeyBackgroundColor
            else -> theme.keyBackgroundColor
        }

    fun foreground(key: Map<String, Any?>, theme: Theme): Int =
        resolveOverride(key, "textColor", theme) ?: when (key["type"]) {
            "CapsKey", "LayoutSwitchKey", "CommaKey", "SymbolKey", "BackspaceKey", "MiniSpaceKey", "LanguageKey" -> theme.altKeyTextColor
            "ReturnKey" -> theme.accentKeyTextColor
            else -> theme.keyTextColor
        }

    private fun resolveOverride(key: Map<String, Any?>, field: String, theme: Theme): Int? {
        val reference = (key[field + "Monet"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = when {
            reference == null -> null
            reference.startsWith("theme:") -> themeToken(theme, reference.removePrefix("theme:"))
            else -> systemColor(reference)
        }
        return resolved ?: parseColor(key[field])
    }

    private fun parseColor(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().let {
            when {
                it.startsWith("#") -> it.drop(1).toLongOrNull(16)?.toInt()
                it.startsWith("0x", ignoreCase = true) -> it.drop(2).toLongOrNull(16)?.toInt()
                else -> it.toLongOrNull()?.toInt()
            }
        }
        else -> null
    }

    // Same token vocabulary as KeyView and the existing key color editor.
    private fun themeToken(theme: Theme, token: String): Int? = when (token) {
        "backgroundColor" -> theme.backgroundColor
        "barColor" -> theme.barColor
        "keyboardColor" -> theme.keyboardColor
        "keyBackgroundColor" -> theme.keyBackgroundColor
        "keyTextColor" -> theme.keyTextColor
        "candidateTextColor" -> theme.candidateTextColor
        "candidateLabelColor" -> theme.candidateLabelColor
        "candidateCommentColor" -> theme.candidateCommentColor
        "altKeyBackgroundColor" -> theme.altKeyBackgroundColor
        "altKeyTextColor" -> theme.altKeyTextColor
        "accentKeyBackgroundColor" -> theme.accentKeyBackgroundColor
        "accentKeyTextColor" -> theme.accentKeyTextColor
        "keyPressHighlightColor" -> theme.keyPressHighlightColor
        "keyShadowColor" -> theme.keyShadowColor
        "popupBackgroundColor" -> theme.popupBackgroundColor
        "popupTextColor" -> theme.popupTextColor
        "spaceBarColor" -> theme.spaceBarColor
        "dividerColor" -> theme.dividerColor
        "clipboardEntryColor" -> theme.clipboardEntryColor
        "genericActiveBackgroundColor" -> theme.genericActiveBackgroundColor
        "genericActiveForegroundColor" -> theme.genericActiveForegroundColor
        else -> null
    }

    companion object {
        fun forContext(context: Context): LayoutKeyColors {
            val resourceIds = mutableMapOf<String, Int>()
            return LayoutKeyColors { name ->
                val id = resourceIds.getOrPut(name) { context.resources.getIdentifier(name, "color", "android") }
                if (id == 0) null else runCatching { context.getColor(id) }.getOrNull()
            }
        }
    }
}
