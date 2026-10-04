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
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
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
    private val setupTextView: (TextView.() -> Unit)? = null
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

    var visible = false
        private set

    val actualContentWidth: Int
        get() {
            if (!visible || root.visibility != View.VISIBLE) return 0
            val upLayout = upView.layout
            val downLayout = downView.layout
            val upWidth = upLayout?.let { layout ->
                if (upView.visibility == View.VISIBLE && layout.lineCount > 0) {
                    var maxW = 0f
                    for (i in 0 until layout.lineCount) maxW = maxW.coerceAtLeast(layout.getLineWidth(i))
                    maxW.toInt() + upView.paddingLeft + upView.paddingRight
                } else 0
            } ?: 0
            val downWidth = downLayout?.let { layout ->
                if (downView.visibility == View.VISIBLE && layout.lineCount > 0) {
                    var maxW = 0f
                    for (i in 0 until layout.lineCount) maxW = maxW.coerceAtLeast(layout.getLineWidth(i))
                    maxW.toInt() + downView.paddingLeft + downView.paddingRight
                } else 0
            } ?: 0
            return upWidth.coerceAtLeast(downWidth)
        }

    override val root: View = verticalLayout {
        add(upView, lParams())
        add(downView, lParams())
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
