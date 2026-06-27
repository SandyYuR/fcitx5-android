/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation
    }

    val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    val textView = view(::AutoScaleTextView) {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = gravityCenter
        visibility = View.GONE
    }

    private var theme: Theme? = null
    private var isActive: Boolean = false

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        this.theme = theme
        image.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        textView.setTextColor(theme.altKeyTextColor)
        setIcon(icon)
        setPressHighlightColor(theme.keyPressHighlightColor)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
        add(textView, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun setIcon(@DrawableRes icon: Int) {
        textView.visibility = View.GONE
        image.visibility = View.VISIBLE
        image.imageTintList = theme?.let { ColorStateList.valueOf(it.altKeyTextColor) }
        image.imageResource = icon
    }

    fun setIconFromDrawable(drawable: Drawable?) {
        if (drawable != null) {
            textView.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.imageTintList = null
            image.imageDrawable = drawable
        }
    }

    fun setText(text: String?) {
        if (!text.isNullOrEmpty()) {
            image.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = text
        }
    }

    fun setPressHighlightColor(@ColorInt color: Int) {
        background = if (disableAnimation) {
            circlePressHighlightDrawable(color)
        } else {
            borderlessRippleDrawable(color, dp(20))
        }
    }

    /**
     * Set the active state of this button.
     * When active, the button icon color changes, background remains transparent.
     */
    fun setActive(active: Boolean) {
        if (isActive == active || theme == null) return
        isActive = active
        updateAppearance()
    }

    private fun updateAppearance() {
        val theme = theme ?: return
        val iconColor = if (isActive) theme.accentKeyBackgroundColor else theme.altKeyTextColor

        image.imageTintList = ColorStateList.valueOf(iconColor)
        textView.setTextColor(iconColor)
    }
}
