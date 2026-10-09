/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewOutlineProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.gravityCenter

class PopupEntryUi(override val ctx: Context, theme: Theme, keyHeight: Int, radius: Float) : Ui {

    var lastShowTime = -1L

    private val textView = view(::AutoScaleTextView) {
        // Use configured font size with fallback to default (23f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveConfiguredFontSize())
        gravity = gravityCenter
        setTextColor(theme.keyTextColor)
        setFontTypeFace("popup_key_font")
    }

    private fun resolveConfiguredFontSize(): Float =
        FontProviders.getFontSize("popup_key_font", 23f)

    /**
     * [PopupComponent] 把这些气泡放进 `freeEntryUi` 池里循环复用（[PopupComponent.showPopup]
     * 命中池就不重建），所以构造期读一次 `popup_key_font` 的字号/字体是不够的：改完「字体设定」
     * 后，被复用的旧实例会一直按旧字号显示。这里在每次真正显示时按字体数据版本号补一次刷新。
     */
    private var appliedFontGeneration = FontProviders.fontGeneration

    private fun refreshConfiguredFontIfNeeded() {
        val generation = FontProviders.fontGeneration
        if (generation == appliedFontGeneration) return
        appliedFontGeneration = generation
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveConfiguredFontSize())
        textView.setFontTypeFace("popup_key_font")
    }

    override val root = constraintLayout {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.backgroundColor)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        add(textView, lParams(matchParent, keyHeight) {
            topOfParent()
            centerHorizontally()
        })
    }

    fun setText(text: String) {
        refreshConfiguredFontIfNeeded()
        textView.text = text
    }
}
