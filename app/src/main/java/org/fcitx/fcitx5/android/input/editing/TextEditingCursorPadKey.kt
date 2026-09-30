/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.math.abs
import kotlin.math.sign

/**
 * Foxy 文本编辑页的光标触控板。
 *
 * 它是编辑器专用的 key view：移动按步数发方向键，长按后移动则附带 Shift，
 * 因此不把“光标板”硬塞进普通键盘的 KeyDef/KeyView 渲染链路。
 */
class TextEditingCursorPadKey(
    context: Context,
    private val theme: Theme,
    stepDp: Int,
    longPressDelayMs: Long,
    promptMove: String,
    promptLongPress: String,
    promptSelecting: String,
    promptReleaseSelection: String
) : View(context) {

    fun interface DirectionListener {
        fun onDirection(keyCode: Int, selecting: Boolean)
    }

    var directionListener: DirectionListener? = null
    var selectionModeListener: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val stepPx = dp(stepDp.coerceIn(4, 80)).toFloat().coerceAtLeast(1f)
    private val longPressDelay = longPressDelayMs.coerceAtLeast(100L)
    private val promptMoveText = promptMove.ifBlank {
        context.getString(R.string.text_editing_touchpad_move_cursor)
    }
    private val promptLongPressText = promptLongPress.ifBlank {
        context.getString(R.string.text_editing_touchpad_long_press)
    }
    private val promptSelectingText = promptSelecting.ifBlank {
        context.getString(R.string.text_editing_touchpad_selecting)
    }
    private val promptReleaseSelectionText = promptReleaseSelection.ifBlank {
        context.getString(R.string.text_editing_touchpad_release_selection)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val longPressAction = Runnable {
        if (!moved && pressed) {
            selecting = true
            selectionModeListener?.invoke(true)
            InputFeedbacks.hapticFeedback(this, longPress = true)
            invalidate()
        }
    }

    private var pressed = false
    private var moved = false
    private var selecting = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pendingX = 0f
    private var pendingY = 0f

    init {
        isClickable = true
        isFocusable = false
        isFocusableInTouchMode = false
        setBackgroundColor(theme.keyboardColor)
        contentDescription = context.getString(R.string.text_editing_touchpad_description)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val inset = dp(8).toFloat()
        val radius = dp(6).toFloat()
        val active = selecting
        paint.style = Paint.Style.FILL
        paint.color = if (active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
        paint.alpha = 255
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius,
            radius,
            paint
        )

        paint.textAlign = Paint.Align.CENTER
        paint.color = if (active) theme.genericActiveForegroundColor else theme.keyTextColor
        paint.alpha = if (active) 190 else 165
        paint.textSize = dp(15).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val primary = if (active) promptSelectingText else promptMoveText
        canvas.drawText(primary, centerX, centerY - dp(6), paint)
        paint.alpha = if (active) 150 else 110
        paint.textSize = dp(12).toFloat()
        val secondary = if (active) promptReleaseSelectionText else promptLongPressText
        canvas.drawText(secondary, centerX, centerY + dp(18), paint)
        paint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                moved = false
                selecting = false
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                pendingX = 0f
                pendingY = 0f
                handler.removeCallbacks(longPressAction)
                handler.postDelayed(longPressAction, longPressDelay)
                InputFeedbacks.hapticFeedback(this)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                if (!moved &&
                    totalDx * totalDx + totalDy * totalDy > touchSlop * touchSlop
                ) {
                    moved = true
                    handler.removeCallbacks(longPressAction)
                }
                pendingX += dx
                pendingY += dy
                dispatchDirections()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishTouch()
                return true
            }
        }
        return true
    }

    private fun dispatchDirections() {
        while (abs(pendingX) >= stepPx || abs(pendingY) >= stepPx) {
            val horizontal = abs(pendingX) >= abs(pendingY)
            val keyCode = if (horizontal) {
                if (pendingX > 0) android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                else android.view.KeyEvent.KEYCODE_DPAD_LEFT
            } else {
                if (pendingY > 0) android.view.KeyEvent.KEYCODE_DPAD_DOWN
                else android.view.KeyEvent.KEYCODE_DPAD_UP
            }
            if (horizontal) pendingX -= sign(pendingX) * stepPx
            else pendingY -= sign(pendingY) * stepPx
            directionListener?.onDirection(keyCode, selecting)
            InputFeedbacks.hapticFeedback(this)
        }
    }

    private fun finishTouch() {
        handler.removeCallbacks(longPressAction)
        pressed = false
        if (selecting) {
            selecting = false
            selectionModeListener?.invoke(false)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPressAction)
        pressed = false
        selecting = false
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
