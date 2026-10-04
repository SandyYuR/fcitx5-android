/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.style.AbsoluteSizeSpan
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CustomTypefaceSpan
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    private val setupTextView: TextView.() -> Unit,
    private val highlightRadius: Float
) : Ui {

    /**
     * [setupTextView] 最近一次应用到的字体数据版本号
     * （见 [FontProviders.fontGeneration]）。
     *
     * 浮动候选窗的主文字字号取自「候选窗口」偏好项、字体取自字体设定的 `cand_font`，
     * 两者此前都只在 [root] 构造时应用一次。ViewHolder 被回收复用后，改完字体设定
     * 仍旧按旧字体渲染 —— 与主候选栏同类的问题。字号那半靠偏好项变更重建整个
     * [CandidatesView] 兜住，字体这半必须靠版本号比对。
     */
    private var appliedFontGeneration = FontProviders.fontGeneration

    override val root = textView {
        setupTextView(this)
    }

    /**
     * 字体数据版本号前进时重跑 [setupTextView]（字号 + `cand_font` 字体 + 内边距）。
     *
     * 直接重跑外部 lambda 而不是自己解析：字号与字体分属两套来源，重跑能保证与
     * 首次构造完全同口径。无变化时只做一次 [FontProviders.fontGeneration] 读。
     */
    fun refreshConfiguredFontIfNeeded() {
        val generation = FontProviders.fontGeneration
        if (generation == appliedFontGeneration) return
        appliedFontGeneration = generation
        setupTextView(root)
        root.requestLayout()
        root.invalidate()
    }

    private val highlightDrawable = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
        cornerRadius = highlightRadius
    }

    fun update(candidate: CandidateWord, active: Boolean) {
        refreshConfiguredFontIfNeeded()
        val labelFg = if (active) theme.genericActiveForegroundColor else theme.candidateLabelColor
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        val commentTypeface = FontProviders.resolveCommentTypeface(root.typeface)
        val commentSizePx = FontProviders.commentFontSizePx(root.context)
        root.text = buildSpannedString {
            color(labelFg) {
                append(candidate.label)
            }
            color(fg) {
                append(candidate.text)
            }
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                inSpans(CustomTypefaceSpan(commentTypeface), AbsoluteSizeSpan(commentSizePx, false)) {
                    color(altFg) {
                        append(candidate.comment)
                    }
                }
            }
        }
        root.background = if (active) highlightDrawable else null
    }
}
