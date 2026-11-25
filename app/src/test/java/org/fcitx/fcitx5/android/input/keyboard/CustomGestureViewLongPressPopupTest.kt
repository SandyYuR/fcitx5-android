/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 回归测试：长按监听器返回 false（弹窗类长按，如 LongPressKeyboard）+ 抬手手势未消费 →
 * 释放时不得再触发 performClick()。否则弹窗键长按会多输入一次基础字符。
 *
 * 真实触摸序列：DOWN → 等过 longPressDelay → UP。
 * 历史回归：clickEligible 计算早于 dispatchGestureEvent(Up)，导致 Up 派发里的消费
 * （如 onPopupTrigger 未选中返回 false 不改变 gestureConsumed）未能抑制 click。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CustomGestureViewLongPressPopupTest {
    @Test
    fun longClickReturningFalseMustNotFireClickOnRelease() {
        val delay = AppPrefs.getInstance().keyboard.longPressDelay
        val previous = delay.getValue()
        delay.setValue(300)
        try {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            var clicks = 0
            var longClicks = 0
            val view = CustomGestureView(controller.get()).apply {
                longPressEnabled = true
                longPressFeedbackEnabled = false
                setOnClickListener { clicks++ }
                setOnLongClickListener { longClicks++; false } // 模拟弹窗：返回 false
                onGestureListener = CustomGestureView.OnGestureListener { _, _ -> false }
            }
            controller.get().setContentView(view)
            view.setViewTreeLifecycleOwner(controller.get())
            view.layout(0, 0, 100, 100)
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 20f, 20f, 0)
                try { view.onTouchEvent(e) } finally { e.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            touch(MotionEvent.ACTION_UP)
            assertEquals(1, longClicks)
            // 关键断言：长按弹窗前释放不得多触发 click
            assertEquals("长按弹窗释放时不应触发 click（否则多出字符）", 0, clicks)
            controller.pause().stop().destroy()
        } finally { delay.setValue(previous) }
    }
}
