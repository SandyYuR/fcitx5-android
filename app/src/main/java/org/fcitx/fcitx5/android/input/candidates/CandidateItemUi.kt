/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.style.AbsoluteSizeSpan
import android.widget.FrameLayout
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CustomTypefaceSpan
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class CandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    // Optional: external font for batch setting (avoids repeated FontProviders access)
    private val font: Typeface? = null,
    commentFont: Typeface? = null,
) : Ui {
    private var cachedCommentFont: Typeface? = commentFont

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        // Use configured font size with fallback to default (20f).
        // 字号**只**由「字体设定」决定，不跟随「工具栏大小」：那是用户为"字多大"专门准备的
        // 旋钮，工具栏百分比管的是栏本身（高度/图标/按钮）。曾让两者联动，结果是只想加高
        // 工具栏的人被迫接受更大的字，还得回头调字体设定抵消——详见 ToolbarMetrics 类注释。
        textSize = org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize("cand_font", 20f)
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    init {
        applyConfiguredTypeface()
    }

    private val normalBackground = pressHighlightDrawable(theme.keyPressHighlightColor)

    private val activeBackground: Drawable = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
        // 高亮圆角由主题配置项「候选栏高亮圆角半径」控制（dp）。读构造时的值即可：候选项
        // 每次编码更新都会重建，改设置后下一次输入即生效。独立候选窗口不走这里。
        cornerRadius = ThemeManager.prefs.candidateBarHighlightRadius.getValue() *
            ctx.resources.displayMetrics.density
    }

    /**
     * 夹在 [root] 与 [text] 之间的内层容器：**高亮背景画在这一层**。
     *
     * 这正是 boomker/fcitx5-android 的 e2cd625（Decouple horizontal candidate highlight from
     * item spacing）的做法——把高亮背景与 item 的触摸区内边距解耦：
     *
     * - 宽度 `wrapContent`：高亮恒等于「文字 + 左右各 highlightPadding」，因此**候选文字离高亮
     *   边框的间距与候选长短无关**，也不会因为 flexGrow/`layoutMinWidth` 把格子拉宽而变近
     *   （此前高亮画在 [root] 上并按 item 宽度铺满，间距 = 格子内边距 − 内缩量，短候选时会缩到
     *   2dp 左右，观感上就是文字贴着高亮框）。被拉宽的格子里高亮仍紧贴文字居中。
     * - 上下 `matchParent`：高亮的垂直范围 = [root] 的内容区，即格子上下各留一格 root 的垂直
     *   内边距（水平候选项取与左右相同的 4dp），不再上下贴边。
     *
     * 内边距由 [configureHighlightSpacing] 设置，默认全 0（九宫格展开页就是这种情况：高亮铺满
     * 整格，与改造前一致）。
     *
     * ⚠️ 这一层会改变 item 的测量口径（文字可用宽 = 格子宽 − root 内边距 − 本层内边距），
     * [HorizontalCandidateComponent.predictRowOverflow] 的宽度预测必须与此同步，否则候选行会
     * 在「挤一行」与「换行/滚动」之间抖动。
     */
    private val content = view(::FrameLayout) {
        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    /**
     * 配置高亮的间距（单位 px），由水平候选栏的适配器在创建 ViewHolder 时调用。
     *
     * @param outerPadding 格子边缘 ↔ 高亮之间的外间距（左右、上下）
     * @param highlightPadding 高亮边框 ↔ 文字之间的间距（左右；上下固定 0）
     *
     * 调用点与 [HorizontalCandidateComponent.predictRowOverflow] 必须取同一份数值
     * （见 [HorizontalCandidateComponent.itemHorizontalPaddingDp] /
     * [HorizontalCandidateComponent.candidateHighlightPaddingDp]）。
     */
    fun configureHighlightSpacing(outerPadding: Int, highlightPadding: Int) {
        root.setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
        content.setPadding(highlightPadding, 0, highlightPadding, 0)
    }

    private var active = false
    private var candidate = CandidateWord.Empty

    fun applyConfiguredTypeface(fontOverride: Typeface? = font) {
        // Priority: explicit override > constructor font > cand_font > font > current/system default
        val resolved = fontOverride ?: FontProviders.resolveTypeface("cand_font", text.typeface)
        if (text.typeface !== resolved) {
            text.typeface = resolved
        }
    }

    /**
     * Cache the comment typeface for the next render. Falls back to the
     * configured comment_font, then cand_font, then the current view typeface.
     */
    fun applyConfiguredCommentTypeface(commentFontOverride: Typeface? = cachedCommentFont) {
        cachedCommentFont = commentFontOverride ?: FontProviders.resolveCommentTypeface(text.typeface)
    }

    fun setActive(active: Boolean) {
        if (this.active != active) {
            this.active = active
            renderCandidate()
        }
        text.setTextColor(if (this.active) theme.genericActiveForegroundColor else theme.candidateTextColor)
        text.background = null
        // 高亮画在内层 [content] 上（紧贴文字），按压反馈仍铺满 [root] 整格。
        content.background = if (this.active) activeBackground else null
        root.background = normalBackground
    }

    override val root = view(::CustomGestureView) {
        background = normalBackground
        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false
        add(content, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    fun updateCandidate(candidate: CandidateWord) {
        this.candidate = candidate
        renderCandidate()
    }

    private fun renderCandidate() {
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        val commentTypeface = cachedCommentFont ?: FontProviders.resolveCommentTypeface(text.typeface)
        val commentSizePx = FontProviders.commentFontSizePx(ctx)
        text.text = buildSpannedString {
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
    }
}
