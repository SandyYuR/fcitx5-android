/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.utils.settingsColor
import kotlin.math.abs

internal data class EditorKeyBounds(val position: EditorKeyPosition, val rect: RectF, val label: String, val splitAfter: Boolean)

/** Captures editor gestures above the real preview; never sends input actions to the IME. */
internal class KeyboardCanvasOverlay(context: Context) : View(context) {
    var boundsProvider: () -> List<EditorKeyBounds> = { emptyList() }
    var onSelect: (EditorKeyPosition) -> Boolean = { false }
    var onShowInspector: () -> Unit = {}
    var onMove: (EditorKeyPosition, EditorKeyPosition) -> Unit = { _, _ -> }
    var selected: EditorKeyPosition? = null
        set(value) { field = value; refresh() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionRect = RectF()
    private val accent = context.settingsColor(android.R.attr.colorAccent)
    private var dragging: EditorKeyPosition? = null
    private var destination: EditorKeyPosition? = null
    private var insertion: RectF? = null
    private var touchX = 0f
    private var touchY = 0f

    private fun hit(x: Float, y: Float) = boundsProvider().firstOrNull { it.rect.contains(x, y) }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hit(e.x, e.y)?.let {
                if (onSelect(it.position)) {
                    selected = it.position
                    onShowInspector()
                }
            }
            performClick()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val key = hit(e.x, e.y) ?: return
            if (!onSelect(key.position)) return
            selected = key.position
            dragging = key.position
            parent.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            updateDrop(e.x, e.y)
        }
    })

    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            boundsProvider().indexOfFirst { it.rect.contains(x, y) }.takeIf { it >= 0 } ?: INVALID_ID
        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            ids.addAll(boundsProvider().indices)
        }
        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val key = boundsProvider().getOrNull(id)
            node.contentDescription = key?.let {
                "${it.label}, ${context.getString(R.string.layout_editor_key_title, it.position.row + 1, it.position.key + 1)}"
            } ?: context.getString(R.string.layout_editor_select_key)
            val rect = Rect()
            key?.rect?.roundOut(rect)
            if (rect.isEmpty) rect.set(0, 0, 1, 1)
            node.setBoundsInParent(rect)
            node.isClickable = true
            node.isSelected = key?.position == selected
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }
        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            val key = boundsProvider().getOrNull(id) ?: return false
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || !onSelect(key.position)) return false
            selected = key.position
            onShowInspector()
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    init {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun refresh() {
        invalidate()
        accessibility.invalidateRoot()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A MATCH_PARENT overlay must not enlarge its WRAP_CONTENT FrameLayout on
        // the first pass. FrameLayout remeasures it EXACTLY to the preview's height.
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean { super.performClick(); return true }

    // GestureDetector calls performClick in onSingleTapUp; dragging must not trigger a click.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        if (dragging != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateDrop(event.x, event.y)
                MotionEvent.ACTION_UP -> {
                    val from = dragging
                    val to = destination
                    cancelDrag()
                    if (from != null && to != null) onMove(from, to)
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelDrag()
            }
        }
        return true
    }

    private fun cancelDrag() {
        dragging = null
        destination = null
        insertion = null
        parent.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun updateDrop(x: Float, y: Float) {
        touchX = x
        touchY = y
        val bounds = boundsProvider()
        if (x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) {
            destination = null; insertion = null; invalidate(); return
        }
        val nearest = bounds.minByOrNull { abs(it.rect.centerY() - y) } ?: return
        val row = bounds.filter { it.position.row == nearest.position.row }.sortedBy { it.rect.left }
        val after = row.firstOrNull { x < it.rect.centerX() }
        val key = after ?: row.last()
        val index = key.position.key + if (after == null) 1 else 0
        destination = EditorKeyPosition(key.position.row, index)
        val edge = if (after == null) key.rect.right else key.rect.left
        insertion = RectF(edge - context.dp(2), key.rect.top, edge + context.dp(2), key.rect.bottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = boundsProvider()
        paint.color = accent
        paint.strokeWidth = context.dp(2).toFloat()
        paint.style = Paint.Style.STROKE
        bounds.filter { it.position == selected }.forEach {
            selectionRect.set(it.rect)
            selectionRect.inset(context.dp(2).toFloat(), context.dp(2).toFloat())
            canvas.drawRoundRect(selectionRect, context.dp(6).toFloat(), context.dp(6).toFloat(), paint)
        }
        // Manual boundaries remain visible even when the current arrangement is regular.
        bounds.filter { it.splitAfter }.forEach {
            canvas.drawLine(it.rect.right, it.rect.top + context.dp(4), it.rect.right, it.rect.bottom - context.dp(4), paint)
        }
        paint.style = Paint.Style.FILL
        insertion?.let { canvas.drawRoundRect(it, context.dp(2).toFloat(), context.dp(2).toFloat(), paint) }
        if (dragging != null) {
            paint.alpha = 28
            bounds.filter { it.position.row == destination?.row }.forEach { canvas.drawRect(it.rect, paint) }
            paint.alpha = 210
            canvas.drawCircle(touchX, touchY - context.dp(24), context.dp(14).toFloat(), paint)
            paint.alpha = 255
        }
    }
}
