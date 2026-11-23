/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.content.Context
import android.graphics.Color
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.settingsColor

/** logcat threadtime 行里的级别字母（V/D/I/W/E/F）。非 logcat 行返回 null。 */
internal val logLevelRegex =
    Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s""")

internal fun logLevelOf(line: CharSequence): Char? =
    logLevelRegex.find(line)?.groupValues?.getOrNull(1)?.firstOrNull()

/**
 * 主题颜色解析。优先走 [settingsColor]（含 Material 角色映射与色板限定符），失败时退回
 * 传入的兜底色，保证不会出现 0（全透明）导致元素消失。
 */
@ColorInt
internal fun Context.logColorOrDefault(@AttrRes attrRes: Int, @ColorInt fallback: Int): Int =
    runCatching { settingsColor(attrRes) }.getOrDefault(fallback)

/** 级别对应的着色属性、图标与兜底色。兜底色随 DayNight 切换。 */
internal data class LogLevelStyle(
    @AttrRes val colorAttr: Int,
    @DrawableRes val iconRes: Int,
    @ColorRes val fallbackColorRes: Int
)

internal fun logLevelStyle(level: Char?): LogLevelStyle = when (level) {
    'V' -> LogLevelStyle(R.attr.colorLogVerbose, R.drawable.ic_log_verbose, R.color.log_level_verbose)
    'D' -> LogLevelStyle(R.attr.colorLogDebug, R.drawable.ic_log_debug, R.color.log_level_debug)
    'W' -> LogLevelStyle(R.attr.colorLogWarning, R.drawable.ic_log_warning, R.color.log_level_warning)
    'E' -> LogLevelStyle(R.attr.colorLogError, R.drawable.ic_log_error, R.color.log_level_error)
    'F' -> LogLevelStyle(R.attr.colorLogFatal, R.drawable.ic_log_fatal, R.color.log_level_fatal)
    else -> LogLevelStyle(R.attr.colorLogInfo, R.drawable.ic_log_info, R.color.log_level_info)
}

/** 解析级别颜色；解析成透明时同样退回兜底色。 */
@ColorInt
internal fun Context.logLevelColor(style: LogLevelStyle): Int {
    val fallback = ContextCompat.getColor(this, style.fallbackColorRes)
    val resolved = logColorOrDefault(style.colorAttr, fallback)
    return if (Color.alpha(resolved) == 0) fallback else resolved
}

/** 无级别行（空行、崩溃堆栈、普通 append）用主题前景色，失败时退回 Verbose 的中性灰。 */
@ColorInt
internal fun Context.logDefaultTextColor(): Int = logColorOrDefault(
    android.R.attr.colorForeground,
    ContextCompat.getColor(this, R.color.log_level_verbose)
)
