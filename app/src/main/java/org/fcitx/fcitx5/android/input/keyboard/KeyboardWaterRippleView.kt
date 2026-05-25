/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.RadialGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import kotlin.math.max

class KeyboardWaterRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class RippleState(
        val cx: Float,
        val cy: Float,
        @ColorInt val color: Int,
        val baseAlpha: Int,
        val targetRadius: Float,
        val durationMs: Long,
        val fadeOutMs: Long,
        val startTimeMs: Long
    )

    private val rippleLocation = IntArray(2)
    private val occluderLocation = IntArray(2)
    private val easeInterpolator = DecelerateInterpolator()

    private data class OccluderSnapshot(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cornerRadius: Float,
        val roundRect: Boolean
    )

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val ripples = ArrayDeque<RippleState>()
    private var frameScheduled = false
    private var occluders: List<View> = emptyList()
    private val occluderSnapshots = ArrayList<OccluderSnapshot>(64)
    private var occludersDirty = true
    private val occluderLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        occludersDirty = true
    }

    init {
        isClickable = false
        isFocusable = false
    }

    private fun scheduleFrame() {
        if (!frameScheduled) {
            frameScheduled = true
            postInvalidateOnAnimation()
        }
    }

    fun startRipple(
        centerX: Float,
        centerY: Float,
        @ColorInt color: Int,
        maxRadius: Float,
        alpha: Int = 210,
        durationMs: Long = 520L
    ) {
        val targetRadius = max(maxRadius, max(width, height) * 0.22f)
        val baseAlpha = alpha.coerceIn(0, 255)
        val speedPxPerMs = 0.44f
        val adaptiveDuration = (targetRadius / speedPxPerMs).toLong().coerceIn(480L, 820L)
        val finalDuration = max(durationMs, adaptiveDuration)
        val fadeOutMs = 150L

        ripples.addLast(
            RippleState(
                cx = centerX,
                cy = centerY,
                color = color,
                baseAlpha = baseAlpha,
                targetRadius = targetRadius,
                durationMs = finalDuration,
                fadeOutMs = fadeOutMs,
                startTimeMs = SystemClock.uptimeMillis()
            )
        )

        while (ripples.size > 8) {
            ripples.removeFirst()
        }
        scheduleFrame()
    }

    fun setOccluders(views: List<View>) {
        occluders.forEach { it.removeOnLayoutChangeListener(occluderLayoutListener) }
        occluders = views
        occluders.forEach { it.addOnLayoutChangeListener(occluderLayoutListener) }
        occludersDirty = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        occludersDirty = true
    }

    override fun onDetachedFromWindow() {
        occluders.forEach { it.removeOnLayoutChangeListener(occluderLayoutListener) }
        frameScheduled = false
        ripples.clear()
        occluderSnapshots.clear()
        super.onDetachedFromWindow()
    }

    private fun rebuildOccluderSnapshots() {
        occludersDirty = false
        occluderSnapshots.clear()
        if (occluders.isEmpty() || width <= 0 || height <= 0) return

        getLocationInWindow(rippleLocation)

        occluders.forEach { view ->
            if (view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) return@forEach

            val insetH = (view as? KeyView)?.hMargin ?: 0
            val insetV = (view as? KeyView)?.vMargin ?: 0
            view.getLocationInWindow(occluderLocation)
            val left = (occluderLocation[0] - rippleLocation[0]).toFloat()
            val top = (occluderLocation[1] - rippleLocation[1]).toFloat()
            val l = left + insetH
            val t = top + insetV
            val r = left + view.width - insetH
            val b = top + view.height - insetV
            if (r <= l || b <= t) return@forEach

            val keyView = view as? KeyView
            if (keyView != null) {
                val hasBorderMask =
                    (keyView.bordered && keyView.def.border != KeyDef.Appearance.Border.Off) ||
                        keyView.def.border == KeyDef.Appearance.Border.On
                if (!hasBorderMask) return@forEach
                val maskWidth = (r - l).toInt().coerceAtLeast(1)
                val maskHeight = (b - t).toInt().coerceAtLeast(1)
                val corner = keyView.blurClipRadius(maskWidth, maskHeight)
                occluderSnapshots.add(
                    OccluderSnapshot(l, t, r, b, corner, roundRect = true)
                )
            } else {
                occluderSnapshots.add(
                    OccluderSnapshot(l, t, r, b, 0f, roundRect = false)
                )
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        frameScheduled = false
        if (ripples.isEmpty()) return
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        val now = SystemClock.uptimeMillis()

        val iter = ripples.iterator()
        while (iter.hasNext()) {
            val ripple = iter.next()
            val elapsed = now - ripple.startTimeMs
            val totalDuration = ripple.durationMs + ripple.fadeOutMs
            if (elapsed >= totalDuration) {
                iter.remove()
                continue
            }
            val rawP = (elapsed.toFloat() / ripple.durationMs).coerceIn(0f, 1f)
            val progress = easeInterpolator.getInterpolation(rawP)

            val fadeProgress = if (elapsed <= ripple.durationMs) {
                0f
            } else {
                ((elapsed - ripple.durationMs).toFloat() / ripple.fadeOutMs).coerceIn(0f, 1f)
            }

            val radius = if (elapsed <= ripple.durationMs) {
                ripple.targetRadius * progress
            } else {
                ripple.targetRadius
            }
            if (radius <= 0.5f) {
                continue
            }

            val fade = if (elapsed <= ripple.durationMs) {
                (1f - progress * 0.52f).coerceIn(0f, 1f)
            } else {
                (1f - fadeProgress).coerceIn(0f, 1f) * 0.48f
            }
            val alpha = (ripple.baseAlpha * fade).toInt().coerceAtLeast(0)
            if (alpha <= 0) {
                continue
            }

            val inner = colorWithAlpha(ripple.color, (alpha * 1.00f).toInt())
            val mid = colorWithAlpha(ripple.color, (alpha * 0.62f).toInt())
            val outer = colorWithAlpha(ripple.color, (alpha * 0.12f).toInt())
            ripplePaint.shader = RadialGradient(
                ripple.cx,
                ripple.cy,
                radius,
                intArrayOf(inner, mid, outer),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(ripple.cx, ripple.cy, radius, ripplePaint)
        }
        ripplePaint.shader = null

        if (occludersDirty) {
            rebuildOccluderSnapshots()
        }
        occluderSnapshots.forEach { snapshot ->
            if (snapshot.roundRect) {
                canvas.drawRoundRect(
                    snapshot.left,
                    snapshot.top,
                    snapshot.right,
                    snapshot.bottom,
                    snapshot.cornerRadius,
                    snapshot.cornerRadius,
                    clearPaint
                )
            } else {
                canvas.drawRect(snapshot.left, snapshot.top, snapshot.right, snapshot.bottom, clearPaint)
            }
        }

        canvas.restoreToCount(saveCount)

        if (ripples.isNotEmpty()) {
            scheduleFrame()
        }
    }

    @ColorInt
    private fun colorWithAlpha(@ColorInt color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}