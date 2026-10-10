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
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import kotlin.math.abs
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CustomTypefaceSpan
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp
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

    /** 已应用到 [text] 的字号（sp）；用于避免无谓的 setTextSize/重排。 */
    private var appliedTextSizeSp = FontProviders.DEFAULT_CANDIDATE_FONT_SIZE

    /**
     * 已真正渲染进富文本 span 的「注释字体 + 注释字号」。
     *
     * 注释字号是在 [renderCandidate] 里现读的，而 [CandidateViewHolder.update] 在候选内容
     * 未变时会跳过重渲染，所以「字体设定」保存后的刷新必须拿这个签名比对，否则字号/字体改了
     * 但候选文字没变时 span 会留在旧值上。
     */
    private var renderedCommentSignature: Pair<Typeface?, Int>? = null

    private fun resolveCandidateFontSize(): Float =
        FontProviders.getFontSize("cand_font", FontProviders.DEFAULT_CANDIDATE_FONT_SIZE)

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        // Use configured font size with fallback to default (20f).
        // 字号**只**由「字体设定」决定，不跟随「工具栏大小」：那是用户为"字多大"专门准备的
        // 旋钮，工具栏百分比管的是栏本身（高度/图标/按钮）。曾让两者联动，结果是只想加高
        // 工具栏的人被迫接受更大的字，还得回头调字体设定抵消——详见 ToolbarMetrics 类注释。
        val size = resolveCandidateFontSize()
        appliedTextSizeSp = size
        textSize = size
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
    private val content = view(::CandidateOverflowViewport) {
        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    private val indexBadge = view(::TextView) {
        includeFontPadding = false
        textSize = 10f
        gravity = Gravity.TOP or Gravity.START
        setTextColor(theme.candidateLabelColor)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    /**
     * Enable natural-width text and horizontal overflow scrolling for the horizontal candidate bar.
     * Expanded candidate windows intentionally keep the proportional text behavior.
     */
    fun setHorizontalOverflowEnabled(enabled: Boolean) {
        if (enabled) enableHorizontalOverflow() else disableHorizontalOverflow()
    }

    fun enableHorizontalOverflow() {
        text.scaleMode = AutoScaleTextView.Mode.None
        content.enableOverflow()
        candidateRoot.scrollableViewport = content
    }

    fun disableHorizontalOverflow() {
        text.scaleMode = AutoScaleTextView.Mode.Proportional
        content.disableOverflow()
        candidateRoot.scrollableViewport = null
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
    private var indexBadgeText: String? = null
    private var indexBadgePosition = CandidateIndexBadgePosition.TopLeft

    private companion object {
        const val INDEX_BADGE_HORIZONTAL_MARGIN_DP = 2
    }

    /**
     * @param text 角标文本；`null` 表示隐藏。调用方（adapter）负责按设置把栏内序号或
     * 引擎选词标签算好再传进来，这里只管显示。
     */
    fun setIndexBadge(text: String?, position: CandidateIndexBadgePosition) {
        if (indexBadgeText == text && indexBadgePosition == position) return
        indexBadgeText = text
        indexBadgePosition = position
        indexBadge.text = text.orEmpty()
        indexBadge.visibility = if (text == null) View.GONE else View.VISIBLE
        applyIndexBadgeLayout()
        refreshIndexBadgeColor()
    }

    private fun applyIndexBadgeLayout() {
        val params = indexBadge.layoutParams as? FrameLayout.LayoutParams ?: return
        val horizontalMargin = ctx.dp(INDEX_BADGE_HORIZONTAL_MARGIN_DP)
        val isLeft = indexBadgePosition == CandidateIndexBadgePosition.TopLeft ||
            indexBadgePosition == CandidateIndexBadgePosition.BottomLeft
        params.gravity = when (indexBadgePosition) {
            CandidateIndexBadgePosition.TopLeft -> Gravity.TOP or Gravity.START
            CandidateIndexBadgePosition.TopRight -> Gravity.TOP or Gravity.END
            CandidateIndexBadgePosition.BottomRight -> Gravity.BOTTOM or Gravity.END
            CandidateIndexBadgePosition.BottomLeft -> Gravity.BOTTOM or Gravity.START
        }
        params.marginStart = if (isLeft) horizontalMargin else 0
        params.marginEnd = if (!isLeft) horizontalMargin else 0
        params.topMargin = 0
        params.bottomMargin = 0
        indexBadge.layoutParams = params
    }

    private fun refreshIndexBadgeColor() {
        indexBadge.setTextColor(
            if (active) {
                ColorUtils.setAlphaComponent(theme.genericActiveForegroundColor, 190)
            } else {
                theme.candidateLabelColor
            }
        )
    }

    fun applyConfiguredTypeface(fontOverride: Typeface? = font) {
        // Priority: explicit override > constructor font > cand_font > font > current/system default
        val resolved = fontOverride ?: FontProviders.resolveTypeface("cand_font", text.typeface)
        if (text.typeface !== resolved) {
            text.typeface = resolved
        }
    }

    /**
     * 重新读取「字体设定」里的候选字号（`cand_font`）。
     *
     * 字号此前只在构造 lambda 里求值一次，于是 ViewHolder 被复用（未重建）时改字号永远不生效
     * ——那正是「改完字体设定要强杀重启、且只有一部分候选换掉」的成因。刷新入口必须能改字号，
     * 而不是只改 typeface。
     *
     * 与 [applyConfiguredTypeface] 成对调用；调用方在两个 adapter 里按字体数据版本号判定。
     */
    fun applyConfiguredTextSize() {
        val size = resolveCandidateFontSize()
        if (size != appliedTextSizeSp) {
            appliedTextSizeSp = size
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            text.requestLayout()
            text.invalidate()
        }
    }

    /**
     * 一次性重读并应用候选字体与字号，并在字号/注释字体变化时强制重渲染。
     *
     * 之所以要在这里补重渲染：注释部分走的是 [renderCandidate] 里的 span（注释字体 + 注释
     * 字号），而 [CandidateViewHolder.update] 只在候选内容变化时才重渲染——改字号时候选内容
     * 通常一个字符都没变，光调 typeface/setTextSize 会让旧 span 留在原地。
     */
    fun refreshConfiguredFont(fontOverride: Typeface? = font) {
        applyConfiguredTypeface(fontOverride)
        applyConfiguredTextSize()
        val commentTypeface = cachedCommentFont ?: FontProviders.resolveCommentTypeface(text.typeface)
        val signature = commentTypeface to FontProviders.commentFontSizePx(ctx)
        if (signature != renderedCommentSignature) {
            renderCandidate()
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
        refreshIndexBadgeColor()
        text.background = null
        // 高亮画在内层 [content] 上（紧贴文字），按压反馈仍铺满 [root] 整格。
        content.background = if (this.active) activeBackground else null
        root.background = normalBackground
    }

    private val candidateRoot = view(::ScrollableCandidateGestureView) {
        background = normalBackground
        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false
        add(content, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
        add(indexBadge, lParams(wrapContent, wrapContent) {
            gravity = Gravity.TOP or Gravity.START
            marginStart = ctx.dp(INDEX_BADGE_HORIZONTAL_MARGIN_DP)
        })
    }

    override val root: CustomGestureView = candidateRoot

    fun updateCandidate(candidate: CandidateWord) {
        if (this.candidate != candidate) {
            content.followTextUpdate()
        }
        this.candidate = candidate
        renderCandidate()
    }

    private fun renderCandidate() {
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        val commentTypeface = cachedCommentFont ?: FontProviders.resolveCommentTypeface(text.typeface)
        val commentSizePx = FontProviders.commentFontSizePx(ctx)
        renderedCommentSignature = commentTypeface to commentSizePx
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

private class CandidateOverflowViewport(context: Context) : FrameLayout(context) {
    private var overflowEnabled = false
    private var followEnd = true
    private var previousContentWidth = -1
    private var previousTextWidth = -1

    private val contentWidth: Int
        get() = (width - paddingLeft - paddingRight).coerceAtLeast(0)

    private val maxScroll: Float
        get() = ((getChildAt(0)?.width ?: 0) - contentWidth).coerceAtLeast(0).toFloat()

    val hasOverflow: Boolean
        get() = overflowEnabled && maxScroll > 0f

    fun enableOverflow() {
        if (overflowEnabled) return
        overflowEnabled = true
        followTextUpdate()
    }

    fun disableOverflow() {
        if (!overflowEnabled) return
        overflowEnabled = false
        followEnd = true
        previousContentWidth = -1
        previousTextWidth = -1
        getChildAt(0)?.translationX = 0f
        requestLayout()
    }

    fun followTextUpdate() {
        if (!overflowEnabled) return
        followEnd = true
        requestLayout()
    }

    fun scrollByDistance(distance: Float) {
        val child = getChildAt(0) ?: return
        followEnd = false
        child.translationX = -(-child.translationX + distance).coerceIn(0f, maxScroll)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!overflowEnabled) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // Measure the entire text, then bound only the viewport to the candidate's slot.
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            heightMeasureSpec,
        )
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec), measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!overflowEnabled) return
        val child = getChildAt(0) ?: return
        // Anchor overflowing text at the physical left edge before applying the scroll offset.
        // Short text retains FrameLayout's existing centering behavior.
        if (hasOverflow) {
            child.layout(paddingLeft, child.top, paddingLeft + child.measuredWidth, child.bottom)
        }
        val resized = previousContentWidth != contentWidth || previousTextWidth != child.width
        child.translationX = if (followEnd || resized) {
            -maxScroll
        } else {
            child.translationX.coerceIn(-maxScroll, 0f)
        }
        followEnd = false
        previousContentWidth = contentWidth
        previousTextWidth = child.width
    }
}

private class ScrollableCandidateGestureView(context: Context) : CustomGestureView(context) {

    var scrollableViewport: CandidateOverflowViewport? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var horizontalScrolling = false
    private var longPressHandled = false
    private var verticalGesture = false

    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        longPressHandled = handled
        return handled
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val viewport = scrollableViewport ?: return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                horizontalScrolling = false
                longPressHandled = false
                verticalGesture = false
                if (isEnabled && viewport.hasOverflow) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(remainingIndex)
                    downX = event.getX(remainingIndex)
                    downY = event.getY(remainingIndex)
                    lastX = downX
                }
                if (horizontalScrolling) return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return horizontalScrolling
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val deltaX = x - downX
                val deltaY = y - downY
                if (isEnabled && !horizontalScrolling && !longPressHandled && !verticalGesture &&
                    viewport.hasOverflow
                ) {
                    if (abs(deltaY) > touchSlop && abs(deltaY) >= abs(deltaX)) {
                        verticalGesture = true
                        parent?.requestDisallowInterceptTouchEvent(false)
                    } else if (abs(deltaX) > touchSlop && abs(deltaX) > abs(deltaY)) {
                        // Cancel both the external long-press listener and CustomGestureView's
                        // pending click/timer before owning the rest of this touch stream.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        horizontalScrolling = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (horizontalScrolling) {
                    viewport.scrollByDistance(lastX - x)
                    lastX = x
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                if (horizontalScrolling) {
                    horizontalScrolling = false
                    cancelGestures()
                    return true
                }
            }
        }
        return horizontalScrolling || super.dispatchTouchEvent(event)
    }
}
