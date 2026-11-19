/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

/**
 * 布局 JSON 里的颜色字面量解析。
 *
 * 布局编辑器与运行时会读取用户手写的颜色；[android.graphics.Color.parseColor] 支持
 * `#RGB`/`#ARGB`/`#RRGGBB`/`#AARRGGBB`，但本项目的旧解析器直接把十六进制位当整数，
 * 六位 `#RRGGBB` 会得到 alpha=0 的透明色。这里统一按 Android 的四种写法补 alpha，
 * 同时保留 0x 前缀和十进制整数。
 */
object ColorLiteral {
    fun parseArgb(raw: String?): Int? {
        val value = raw?.trim()?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
            ?: return null
        if (value.startsWith("#")) {
            val hex = value.substring(1)
            if (hex.isEmpty() || hex.any { !it.isHexDigit() }) return null
            val argb = when (hex.length) {
                3 -> "FF" + hex.map { "$it$it" }.joinToString("")
                4 -> hex.map { "$it$it" }.joinToString("")
                6 -> "FF$hex"
                8 -> hex
                else -> return null
            }
            return argb.toLongOrNull(16)?.toInt()
        }
        if (value.startsWith("0x", ignoreCase = true)) {
            return value.substring(2).toLongOrNull(16)?.toInt()
        }
        return value.toLongOrNull()?.toInt()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
