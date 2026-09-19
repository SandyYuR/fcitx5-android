/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

class HueSliderView(context: Context) : View(context) {

    var onHueChanged: ((Float) -> Unit)? = null

    private var hue = 0f
    private val gradientPaint = Paint()
    private val indicatorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = (2f * resources.displayMetrics.density)
    }

    // 渐变只随高度变化：在 onDraw 里每次 new LinearGradient + intArrayOf 会让每帧都
    // 分配两个对象（lint DrawAllocation）。按高度缓存，尺寸变化时重建一次即可。
    private var gradientHeight = -1
    private val hueStops = intArrayOf(
        0xFFFF0000.toInt(),
        0xFFFFFF00.toInt(),
        0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(),
        0xFF0000FF.toInt(),
        0xFFFF00FF.toInt(),
        0xFFFF0000.toInt()
    )

    fun setHue(hue: Float) {
        this.hue = hue.coerceIn(0f, 360f)
        invalidate()
    }

    private fun updateGradient(h: Int) {
        if (h == gradientHeight) return
        gradientHeight = h
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), hueStops, null, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradient(h)
    }

    override fun onDraw(canvas: Canvas) {
        updateGradient(height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)

        val y = (hue / 360f) * height
        canvas.drawRect(0f, y - 2f, width.toFloat(), y + 2f, indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateHueFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateHueFromTouch(rawY: Float) {
        if (height <= 0) return
        val y = rawY.coerceIn(0f, height.toFloat())
        val newHue = (y / height) * 360f
        if (newHue != hue) {
            hue = newHue
            invalidate()
            onHueChanged?.invoke(hue)
        }
    }
}
