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

    /**
     * Cached radial gradient, keyed by ripple colour and quantized alpha.
     *
     * A new [RadialGradient] per ripple per frame was pure GC churn (see E2). The shader is
     * built once at [SHADER_UNIT_RADIUS] centred on the origin; the draw transforms the canvas
     * so one cached instance serves any centre and radius without being mutated.
     *
     * Alpha is quantized in [ALPHA_QUANTIZATION_STEP] units: a ripple fades continuously, so an
     * exact key would never hit. 8 units gives 32 buckets across the fade, which at ~60fps is
     * finer than one bucket per frame.
     */
    private val shaderCache = HashMap<Int, RadialGradient>()

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

            // Cached shader (see E2), positioned by transforming the canvas rather than the
            // shader: Shader.setLocalMatrix mutates the shared instance and discards its native
            // counterpart, which would both re-allocate per draw and make a shader reused by two
            // ripples in the same frame order-dependent. The cached gradient is built at the
            // origin with SHADER_UNIT_RADIUS, so scaling the canvas lines it up exactly.
            ripplePaint.shader = obtainRippleShader(ripple.color, alpha)
            val saveId = canvas.save()
            canvas.translate(ripple.cx, ripple.cy)
            val scale = radius / SHADER_UNIT_RADIUS
            canvas.scale(scale, scale)
            canvas.drawCircle(0f, 0f, SHADER_UNIT_RADIUS, ripplePaint)
            canvas.restoreToCount(saveId)
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

    /**
     * Gradient for [color] at [alpha], from the cache when possible.
     *
     * Built at [SHADER_UNIT_RADIUS] around (0, 0); the caller scales and translates the canvas.
     */
    private fun obtainRippleShader(@ColorInt color: Int, alpha: Int): RadialGradient {
        val maxBucket = 255 / ALPHA_QUANTIZATION_STEP
        val quantizedAlpha = (alpha / ALPHA_QUANTIZATION_STEP).coerceIn(0, maxBucket)
        // RGB in the high 24 bits, alpha bucket in the low 8: no collisions.
        val key = ((color and 0x00FFFFFF) shl 8) or quantizedAlpha
        shaderCache[key]?.let { return it }
        val effectiveAlpha = quantizedAlpha * ALPHA_QUANTIZATION_STEP
        val shader = RadialGradient(
            0f,
            0f,
            SHADER_UNIT_RADIUS,
            intArrayOf(
                colorWithAlpha(color, effectiveAlpha),
                colorWithAlpha(color, (effectiveAlpha * 0.62f).toInt()),
                colorWithAlpha(color, (effectiveAlpha * 0.12f).toInt())
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        // Bounded by construction: a keyboard uses one or two ripple colours, and alpha has
        // 32 buckets. Clear anyway if a theme somehow produces many colours.
        if (shaderCache.size >= MAX_CACHED_SHADERS) shaderCache.clear()
        shaderCache[key] = shader
        return shader
    }

    private companion object {
        /** Radius the cached shaders are built at; scaled per draw. */
        const val SHADER_UNIT_RADIUS = 256f

        /** Alpha bucket width; 8 gives 32 buckets and no visible banding. */
        const val ALPHA_QUANTIZATION_STEP = 8

        const val MAX_CACHED_SHADERS = 128
    }
}