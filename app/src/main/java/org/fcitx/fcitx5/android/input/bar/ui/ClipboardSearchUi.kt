/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView

/**
 * 剪贴板历史搜索输入框（工具栏行内展示）。
 *
 * 关闭入口统一使用工具栏最左侧的返回箭头（IdleUi.menuButton 在 Search
 * 状态下即显示该箭头并负责关闭搜索），本视图只保留输入框本身。
 */
class ClipboardSearchUi(override val ctx: Context, theme: Theme) : Ui {
    private val queryView = textView {
        gravity = Gravity.CENTER_VERTICAL
        hint = ctx.getString(R.string.search)
        setHintTextColor(theme.altKeyTextColor and 0x00ffffff or (0x88 shl 24))
        setTextColor(theme.keyTextColor)
        textSize = 15f
        isSingleLine = true
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(theme.keyBackgroundColor)
        }
    }

    override val root = horizontalLayout {
        gravity = Gravity.CENTER_VERTICAL
        add(queryView, LinearLayout.LayoutParams(matchParent, dp(34)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
    }

    fun updateQuery(committed: String, preedit: String) {
        queryView.text = SpannableStringBuilder(committed).apply {
            if (preedit.isNotEmpty()) {
                val start = length
                append(preedit)
                setSpan(UnderlineSpan(), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
