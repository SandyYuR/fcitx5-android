/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * 解析布局 JSON 里按键的颜色字段。
 *
 * 约定：`theme:` 前缀取主题字段（见 [resolveThemeTokenColor]），其余非空字符串当作
 * Android 系统颜色资源名（Monet 动态色），两者都取不到才回落到布局里写的静态色。
 *
 * 键盘（[KeyView]）和文本编辑栏（[org.fcitx.fcitx5.android.input.editing.FoxyTextEditingUi]
 * 用同一份 `text_editor` 布局建键）必须走同一个解析器：各抄一份的话，加 token 时很容易
 * 只改其中一边，表现为「主键盘好了、编辑器还是旧颜色」。
 */
object LayoutKeyColorResolver {

    private const val THEME_COLOR_REF_PREFIX = "theme:"

    fun resolve(context: Context, theme: Theme, staticColor: Int?, colorRef: String?): Int? {
        val refValue = colorRef?.takeIf { it.isNotBlank() }
        val resolved = if (refValue != null && refValue.startsWith(THEME_COLOR_REF_PREFIX)) {
            resolveThemeTokenColor(theme, refValue.removePrefix(THEME_COLOR_REF_PREFIX))
        } else {
            resolveMonetColor(context, refValue)
        }
        return resolved ?: staticColor
    }

    fun resolveThemeTokenColor(theme: Theme, token: String): Int? {
        return when (token) {
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
    }

    private fun resolveMonetColor(context: Context, resourceName: String?): Int? {
        val name = resourceName?.takeIf { it.isNotBlank() } ?: return null
        val colorResId = context.resources.getIdentifier(name, "color", "android")
        if (colorResId == 0) return null
        return runCatching { context.getColor(colorResId) }.getOrNull()
    }
}
