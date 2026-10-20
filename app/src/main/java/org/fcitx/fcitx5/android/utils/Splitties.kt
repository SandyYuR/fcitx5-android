/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

@file:OptIn(InternalSplittiesApi::class)

package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import splitties.experimental.InternalSplittiesApi
import splitties.resources.styledColor
import splitties.resources.withResolvedThemeAttribute
import splitties.views.dsl.core.Ui

private fun materialColorAttribute(@AttrRes attrRes: Int): Int = when (attrRes) {
    android.R.attr.colorPrimary,
    android.R.attr.colorAccent,
    android.R.attr.colorControlActivated -> androidx.appcompat.R.attr.colorPrimary
    android.R.attr.colorBackground -> com.google.android.material.R.attr.colorSurface
    android.R.attr.colorBackgroundFloating,
    android.R.attr.colorButtonNormal -> com.google.android.material.R.attr.colorSurfaceContainerHigh
    android.R.attr.colorControlHighlight -> androidx.appcompat.R.attr.colorControlHighlight
    android.R.attr.colorControlNormal -> com.google.android.material.R.attr.colorOnSurfaceVariant
    android.R.attr.colorForeground,
    android.R.attr.textColorPrimary -> com.google.android.material.R.attr.colorOnSurface
    android.R.attr.colorForegroundInverse -> com.google.android.material.R.attr.colorSurfaceInverse
    android.R.attr.textColorHint,
    android.R.attr.textColorSecondary,
    android.R.attr.textColorTertiary -> com.google.android.material.R.attr.colorOnSurfaceVariant
    else -> attrRes
}

/**
 * Resolves legacy color lookups through Material color roles. TypedArray handles color resources
 * in all resource qualifiers, including dynamic system palettes that Splitties' styledColor does
 * not recognize on some Android builds.
 */
fun Context.settingsColorStateList(@AttrRes attrRes: Int): ColorStateList {
    val materialAttr = materialColorAttribute(attrRes)
    fun resolve(attribute: Int): ColorStateList? {
        val values = theme.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            if (!values.hasValue(0)) return null
            values.getColorStateList(0)
                ?: ColorStateList.valueOf(values.getColor(0, android.graphics.Color.TRANSPARENT))
        } finally {
            values.recycle()
        }
    }

    return resolve(materialAttr)
        ?: resolve(attrRes)?.takeIf { materialAttr != attrRes }
        ?: error("Color theme attribute $materialAttr is not defined")
}

@ColorInt
fun Context.settingsColor(@AttrRes attrRes: Int): Int = settingsColorStateList(attrRes).defaultColor

@Suppress("NOTHING_TO_INLINE")
inline fun View.settingsColor(@AttrRes attrRes: Int) = context.settingsColor(attrRes)

@Suppress("NOTHING_TO_INLINE")
inline fun Ui.settingsColor(@AttrRes attrRes: Int) = ctx.settingsColor(attrRes)

@Suppress("NOTHING_TO_INLINE")
inline fun Ui.settingsColorStateList(@AttrRes attrRes: Int) = ctx.settingsColorStateList(attrRes)

fun Context.styledFloat(@AttrRes attrRes: Int) = withResolvedThemeAttribute(attrRes) {
    when (type) {
        TypedValue.TYPE_FLOAT -> float
        else -> throw IllegalArgumentException("float attribute expected")
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun View.styledFloat(@AttrRes attrRes: Int) = context.styledFloat(attrRes)

@Suppress("NOTHING_TO_INLINE")
inline fun Ui.styledFloat(@AttrRes attrRes: Int) = ctx.styledFloat(attrRes)

inline val ConstraintLayout.LayoutParams.unset
    get() = ConstraintLayout.LayoutParams.UNSET

@ColorInt
fun Context.styledColorOrDefault(@AttrRes attrRes: Int, @ColorInt defaultValue: Int) =
    runCatching { styledColor(attrRes) }.getOrDefault(defaultValue)

@Suppress("NOTHING_TO_INLINE")
inline fun View.styledColorOrDefault(@AttrRes attrRes: Int, @ColorInt defaultValue: Int) =
    context.styledColorOrDefault(attrRes, defaultValue)

@Suppress("NOTHING_TO_INLINE")
inline fun Ui.styledColorOrDefault(@AttrRes attrRes: Int, @ColorInt defaultValue: Int) =
    ctx.styledColorOrDefault(attrRes, defaultValue)
