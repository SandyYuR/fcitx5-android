/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 空格键光标划动的精度约定：
 * - 慢速小幅拖动（超过触摸 slop、不到一格）不再触发长按菜单，抬手时补算一格；
 * - 快速点击照旧打空格，不因为一点手指抖动变成移光标；
 * - 竖向主导的小幅拖动不动光标（保持原有行为）。
 *
 * 阈值按 slop 的倍数设定，避免 Robolectric 密度差异让「不到一格」变成「正好一格」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CustomGestureViewSwipeTest {

    private class Harness(
        val view: CustomGestureView,
        val controller: ActivityController<ComponentActivity>,
        val slop: Float
    ) {
        var clicks = 0
        var longClicks = 0
        var moveCountX = 0
        var moveCountY = 0
        var moveEvents = 0
        private val down = SystemClock.uptimeMillis()

        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                view.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

        fun destroy() = controller.pause().stop().destroy()
    }

    private fun harness(): Harness {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = CustomGestureView(controller.get()).apply {
            longPressFeedbackEnabled = false
            longPressEnabled = true
            swipeEnabled = true
            swipeRepeatEnabled = true
            setOnClickListener { }
            setOnLongClickListener { true }
        }
        controller.get().setContentView(view)
        view.setViewTreeLifecycleOwner(controller.get())
        view.layout(0, 0, 400, 400)
        val slop = ViewConfiguration.get(controller.get()).scaledTouchSlop.toFloat()
        // 一格 = 2*slop，竖向一格 = slop：保证「超过 slop 但不到一格」的窗口真实存在
        view.swipeThresholdX = slop * 2f
        view.swipeThresholdY = slop
        val harness = Harness(view, controller, slop)
        view.setOnClickListener { harness.clicks++ }
        view.setOnLongClickListener { harness.longClicks++; true }
        view.onGestureListener = CustomGestureView.OnGestureListener { _, event ->
            if (event.type == CustomGestureView.GestureType.Move) {
                harness.moveEvents++
                harness.moveCountX += event.countX
                harness.moveCountY += event.countY
                event.countX != 0
            } else {
                false
            }
        }
        return harness
    }

    private fun withLongPressDelay(block: () -> Unit) {
        val delay = AppPrefs.getInstance().keyboard.longPressDelay
        val previous = delay.getValue()
        delay.setValue(300)
        try {
            block()
        } finally {
            delay.setValue(previous)
        }
    }

    @Test fun aSlowSubStepDragCancelsLongPressAndCommitsOneCell() = withLongPressDelay {
        val h = harness()
        val subCell = h.slop + 2f
        try {
            h.touch(MotionEvent.ACTION_DOWN, 50f, 50f)
            h.idle(120)
            // 超过 slop、但还没凑够一格：以前这里会一路走到 300ms 弹长按菜单
            h.touch(MotionEvent.ACTION_MOVE, 50f + subCell, 50f)
            h.idle(220)
            assertEquals("慢速小幅拖动不应触发长按", 0, h.longClicks)
            h.touch(MotionEvent.ACTION_UP, 50f + subCell, 50f)
            assertEquals("抬手补算一格", 1, h.moveCountX)
            assertEquals("补算格数后不应再打出空格", 0, h.clicks)
        } finally {
            h.destroy()
        }
    }

    @Test fun aFastTapWithJitterStillTypesSpace() = withLongPressDelay {
        val h = harness()
        val subCell = h.slop + 2f
        try {
            h.touch(MotionEvent.ACTION_DOWN, 50f, 50f)
            h.idle(40)
            h.touch(MotionEvent.ACTION_MOVE, 50f + subCell, 50f)
            h.idle(40)
            h.touch(MotionEvent.ACTION_UP, 50f + subCell, 50f)
            assertEquals("快打抖动不该补格数", 0, h.moveCountX)
            assertEquals("快打照旧打空格", 1, h.clicks)
        } finally {
            h.destroy()
        }
    }

    @Test fun aVerticalSubStepDragDoesNotMoveCursor() = withLongPressDelay {
        val h = harness()
        try {
            h.touch(MotionEvent.ACTION_DOWN, 50f, 50f)
            h.idle(120)
            h.touch(MotionEvent.ACTION_MOVE, 50f + h.slop + 2f, 50f + h.slop * 3f)
            h.idle(200)
            h.touch(MotionEvent.ACTION_UP, 50f + h.slop + 2f, 50f + h.slop * 3f)
            assertEquals("竖向主导的小幅拖动不补格数", 0, h.moveCountX)
            assertEquals("竖向主导的小幅拖动不打空格", 0, h.clicks)
        } finally {
            h.destroy()
        }
    }

    @Test fun aFullStepDragCommitsCellsWithoutExtraCompensation() = withLongPressDelay {
        val h = harness()
        val twoCells = h.slop * 4f
        try {
            h.touch(MotionEvent.ACTION_DOWN, 50f, 50f)
            h.idle(60)
            h.touch(MotionEvent.ACTION_MOVE, 50f + twoCells, 50f)
            h.idle(60)
            h.touch(MotionEvent.ACTION_UP, 50f + twoCells, 50f)
            assertEquals("两格位移只产生两格，不额外补格", 2, h.moveCountX)
            assertEquals("有格数时不再打空格", 0, h.clicks)
        } finally {
            h.destroy()
        }
    }
}
