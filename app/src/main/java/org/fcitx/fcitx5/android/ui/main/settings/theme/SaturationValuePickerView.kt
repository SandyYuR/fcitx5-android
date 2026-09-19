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
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.ranges.coerceIn

/**
 * Saturation-Value picker view (square)
 * X-axis: Saturation (0-1, left to right)
 * Y-axis: Value/Brightness (1-0, top to bottom)
 * Background shows the current hue with full saturation/brightness gradient
 */
class SaturationValuePickerView(context: Context) : View(context) {

    var onColorChanged: ((Float, Float) -> Unit)? = null

    private val huePaint = Paint()
    private val saturationPaint = Paint()
    private val valuePaint = Paint()
    private val markerPaint = Paint()
    // 内圈标记笔（随当前颜色变化）：预先建好，避免 onDraw 每帧 new Paint（lint DrawAllocation）。
    private val innerMarkerPaint = Paint().apply { style = Paint.Style.FILL }

    private var hue = 0f
    private var saturation = 1f
    private var value = 1f

    private val rect = RectF()
    private var markerX = 0f
    private var markerY = 0f

    private val dp: (Float) -> Int = { (it * resources.displayMetrics.density + 0.5f).toInt() }

    // 两个渐变只依赖尺寸与 hue，缓存后可避免每帧分配 LinearGradient + IntArray。
    // hue 每帧都可能变（拖动色环），此时只需重建横向那一条。
    private var cachedHue = Float.NaN
    private var cachedWidth = -1
    private var cachedHeight = -1
    private val svStops = intArrayOf(0, 0)

    init {
        markerPaint.color = Color.WHITE
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = dp(3f).toFloat()
    }

    fun setHue(hue: Float) {
        this.hue = hue
        invalidate()
    }

    fun setSaturation(saturation: Float) {
        this.saturation = saturation.coerceIn(0f, 1f)
        updateMarkerPosition()
        invalidate()
    }

    fun setValue(value: Float) {
        this.value = value.coerceIn(0f, 1f)
        updateMarkerPosition()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        cachedWidth = -1
        cachedHeight = -1
        updateMarkerPosition()
    }

    private fun updateShaders() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (w != cachedWidth || hue != cachedHue) {
            cachedWidth = w
            cachedHue = hue
            // 左侧白（饱和度 0）→ 右侧当前色（饱和度 1）。hue 不变时复用同一对颜色。
            svStops[0] = Color.HSVToColor(floatArrayOf(hue, 0f, 1f))
            svStops[1] = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            huePaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f, svStops, null, Shader.TileMode.CLAMP
            )
        }
        if (h != cachedHeight) {
            cachedHeight = h
            valuePaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        updateShaders()
        canvas.drawRect(rect, huePaint)
        canvas.drawRect(rect, valuePaint)

        canvas.drawCircle(markerX, markerY, dp(10f).toFloat(), markerPaint)

        innerMarkerPaint.color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        canvas.drawCircle(markerX, markerY, dp(8f).toFloat(), innerMarkerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x.coerceIn(0f, width.toFloat())
                val y = event.y.coerceIn(0f, height.toFloat())

                saturation = x / width
                value = 1f - (y / height)

                markerX = x
                markerY = y

                invalidate()
                onColorChanged?.invoke(saturation, value)
            }
        }
        return true
    }

    private fun updateMarkerPosition() {
        markerX = saturation * width
        markerY = (1f - value) * height
    }
}
