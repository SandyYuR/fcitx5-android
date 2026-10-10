/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.Spanned
import android.text.SpannedString
import android.text.style.DynamicDrawableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ContinuousCornerGeometry
import org.fcitx.fcitx5.android.input.bar.PreeditShapeDrawable
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout

open class PreeditUi(
    override val ctx: Context,
    private val theme: Theme,
    private val setupTextView: (TextView.() -> Unit)? = null,
    private val cardTopRadiusProvider: () -> Float = { 0f },
    private val preeditRadiusProvider: () -> Float = { 0f }
) : Ui {

    class CursorSpan(ctx: Context, @ColorInt color: Int, metrics: Paint.FontMetricsInt) :
        DynamicDrawableSpan() {
        private val drawable = ShapeDrawable(RectShape()).apply {
            paint.color = color
            setBounds(0, metrics.ascent, ctx.dp(1), metrics.bottom)
        }

        override fun getDrawable() = drawable
    }

    /**
     * 光标条的绘制度量取自 [upView] 的字体度量，字号一变就必须重建，
     * 否则改完「字体设定」里的预编辑字号后，光标条还按旧字号的高度画。
     */
    private var cursorSpanCache: CursorSpan? = null

    private val cursorSpan: CursorSpan
        get() = cursorSpanCache
            ?: CursorSpan(ctx, theme.keyTextColor, upView.paint.fontMetricsInt)
                .also { cursorSpanCache = it }

    /**
     * [applyConfiguredFont] 最近一次应用到的字体数据版本号
     * （见 [org.fcitx.fcitx5.android.input.font.FontProviders.fontGeneration]）。
     *
     * 预编辑视图只在构造时读一次 `preedit_font` 的字号与字体，而 `PreeditUi` 实例会长期
     * 存活（`PreeditComponent.ui` 与浮动候选窗的 `CandidatesView` 各持一个），所以在
     * 「字体设定」保存后，不改字号就只能等下一个输入会话重建视图 —— 与候选栏同类的问题。
     */
    private var appliedFontGeneration = FontProviders.fontGeneration

    private fun applyConfiguredFont(view: TextView) {
        // Apply preedit font settings after external setup to avoid being overridden
        // by candidate window style hooks.
        val fontSize = FontProviders.getFontSize("preedit_font", 16f)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize)
        view.typeface = FontProviders.resolveTypeface("preedit_font", view.typeface)
    }

    private fun createTextView() = textView {
        setTextColor(theme.keyTextColor)
        setupTextView?.invoke(this)
        applyConfiguredFont(this)
    }

    /**
     * 字体数据版本号前进时重读 `preedit_font` 的字号与字体。
     *
     * 挂在 [update] 上（每次输入面板事件都会走），因为预编辑只在合成中可见：用户改完
     * 字体设定回到键盘、打出第一个编码时，这一帧就会用上新字号，不会先按旧字号画一次。
     * 无变化时只做一次 [FontProviders.fontGeneration] 读，开销可忽略。
     */
    private fun refreshConfiguredFontIfNeeded() {
        val generation = FontProviders.fontGeneration
        if (generation == appliedFontGeneration) return
        appliedFontGeneration = generation
        applyConfiguredFont(upView)
        applyConfiguredFont(downView)
        // 度量变了，光标条重建；两个视图都要重新测高。
        cursorSpanCache = null
        upView.requestLayout()
        downView.requestLayout()
    }

    private val upView = createTextView()

    private val downView = createTextView()

    private val content = verticalLayout {
        add(upView, lParams())
        add(downView, lParams())
    }

    /**
     * 承载预编辑文字的容器，也是编码区胶囊背景的绘制者。
     *
     * 视图高度等于文字行高（与原实现一致），胶囊主体左缘相对本视图左缘右移
     * [ContinuousCornerGeometry.preeditBodyOffset]，底部两侧的反向圆角在**这段高度内部**
     * 让出，弧线收在键盘卡片上圆角与顶边的切点处。
     * 本视图左缘通常不落在卡片左缘（键盘侧边距、单手模式留白），这段差值由父布局
     * 的 padding 决定，每次测量/绘制现取，因此运行期改变留白无需重建。
     */
    private class ContentWrapper(
        context: Context,
        private val content: View,
        private val cardTopRadiusProvider: () -> Float,
        private val preeditRadiusProvider: () -> Float
    ) : FrameLayout(context) {

        /** 本视图左缘相对键盘卡片左缘的内缩（等于父布局左 padding）。 */
        private fun cardInset(): Float = (parent as? View)?.paddingLeft?.toFloat() ?: 0f

        /** 胶囊主体左缘相对本视图左缘的位移。 */
        private fun bodyOffset(): Float = ContinuousCornerGeometry.preeditBodyOffset(
            cardTopRadiusProvider(),
            cardInset(),
            preeditRadiusProvider()
        )

        /** 反向圆角的外扩宽度（水平半径），与 [ContinuousCornerGeometry.addPreeditPath] 一致。 */
        private fun flareWidth(): Float = preeditRadiusProvider().coerceAtLeast(0f)

        init {
            clipChildren = false
            clipToPadding = false
            addView(content)
        }

        fun configureDecoration(@ColorInt color: Int) {
            background = PreeditShapeDrawable(
                color = color,
                insetProvider = ::cardInset,
                cardTopRadiusProvider = cardTopRadiusProvider,
                preeditRadiusProvider = preeditRadiusProvider
            )
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureChild(content, widthMeasureSpec, heightMeasureSpec)
            // 反向圆角从原有高度里让出，不额外加高：视图高度仍等于文字行高，
            // 否则底对齐的整块（连同文字）会被顶高一个圆角半径。
            val height = content.measuredHeight
            val desiredWidth = bodyOffset() + content.measuredWidth + flareWidth()
            setMeasuredDimension(
                resolveSize(desiredWidth.toInt(), widthMeasureSpec),
                resolveSize(height, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val offset = bodyOffset().toInt()
            content.layout(offset, 0, offset + content.measuredWidth, content.measuredHeight)
        }
    }

    private val wrapper = ContentWrapper(ctx, content, cardTopRadiusProvider, preeditRadiusProvider)

    override val root: View = FrameLayout(ctx).apply {
        clipChildren = false
        clipToPadding = false
        addView(wrapper, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    var visible = false
        private set

    /**
     * 胶囊在**根视图坐标系**里可见的宽度（含两侧反向圆角与外扩出去的部分），
     * 即键盘触摸区域需要覆盖的范围。根视图左缘即键盘卡片左缘。
     */
    val actualContentWidth: Int
        get() {
            if (!visible || root.visibility != View.VISIBLE) return 0
            return (wrapper.left + wrapper.width).coerceAtMost(root.width)
        }

    fun configureDecoration(@ColorInt color: Int) {
        wrapper.configureDecoration(color)
    }

    private fun updateTextView(view: TextView, str: CharSequence, visible: Boolean) {
        view.text = str
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(inputPanel: FcitxEvent.InputPanelEvent.Data) {
        refreshConfiguredFontIfNeeded()
        val activeBkg = theme.genericActiveBackgroundColor
        val upString: SpannedString
        val upCursor: Int
        if (inputPanel.auxUp.isEmpty()) {
            upString = inputPanel.preedit.toSpannedString(activeBkg)
            upCursor = inputPanel.preedit.cursor
        } else {
            upString = buildSpannedString {
                append(inputPanel.auxUp.toSpannedString(activeBkg))
                append(inputPanel.preedit.toSpannedString(activeBkg))
            }
            upCursor = inputPanel.preedit.cursor.let {
                if (it < 0) it
                else inputPanel.auxUp.length + it
            }
        }
        val downString = inputPanel.auxDown.toSpannedString(activeBkg)
        val hasUp = upString.isNotEmpty()
        val hasDown = downString.isNotEmpty()
        visible = hasUp || hasDown
        if (!visible) {
            updateTextView(upView, "", false)
            updateTextView(downView, "", false)
            return
        }
        val upStringWithCursor = if (upCursor < 0 || upCursor == upString.length) {
            upString
        } else buildSpannedString {
            if (upCursor > 0) append(upString, 0, upCursor)
            append('|')
            setSpan(cursorSpan, upCursor, upCursor + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(upString, upCursor, upString.length)
        }
        updateTextView(upView, upStringWithCursor, hasUp)
        updateTextView(downView, downString, hasDown)
    }
}
