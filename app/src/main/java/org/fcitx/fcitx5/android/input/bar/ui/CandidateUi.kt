/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add

class CandidateUi(override val ctx: Context, theme: Theme, private val horizontalView: View) : Ui {

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.INVISIBLE
    }

    override val root = ctx.constraintLayout {
        add(expandButton, lParams(dp(40)) {
            centerVertically()
            endOfParent()
        })
        add(horizontalView, lParams {
            centerVertically()
            startOfParent()
            before(expandButton)
        })
    }

    /**
     * 展开按钮的边长与图标跟随「工具栏大小」。
     *
     * 原来写死 40dp/24dp：工具栏放大到 200% 后，候选项字变大了，最右侧的展开箭头却还是
     * 原来那么小，视觉上明显不匹配、点击热区也偏小。
     */
    fun applyToolbarScale() {
        val scale = KawaiiBarComponent.resolveScale()
        val size = KawaiiBarComponent.resolveHeightPx(ctx)
        expandButton.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
            width = size
            height = size
        }
        // 正方形按钮（边长 = 栏高）：图标上限跟着栏高走，栏调高它才长得大。
        expandButton.applyToolbarScale(scale, KawaiiBarComponent.resolveHeightDp())
    }
}
