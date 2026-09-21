/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

open class CustomGestureView(ctx: Context) : FrameLayout(ctx) {

    enum class SwipeAxis { X, Y }

    enum class GestureType { Down, Move, Up }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    private val lifecycleScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    @Volatile
    private var touchMovedOutside = false

    @Volatile
    private var longPressTriggered = false
    var longPressEnabled = false
    private var longPressJob: Job? = null

    @Volatile
    var longPressFeedbackEnabled = true

    @Volatile
    private var repeatStarted = false
    var repeatEnabled = false
    private var repeatJob: Job? = null

    var swipeEnabled = false
    var swipeRepeatEnabled = false
    var swipeThresholdX = 24f
    var swipeThresholdY = 24f

    /**
     * 按住后滑动模式（候选词手势专用）。
     *
     * 与 [swipeEnabled] 互斥：本模式走自己的分支，不读 [swipeEnabled]，阈值复用
     * [swipeThresholdY]。与 [swipeEnabled] 的关键差异：
     * - 按下时**不派发 `GestureType.Down`**，也不阻止父容器拦截 —— 未按住时的
     *   上下滑动完全不消费，留给父容器（展开候选列表靠它翻页滚动）。这正是
     *   [swipeEnabled] 做不到的：它在按下时就夺走拦截权，展开面板无法滚动。
     * - 按满长按判定时间才进入「已按住」，此时才补发 `GestureType.Down`；监听者
     *   收到 Down 后调用 `requestDisallowInterceptTouchEvent(true)`，此后位移归本视图。
     * - 已按住但一次滑动都没发生（原地不动）时，抬手回落到 [performLongClick]，
     *   保持「长按弹出菜单」的既有语义。
     * - 本模式不启动常规 [longPressEnabled] 的 job（否则每次按住都会先弹菜单），
     *   长按语义由抬手时的 [performLongClick] 回落承担。
     */
    var holdSwipeEnabled = false

    @Volatile
    private var holdSwipeArmed = false
    private var holdSwipeJob: Job? = null

    private var swipeRepeatTriggered = false
    private var swipeLastX = -1f
    private var swipeLastY = -1f
    private var swipeXUnconsumed = 0f
    private var swipeYUnconsumed = 0f
    private var swipeTotalX = 0
    private var swipeTotalY = 0
    private var gestureConsumed = false

    var doubleTapEnabled = false
    private var lastClickTime = 0L
    private var maybeDoubleTap = false

    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    // 长按自动重复的进入与移动回调。常规 [swipeEnabled] 滑动在 repeatStarted 后会被
    // 上面的早退分支挡掉，因此按住退格键继续上滑时收不到 Move 事件；这两个回调让
    // 调用方仍能感知"已按住"与按坐标变化（退格上滑清空即依赖它）。
    var onRepeatStartListener: ((View) -> Unit)? = null
    var onRepeatMoveListener: ((View, Float, Float) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null

    var soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard

    private val touchSlop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        enforceNonFocusable()
    }

    private fun enforceNonFocusable() {
        // Clickable views on API 26+ may be promoted to FOCUSABLE_AUTO by framework.
        // Keep gesture-only buttons out of focus navigation so hardware/macro keys
        // won't leave any focused/selected visual state on Kawaii bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusable = View.NOT_FOCUSABLE
        }
        isFocusable = false
        isFocusableInTouchMode = false
        if (hasFocus()) clearFocus()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    private fun pointInView(x: Float, y: Float): Boolean {
        return -touchSlop <= x &&
                -touchSlop <= y &&
                x < (width + touchSlop) &&
                y < (height + touchSlop)
    }

    private fun resetState() {
        touchMovedOutside = false
        // Reset unconditionally rather than behind the *Enabled flags: a rebind
        // (composition-state flip, applyBehaviorPopupBindings) clears those flags first,
        // so gating on them would leave a running repeatJob spinning forever and
        // repeatStarted stuck at true, which makes shouldPerformClick permanently false
        // and the key stop responding to taps.
        longPressTriggered = false
        longPressJob?.cancel()
        longPressJob = null
        repeatStarted = false
        repeatJob?.cancel()
        repeatJob = null
        swipeRepeatTriggered = false
        swipeXUnconsumed = 0f
        swipeYUnconsumed = 0f
        swipeTotalX = 0
        swipeTotalY = 0
        gestureConsumed = false
        holdSwipeArmed = false
        holdSwipeJob?.cancel()
        holdSwipeJob = null
        // double tap state should be preserved on touch up
    }

    fun cancelGestures() {
        isPressed = false
        resetState()
        // reset double tap state on cancel; unconditional for the same reason as resetState
        maybeDoubleTap = false
        lastClickTime = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                val longPressDelayMillis = longPressDelay.toLong()
                drawableHotspotChanged(x, y)
                isPressed = true
                InputFeedbacks.hapticFeedback(this)
                InputFeedbacks.soundEffect(soundEffect)
                if (holdSwipeEnabled) {
                    // 按住后滑动：先不派发 Down、不夺拦截，等按满长按判定时间
                    // 才进入「已按住」，这样未按住时的上下滑动留给父容器翻页。
                    holdSwipeArmed = false
                    holdSwipeJob?.cancel()
                    holdSwipeJob = lifecycleScope.launch {
                        delay(longPressDelayMillis)
                        holdSwipeArmed = true
                        // 反馈对齐常规长按，提示「已按住、可以滑了」
                        if (longPressFeedbackEnabled) {
                            InputFeedbacks.hapticFeedback(this@CustomGestureView, true)
                        }
                        dispatchGestureEvent(GestureType.Down, x, y)
                    }
                } else {
                    dispatchGestureEvent(GestureType.Down, x, y)
                }
                if (longPressEnabled && !holdSwipeEnabled) {
                    longPressJob?.cancel()
                    longPressJob = lifecycleScope.launch {
                        delay(longPressDelayMillis)
                        if (longPressFeedbackEnabled) {
                            InputFeedbacks.hapticFeedback(this@CustomGestureView, true)
                        }
                        longPressTriggered = performLongClick()
                    }
                }
                if (repeatEnabled) {
                    repeatJob?.cancel()
                    repeatJob = lifecycleScope.launch {
                        delay(longPressDelayMillis)
                        repeatStarted = true
                        onRepeatStartListener?.invoke(this@CustomGestureView)
                        while (isActive && isEnabled) {
                            val lastTriggerTime = SystemClock.uptimeMillis()
                            onRepeatListener?.invoke(this@CustomGestureView)
                            val t = lastTriggerTime + RepeatInterval - SystemClock.uptimeMillis()
                            if (t > 0) delay(t)
                        }
                    }
                }
                if (swipeEnabled || holdSwipeEnabled) {
                    swipeLastX = x
                    swipeLastY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                InputFeedbacks.hapticFeedback(this, longPress = true, keyUp = true)
                // 按住后滑动模式：只在「已按住」时才配对派发 Up（未按住时本视图
                // 从未派发过 Down）。其它模式行为不变。
                val holdSwipeWasArmed = holdSwipeEnabled && holdSwipeArmed
                if (!holdSwipeEnabled || holdSwipeWasArmed) {
                    dispatchGestureEvent(GestureType.Up, event.x, event.y)
                }
                // 已按住但一次滑动都没被消费（原地没怎么动）：回落到长按语义，
                // 候选词那里就是呼出操作菜单。必须在 resetState() 前取值。
                val holdSwipeFallbackLongClick = holdSwipeWasArmed && !gestureConsumed
                // 未进入「已按住」的快速点击照常触发 click（候选词即选词）；
                // 已按住时不再触发 click，由上面的长按回落/手势监听接管。
                val shouldPerformClick = !holdSwipeWasArmed && !(touchMovedOutside ||
                        longPressTriggered ||
                        repeatStarted ||
                        swipeRepeatTriggered ||
                        gestureConsumed)
                resetState()
                if (holdSwipeFallbackLongClick) {
                    performLongClick()
                } else if (shouldPerformClick) {
                    if (doubleTapEnabled) {
                        val now = System.currentTimeMillis()
                        if (maybeDoubleTap && now - lastClickTime <= longPressDelay) {
                            maybeDoubleTap = false
                            onDoubleTapListener?.invoke(this)
                        } else {
                            maybeDoubleTap = true
                            performClick()
                        }
                        lastClickTime = now
                    } else {
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                if (holdSwipeEnabled) {
                    if (!holdSwipeArmed) {
                        // 尚未「已按住」：不消费也不派发位移，交给父容器
                        // （展开候选列表靠它翻页滚动）。只记住最新位置，
                        // 等进入「已按住」后从这里起算，避免按住瞬间误触发。
                        swipeLastX = x
                        swipeLastY = y
                        return true
                    }
                    if (!touchMovedOutside && !pointInView(x, y)) {
                        touchMovedOutside = true
                    }
                    val countY = consumeSwipe(y, SwipeAxis.Y)
                    dispatchGestureEvent(GestureType.Move, x, y, 0, countY)
                    swipeLastX = x
                    swipeLastY = y
                    return true
                }
                if (!touchMovedOutside && !pointInView(x, y)) {
                    touchMovedOutside = true
                    if (longPressEnabled) {
                        longPressJob?.cancel()
                        longPressJob = null
                    }
                    if (repeatEnabled) {
                        repeatJob?.cancel()
                        repeatJob = null
                    }
                    if (repeatStarted || !swipeEnabled) {
                        isPressed = false
                    }
                }
                if (!swipeEnabled || longPressTriggered || repeatStarted) {
                    // 已进入长按重复：滑动分支不再派发 Move，改由专用回调上报坐标，
                    // 让按住退格继续上滑时仍能被识别（退格上滑清空）。
                    if (repeatStarted) {
                        onRepeatMoveListener?.invoke(this, x, y)
                    }
                    return true
                }
                val countX = consumeSwipe(x, SwipeAxis.X)
                val countY = consumeSwipe(y, SwipeAxis.Y)
                dispatchGestureEvent(GestureType.Move, x, y, countX, countY)
                swipeLastX = x
                swipeLastY = y
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 与 ACTION_UP 同样的配对规则：只有派发过 Down 才派发 Up。
                if (!holdSwipeEnabled || holdSwipeArmed) {
                    dispatchGestureEvent(GestureType.Up, event.x, event.y)
                }
                cancelGestures()
                return true
            }
        }
        return true
    }

    private fun dispatchGestureEvent(
        type: GestureType,
        x: Float,
        y: Float,
        countX: Int = 0,
        countY: Int = 0
    ) {
        val event = Event(type, gestureConsumed, x, y, countX, countY, swipeTotalX, swipeTotalY)
        val consumed = onGestureListener?.onGesture(this, event) ?: return
        if (consumed && !gestureConsumed) {
            gestureConsumed = true
        }
    }

    private fun consumeSwipe(current: Float, axis: SwipeAxis): Int {
        val unconsumed: Float
        val threshold: Float
        when (axis) {
            SwipeAxis.X -> {
                unconsumed = current - swipeLastX + swipeXUnconsumed
                threshold = swipeThresholdX
            }
            SwipeAxis.Y -> {
                unconsumed = current - swipeLastY + swipeYUnconsumed
                threshold = swipeThresholdY
            }
        }
        val remains: Float = unconsumed % threshold
        val count: Int = (unconsumed / threshold).toInt()
        if (count != 0) {
            if (swipeRepeatEnabled && !swipeRepeatTriggered) {
                swipeRepeatTriggered = true
            }
            if (longPressEnabled && !longPressTriggered) {
                longPressJob?.cancel()
                longPressJob = null
            }
            if (repeatEnabled && !repeatStarted) {
                repeatJob?.cancel()
                repeatJob = null
            }
        }
        when (axis) {
            SwipeAxis.X -> {
                swipeXUnconsumed = remains
                swipeTotalX += count
            }
            SwipeAxis.Y -> {
                swipeYUnconsumed = remains
                swipeTotalY += count
            }
        }
        return count
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
        enforceNonFocusable()
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
        enforceNonFocusable()
    }

    companion object {
        val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay
        const val RepeatInterval = 50L
    }
}
