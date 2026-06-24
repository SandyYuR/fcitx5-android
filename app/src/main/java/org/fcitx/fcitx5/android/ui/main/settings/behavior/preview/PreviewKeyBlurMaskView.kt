/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.utils.DarkenColorFilter

class PreviewKeyBlurMaskView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val clipRect = Rect()
    private val clipRectF = RectF()
    private val clipPath = Path()
    private val keyViews = ArrayList<KeyView>(64)
    private val keyClipRects = ArrayList<Rect>(64)
    private val keyClipRadii = ArrayList<Float>(64)

    private var keyboard: TextKeyboard? = null
    private var blurBitmap: Bitmap? = null
    private var keyBorder = false
    private var keyRegionsDirty = true
    private var keyHierarchyDirty = true
    private var hasVisibleKey = false
    private var redrawRetryCount = 0
    /**
     * RenderNode that carries the GPU blur RenderEffect. Recorded with a single drawBitmap of the
     * source image; replayed under a canvas clip so the blur applies BEFORE the clip — matching
     * the pre-GPU CPU path (loadBlurredBitmapForRendering + clipPath + drawBitmap).
     */
    private var blurNode: RenderNode? = null
    private var blurNodeDirty = true
    private var hasRenderEffect = false

    @RequiresApi(Build.VERSION_CODES.S)
    private fun obtainBlurNode(): RenderNode {
        return blurNode ?: RenderNode("keyBlur").also { blurNode = it }
    }

    private fun discardBlurNode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurNode?.discardDisplayList()
        }
        blurNode = null
        blurNodeDirty = true
    }

    fun bindKeyboard(newKeyboard: TextKeyboard?) {
        keyboard = newKeyboard
        keyRegionsDirty = true
        keyHierarchyDirty = true
        invalidate()
    }

    fun applyTheme(theme: Theme, keyBorder: Boolean) {
        this.keyBorder = keyBorder
        val custom = theme as? Theme.Custom
        val bg = custom?.backgroundImage
        if (bg == null || bg.blurRadius <= 0f) {
            setBlurBitmap(null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setBlurBitmap(
                bitmap = bg.loadBitmapForRendering(),
                brightness = bg.brightness,
                blurRadius = bg.blurRadius,
                useRenderEffect = true
            )
        } else {
            setBlurBitmap(bg.loadBlurredBitmapForRendering(), bg.brightness)
        }
    }

    fun refreshMask(hierarchyChanged: Boolean = false) {
        keyRegionsDirty = true
        if (hierarchyChanged) {
            keyHierarchyDirty = true
        }
        invalidate()
    }

    private fun setBlurBitmap(
        bitmap: Bitmap?,
        brightness: Int = 70,
        blurRadius: Float = 0f,
        useRenderEffect: Boolean = false
    ) {
        blurBitmap = bitmap
        paint.colorFilter = bitmap?.let { DarkenColorFilter(100 - brightness) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The view itself never carries a RenderEffect; the blur lives on the offscreen node
            // so clipping happens after the blur, not before.
            setRenderEffect(null)
            hasRenderEffect = useRenderEffect && bitmap != null && blurRadius > 0f
            if (hasRenderEffect) {
                val node = obtainBlurNode()
                node.setRenderEffect(
                    RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                )
                blurNodeDirty = true
            } else {
                discardBlurNode()
            }
        }
        visibility = if (bitmap == null) GONE else VISIBLE
        keyRegionsDirty = true
        keyHierarchyDirty = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = blurBitmap ?: return
        if (width <= 0 || height <= 0) return
        calculateCenterCropSource(bitmap.width, bitmap.height, width, height, srcRect)
        dstRect.set(0, 0, width, height)

        if (!keyBorder) {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            redrawRetryCount = 0
            return
        }

        if (keyRegionsDirty) {
            rebuildKeyRegions()
        }
        var drewKeyRegion = false
        keyClipRects.forEachIndexed { index, rect ->
            val saveId = canvas.save()
            val radius = keyClipRadii.getOrElse(index) { 0f }
            if (radius > 0f) {
                clipRectF.set(rect)
                clipPath.reset()
                clipPath.addRoundRect(clipRectF, radius, radius, Path.Direction.CW)
                canvas.clipPath(clipPath)
            } else {
                canvas.clipRect(rect)
            }
            drawFullScreenBlur(canvas, bitmap)
            canvas.restoreToCount(saveId)
            drewKeyRegion = true
        }

        if (hasVisibleKey && !drewKeyRegion) {
            if (redrawRetryCount < 8) {
                redrawRetryCount++
                keyRegionsDirty = true
                postInvalidateOnAnimation()
            }
        } else {
            redrawRetryCount = 0
        }
    }

    /**
     * Draws the source bitmap onto [canvas]. On API ≥ S the bitmap is replayed through a
     * RenderNode carrying the blur RenderEffect, so the GPU blur runs once over the full image
     * and any canvas clip the caller set applies AFTER the blur — visually equivalent to
     * `loadBlurredBitmapForRendering + clipPath + drawBitmap`.
     */
    private fun drawFullScreenBlur(canvas: Canvas, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasRenderEffect && canvas.isHardwareAccelerated) {
            val node = obtainBlurNode()
            if (node.setPosition(0, 0, width, height) || blurNodeDirty || !node.hasDisplayList()) {
                val rc = node.beginRecording(width, height)
                try {
                    rc.drawBitmap(bitmap, srcRect, dstRect, paint)
                } finally {
                    node.endRecording()
                }
                blurNodeDirty = false
            }
            canvas.drawRenderNode(node)
        } else {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
    }

    private fun rebuildKeyRegions() {
        keyRegionsDirty = false
        hasVisibleKey = false
        keyClipRects.clear()
        keyClipRadii.clear()
        val keyboardView = keyboard ?: return
        val parentView = parent as? ViewGroup ?: return

        if (keyHierarchyDirty) {
            keyViews.clear()
            collectVisibleKeys(keyboardView, keyViews)
            keyHierarchyDirty = false
        }

        fun buildClipRects() {
            hasVisibleKey = false
            keyClipRects.clear()
            keyClipRadii.clear()
            keyViews.forEach { key ->
                if (!key.isShown) return@forEach
                hasVisibleKey = true
                if (key.width <= 0 || key.height <= 0) return@forEach
                clipRect.set(0, 0, key.width, key.height)
                parentView.offsetDescendantRectToMyCoords(key, clipRect)
                clipRect.offset(-left, -top)
                clipRect.set(
                    clipRect.left + key.hMargin,
                    clipRect.top + key.vMargin,
                    clipRect.right - key.hMargin,
                    clipRect.bottom - key.vMargin
                )
                if (clipRect.width() <= 0 || clipRect.height() <= 0) return@forEach
                if (!clipRect.intersect(0, 0, width, height)) return@forEach
                keyClipRects.add(Rect(clipRect))
                keyClipRadii.add(key.blurClipRadius(clipRect.width(), clipRect.height()))
            }
        }

        buildClipRects()
        if (!hasVisibleKey && keyViews.isNotEmpty()) {
            keyViews.clear()
            collectVisibleKeys(keyboardView, keyViews)
            buildClipRects()
        }
    }

    private fun collectVisibleKeys(view: View, out: MutableList<KeyView>) {
        if (view is KeyView) {
            out.add(view)
            return
        }
        val group = view as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            collectVisibleKeys(group.getChildAt(i), out)
        }
    }

    private fun calculateCenterCropSource(
        bitmapWidth: Int,
        bitmapHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        outRect: Rect
    ) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            outRect.set(0, 0, bitmapWidth.coerceAtLeast(0), bitmapHeight.coerceAtLeast(0))
            return
        }
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        if (bitmapRatio > targetRatio) {
            val cropWidth = (bitmapHeight * targetRatio).toInt().coerceAtLeast(1)
            val left = (bitmapWidth - cropWidth) / 2
            outRect.set(left, 0, left + cropWidth, bitmapHeight)
        } else {
            val cropHeight = (bitmapWidth / targetRatio).toInt().coerceAtLeast(1)
            val top = (bitmapHeight - cropHeight) / 2
            outRect.set(0, top, bitmapWidth, top + cropHeight)
        }
    }
}
