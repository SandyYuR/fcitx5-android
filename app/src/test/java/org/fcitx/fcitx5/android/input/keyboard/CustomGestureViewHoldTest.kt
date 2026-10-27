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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CustomGestureViewHoldTest {
    @Test fun aShortTapClicksWhileTheMeasured331msHoldOpensCandidateActions() {
        val delay = AppPrefs.getInstance().keyboard.longPressDelay
        val previous = delay.getValue()
        delay.setValue(300)
        try {
            for (holdMs in listOf(20L, 331L)) {
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                var clicks = 0
                var longClicks = 0
                try {
                    val view = CustomGestureView(controller.get()).apply {
                        holdSwipeEnabled = true
                        longPressFeedbackEnabled = false
                        setOnClickListener { clicks++ }
                        setOnLongClickListener { longClicks++; true }
                    }
                    controller.get().setContentView(view)
                    view.setViewTreeLifecycleOwner(controller.get())
                    view.layout(0, 0, 100, 100)
                    val down = SystemClock.uptimeMillis()
                    fun touch(action: Int) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 20f, 20f, 0)
                        try { view.onTouchEvent(event) } finally { event.recycle() }
                    }
                    touch(MotionEvent.ACTION_DOWN)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(holdMs))
                    touch(MotionEvent.ACTION_UP)
                    assertEquals("hold=$holdMs", if (holdMs < 300) 1 else 0, clicks)
                    assertEquals("hold=$holdMs", if (holdMs < 300) 0 else 1, longClicks)
                } finally { controller.pause().stop().destroy() }
            }
        } finally { delay.setValue(previous) }
    }
}
