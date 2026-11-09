/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.View
import com.google.android.material.textfield.TextInputEditText
import org.fcitx.fcitx5.android.utils.settingsColor
import kotlin.math.roundToInt

/** Material editor driven by the panel's local connection, without taking system window focus. */
internal class JsAppEditText(context: Context) : TextInputEditText(context) {
    private var screenOn = true
    private var attached = false
    private var cursorOn = false
    private var blink: Runnable? = null
    private val cursorPadding = Rect()
    private val previousCursorBounds = Rect()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.settingsColor(androidx.appcompat.R.attr.colorPrimary)
    }

    init {
        showSoftInputOnFocus = false
        isCursorVisible = false
        blink = Runnable {
            if (isLocalCursorActive) {
                cursorOn = !cursorOn
                invalidate()
                blink?.let { postDelayed(it, 500) }
            } else stopCursorBlink()
        }
    }

    var ownsLocalInput = false
        set(value) {
            field = value
            // A non-focusable panel cannot rely on the platform's private Editor.Blink.
            // Keep native text editing/selection, but draw one local caret independently.
            isCursorVisible = false
            restartCursorBlink()
        }

    internal val isLocalCursorActive: Boolean
        get() = ownsLocalInput && keyListener != null && isEnabled && isFocused && attached &&
            isShown && windowVisibility == View.VISIBLE && screenOn && selectionStart >= 0 &&
            selectionStart == selectionEnd

    private fun stopCursorBlink() {
        blink?.let(::removeCallbacks)
        cursorOn = false
        invalidate()
    }

    private fun restartCursorBlink() {
        stopCursorBlink()
        if (isLocalCursorActive) {
            cursorOn = true
            invalidate()
            blink?.let { postDelayed(it, 500) }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        // Deliver the real focus state; the original application remains the IME target.
        super.onWindowFocusChanged(hasWindowFocus)
        restartCursorBlink()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        restartCursorBlink()
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        restartCursorBlink()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        restartCursorBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        restartCursorBlink()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopCursorBlink()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        restartCursorBlink()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ownsLocalInput && isShown && isEnabled && !isFocused) requestFocus()
        restartCursorBlink()
    }

    override fun onScreenStateChanged(screenState: Int) {
        screenOn = screenState == View.SCREEN_STATE_ON
        super.onScreenStateChanged(screenState)
        restartCursorBlink()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        restartCursorBlink()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!cursorOn || !isLocalCursorActive) return
        val textLayout = layout ?: return
        val offset = selectionStart.coerceIn(0, text?.length ?: 0)
        val line = textLayout.getLineForOffset(offset)
        val horizontal = textLayout.getPrimaryHorizontal(offset).coerceIn(0f, textLayout.width.toFloat())
        val x = totalPaddingLeft + horizontal
        val top = totalPaddingTop + textLayout.getLineTop(line)
        val bottom = totalPaddingTop + textLayout.getLineBottom(line)
        val saved = canvas.save()
        // TextView's canvas uses content coordinates, including its scroll offsets.
        canvas.clipRect(scrollX + compoundPaddingLeft, scrollY + extendedPaddingTop,
            scrollX + width - compoundPaddingRight, scrollY + height - extendedPaddingBottom)
        val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable else null
        if (drawable != null) {
            drawable.getPadding(cursorPadding)
            drawable.copyBounds(previousCursorBounds)
            val left = x.roundToInt() - cursorPadding.left
            drawable.setBounds(left, top, left + drawable.intrinsicWidth.coerceAtLeast(1), bottom)
            drawable.draw(canvas)
            drawable.bounds = previousCursorBounds
        } else {
            val cursorWidth = (resources.displayMetrics.density * 2).coerceAtLeast(1f)
            canvas.drawRect(x, top.toFloat(), x + cursorWidth, bottom.toFloat(), cursorPaint)
        }
        canvas.restoreToCount(saved)
    }
}
