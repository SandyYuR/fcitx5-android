/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.settingsColor
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledDrawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

class NewThemeEntryUi(override val ctx: Context) : Ui {
    val text = textView {
        setText(R.string.new_theme)
        setTextColor(ctx.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
        gravity = android.view.Gravity.CENTER
        maxLines = 2
    }

    val icon = imageView {
        imageDrawable = ctx.drawable(R.drawable.ic_baseline_plus_24)!!.apply {
            setTint(ctx.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
        }
    }

    override val root = constraintLayout {
        foreground = styledDrawable(android.R.attr.selectableItemBackground)
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(ctx.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer))
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        elevation = 0f
        add(icon, lParams(dp(24), dp(24)) {
            topOfParent()
            centerHorizontally()
            above(text, dp(4))
            verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
        })
        add(text, lParams(wrapContent, wrapContent) {
            below(icon)
            centerHorizontally()
            constrainedWidth = true
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomOfParent()
        })
    }
}
