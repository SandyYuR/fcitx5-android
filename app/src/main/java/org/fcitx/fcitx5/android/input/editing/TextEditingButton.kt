/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.editing

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.shadowedKeyBackgroundDrawable
import org.fcitx.fcitx5.android.input.keyboard.insetRadiusDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageResource
import kotlin.math.max

@SuppressLint("ViewConstructor")
class TextEditingButton(
    ctx: Context,
    private val theme: Theme,
    private val rippled: Boolean,
    private val bordered: Boolean,
    private val radius: Float,
    private val altStyle: Boolean = false
) : CustomGestureView(ctx) {

    // bordered
    private val shadowWidth = dp(1)
    private val hInset = dp(4)
    private val vInset = dp(4)

    // !bordered
    private val lineWidth = max(1, dp(1) / 2)

    private fun roundedBorderDrawable(backgroundColor: Int = Color.TRANSPARENT): Drawable =
        GradientDrawable().apply {
            setColor(backgroundColor)
            setStroke(lineWidth, theme.dividerColor)
            cornerRadius = radius
        }

    private fun roundedPressDrawable(color: Int): Drawable =
        insetRadiusDrawable(0, 0, radius, color)

    private fun roundedPressForeground(): Drawable = if (rippled) {
        RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor),
            null,
            roundedPressDrawable(Color.WHITE)
        )
    } else {
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedPressDrawable(theme.keyPressHighlightColor)
            )
        }
    }

    init {
        if (bordered) {
            val bkgColor = if (altStyle) theme.altKeyBackgroundColor else theme.keyBackgroundColor
            background = shadowedKeyBackgroundDrawable(
                bkgColor, theme.keyShadowColor,
                radius, shadowWidth, hInset, vInset
            )
            foreground = if (rippled) {
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor), null,
                    insetRadiusDrawable(hInset, vInset, radius)
                )
            } else {
                StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        insetRadiusDrawable(hInset, vInset, radius, theme.keyPressHighlightColor)
                    )
                }
            }
        } else {
            background = roundedBorderDrawable()
            foreground = roundedPressForeground()
        }
    }

    val textView = textView {
        isClickable = false
        isFocusable = false
        background = null
        setTextColor(if (altStyle) theme.altKeyTextColor else theme.keyTextColor)
    }

    val imageView = imageView {
        isClickable = false
        isFocusable = false
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
    }

    private fun detachContentViews() {
        if (textView.parent === this) removeView(textView)
        if (imageView.parent === this) removeView(imageView)
    }

    fun setText(id: Int) {
        setText(context.getText(id))
    }

    fun setText(text: CharSequence) {
        textView.text = text
        detachContentViews()
        add(textView, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun setIcon(@DrawableRes icon: Int) {
        imageView.imageResource = icon
        imageView.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        detachContentViews()
        add(imageView, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun setThemedIcon(slot: String, @DrawableRes fallback: Int) {
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(slot)
        if (iconInfo != null) {
            detachContentViews()
            add(imageView, lParams(wrapContent, wrapContent, gravityCenter))
            imageView.setImageDrawable(iconInfo.drawable)
            if (iconInfo.tintWithTheme) {
                imageView.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
            } else {
                imageView.imageTintList = null
                imageView.drawable?.setTintList(null)
            }
        } else {
            setIcon(fallback)
        }
    }

    fun enableActivatedState() {
        textView.setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_activated),
                    intArrayOf(android.R.attr.state_enabled)
                ),
                intArrayOf(
                    theme.genericActiveForegroundColor,
                    if (altStyle) theme.altKeyTextColor else theme.keyTextColor
                )
            )
        )
        imageView.imageTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_activated),
                intArrayOf(android.R.attr.state_enabled)
            ),
            intArrayOf(
                theme.genericActiveForegroundColor,
                theme.altKeyTextColor
            )
        )
        background = if (bordered) {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_activated),
                    shadowedKeyBackgroundDrawable(
                        theme.genericActiveBackgroundColor, theme.keyShadowColor,
                        radius, shadowWidth, hInset, vInset
                    )
                )
                addState(
                    intArrayOf(android.R.attr.state_enabled),
                    shadowedKeyBackgroundDrawable(
                        theme.keyBackgroundColor, theme.keyShadowColor,
                        radius, shadowWidth, hInset, vInset
                    )
                )
            }
        } else {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_activated),
                    roundedBorderDrawable(theme.genericActiveBackgroundColor)
                )
                addState(
                    intArrayOf(android.R.attr.state_enabled),
                    roundedBorderDrawable()
                )
            }
        }
    }
}
