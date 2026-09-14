/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import splitties.dimensions.dp
import timber.log.Timber
import kotlin.math.abs

/**
 * Single-row container for the Kawaii Bar center buttons.
 *
 * Replaces the previous RecyclerView + FlexboxLayoutManager + adapter stack, which kept derived
 * layout state (flex lines, scroll anchor, cached measured views, widths read from the
 * RecyclerView's *frame* width at bind time) across width changes. That state could survive a
 * floating/docked toggle and leave the strip empty until something else forced a full rebind —
 * the reported "center toolbar goes blank" bug.
 *
 * This container owns *no* derived state: every measure/layout pass recomputes the row from the
 * measure spec it is currently given, so a width change can never leave a stale layout behind
 * and `requestLayout()` is always enough to repair the row.
 *
 * Behavior kept from the old implementation:
 * - buttons are evenly distributed while every button keeps [minButtonWidth];
 * - below that the row scrolls horizontally (drag and fling) instead of squeezing buttons;
 * - children are created and owned by `ButtonsBarUi`; this view never recycles them.
 */
class KawaiiBarRowLayout(context: Context) : ViewGroup(context) {

    /** Minimum button width: 40dp to match icon size. */
    val minButtonWidth: Int = context.dp(40)

    /** Total horizontal margin consumed by one button (2dp on each side). */
    private val buttonSpacing: Int = context.dp(4)

    private val barHeight: Int = context.dp(KawaiiBarComponent.HEIGHT)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false

    /** True when every button currently gets an equal share of the row. */
    var isEvenDistributionMode = true
        private set

    /** Total width of the laid out children, including their margins and the row padding. */
    private var contentWidth = 0

    private var loggedMode = -1
    private var loggedViewport = -1
    private var loggedContent = -1

    init {
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
    }

    // region measurement

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        val viewport = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        val metrics = resolveKawaiiBarRowMetrics(
            availableWidth = viewport,
            buttonCount = childCount,
            spacing = buttonSpacing,
            minButtonWidth = minButtonWidth
        )
        isEvenDistributionMode = metrics.evenDistribution
        isHorizontalScrollBarEnabled = !metrics.evenDistribution

        val innerHeight = if (heightMode == MeasureSpec.UNSPECIFIED || heightSize <= 0) {
            barHeight
        } else {
            (heightSize - paddingTop - paddingBottom).coerceAtLeast(0)
        }

        var measuredContent = 0
        var maxChildHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidthSpec = when {
                metrics.evenDistribution && metrics.buttonWidth > 0 ->
                    MeasureSpec.makeMeasureSpec(metrics.buttonWidth, MeasureSpec.EXACTLY)

                // Scroll mode: intrinsic width, never below the button's own minimum width.
                viewport > 0 -> MeasureSpec.makeMeasureSpec(viewport, MeasureSpec.AT_MOST)

                // Width still unknown (first pass of a not yet measured parent): keep the
                // intrinsic size instead of squeezing the buttons to zero. The next pass with a
                // real width measures them again.
                else -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            }
            val childHeight = (innerHeight - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)
            child.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
            measuredContent += child.measuredWidth + lp.leftMargin + lp.rightMargin
            maxChildHeight = maxOf(maxChildHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }

        val measuredWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> maxOf(measuredContent + paddingLeft + paddingRight, widthSize)
            else -> widthSize
        }
        val measuredHeight = when (heightMode) {
            MeasureSpec.UNSPECIFIED -> maxOf(maxChildHeight + paddingTop + paddingBottom, barHeight)
            else -> heightSize
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val height = b - t
        val innerBottom = (height - paddingBottom).coerceAtLeast(paddingTop)

        var x = paddingLeft
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            x += lp.leftMargin
            val top = (paddingTop + lp.topMargin).coerceAtMost(innerBottom)
            val bottom = (innerBottom - lp.bottomMargin).coerceAtLeast(top)
            child.layout(x, top, x + child.measuredWidth, bottom)
            x += child.measuredWidth + lp.rightMargin
        }
        contentWidth = (x - paddingLeft).coerceAtLeast(0) + paddingRight
        if (isEvenDistributionMode) {
            // Even distribution always fits: any leftover offset is stale and must go.
            scroller.abortAnimation()
            if (scrollX != 0) scrollTo(0, 0)
        } else {
            clampScroll()
        }
        logLayoutStateIfChanged()
    }

    private fun logLayoutStateIfChanged() {
        val mode = if (isEvenDistributionMode) 1 else 0
        val viewport = width - paddingLeft - paddingRight
        if (mode == loggedMode && viewport == loggedViewport && contentWidth == loggedContent) return
        loggedMode = mode
        loggedViewport = viewport
        loggedContent = contentWidth
        Timber.d(
            "KawaiiBarRow: mode=${if (isEvenDistributionMode) "even" else "scroll"} " +
                "viewport=$viewport content=$contentWidth children=$childCount"
        )
    }

    // endregion

    // region horizontal scrolling

    private val maxScroll: Int
        get() = (contentWidth - (width - paddingLeft - paddingRight)).coerceAtLeast(0)

    private fun clampScroll() {
        val clamped = scrollX.coerceIn(0, maxScroll)
        if (clamped != scrollX) scrollTo(clamped, 0)
    }

    override fun computeHorizontalScrollRange(): Int = contentWidth

    override fun computeHorizontalScrollOffset(): Int = scrollX

    override fun computeHorizontalScrollExtent(): Int =
        (width - paddingLeft - paddingRight).coerceAtLeast(0)

    /**
     * Fling is driven by a self-posting runnable rather than [computeScroll]: hardware
     * accelerated children are drawn through a render node, in which case the framework does not
     * call `computeScroll()` at all.
     */
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val target = scroller.currX.coerceIn(0, maxScroll)
            if (target != scrollX) scrollTo(target, 0)
            if (!scroller.isFinished) postOnAnimation(this)
        }
    }

    private fun startFling(velocityX: Float) {
        removeCallbacks(flingRunnable)
        scrollTo(scrollX.coerceIn(0, maxScroll), 0)
        scroller.fling(scrollX, 0, (-velocityX).toInt(), 0, 0, maxScroll, 0, 0)
        postOnAnimation(flingRunnable)
    }

    private fun abortFling() {
        removeCallbacks(flingRunnable)
        scroller.abortAnimation()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isEvenDistributionMode || maxScroll == 0) {
            dragging = false
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = ev.x
                lastTouchY = ev.y
                dragging = false
                abortFling()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastTouchX
                val dy = ev.y - lastTouchY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEvenDistributionMode || maxScroll == 0) return super.onTouchEvent(event)
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                abortFling()
                tracker.addMovement(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                tracker.addMovement(event)
                val dx = event.x - lastTouchX
                if (!dragging && abs(dx) > touchSlop) dragging = true
                if (dragging) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    val target = (scrollX - dx.toInt()).coerceIn(0, maxScroll)
                    if (target != scrollX) scrollTo(target, 0)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                tracker.addMovement(event)
                tracker.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
                val velocityX = tracker.xVelocity
                val wasDragging = dragging
                recycleVelocityTracker()
                dragging = false
                if (wasDragging && abs(velocityX) > minimumFlingVelocity) {
                    startFling(velocityX)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                abortFling()
                recycleVelocityTracker()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        abortFling()
        recycleVelocityTracker()
        dragging = false
    }

    // endregion

    // region layout params

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams

    // endregion
}
