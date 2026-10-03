/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityVerticalCenter

class TitleUi(override val ctx: Context, theme: Theme) : Ui {

    private val backButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_back_24, theme).apply {
        contentDescription = ctx.getString(R.string.back_to_keyboard)
    }

    private val titleText = textView {
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        setTextColor(theme.altKeyTextColor)
        gravity = gravityVerticalCenter
        textSize = 16f
    }

    private var extension: View? = null

    override val root = constraintLayout {
        add(backButton, lParams(dp(40), dp(40)) {
            topOfParent()
            startOfParent()
            bottomOfParent()
        })
        add(titleText, lParams(wrapContent, dp(40)) {
            topOfParent()
            after(backButton, dp(8))
            bottomOfParent()
        })
    }

    fun setReturnButtonOnClickListener(block: () -> Unit) {
        backButton.setOnClickListener {
            block()
        }
    }

    fun setTitle(title: String) {
        titleText.text = title
    }

    fun addExtension(view: View, showTitle: Boolean) {
        if (extension != null) {
            throw IllegalStateException("TitleBar extension is already present")
        }
        backButton.isVisible = showTitle
        titleText.isVisible = showTitle
        extension = view
        root.run {
            add(view, lParams(matchConstraints, dp(40)) {
                centerVertically()
                if (showTitle) {
                    endOfParent(dp(5))
                } else {
                    centerHorizontally()
                }
            })
        }
    }

    fun removeExtension() {
        extension?.let {
            root.removeView(it)
            extension = null
        }
    }

    /**
     * 标题栏随「工具栏大小」缩放。
     *
     * 标题栏（扩展窗口，如符号面板/剪贴板）与候选栏共用同一条 Kawaii Bar，高度由
     * `InputView` 按偏好设定。若不缩放，工具栏放大后标题会被挤在栏顶一小条里。
     * 内部控件用 `MATCH_PARENT`/wrapContent，改字号与按钮边长即可。
     */
    fun applyToolbarScale() {
        val scale = KawaiiBarComponent.resolveScale()
        val size = KawaiiBarComponent.resolveHeightPx(ctx)
        backButton.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
            width = size
            height = size
        }
        titleText.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
            height = size
        }
        titleText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f * scale)
        // 返回按钮同样是正方形（边长 = 栏高），图标上限跟着栏高走。
        backButton.applyToolbarScale(scale, KawaiiBarComponent.resolveHeightDp())
        extension?.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
            height = size
        }
    }
}
