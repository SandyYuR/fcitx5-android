/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CandidateTouchCancellationTest {
    private class Touch(val view: CustomGestureView) {
        var clicks = 0
        var longClicks = 0
        var holds = 0
        var swipes = 0
        private var downTime = 0L

        init {
            view.holdSwipeEnabled = true
            view.setOnClickListener { clicks++ }
            view.setOnLongClickListener { longClicks++; true }
            view.onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                if (event.type == CustomGestureView.GestureType.Down) holds++
                val swipe = event.type == CustomGestureView.GestureType.Move && event.countY != 0
                if (swipe) swipes++
                swipe
            }
        }

        fun send(action: Int, x: Float = 100f, y: Float = 40f) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }

        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** Exercise expanded candidates, ordinary bar candidates, and horizontally overflowing text. */
    private fun candidates(block: (Touch) -> Unit) {
        val delay = AppPrefs.getInstance().keyboard.longPressDelay
        val previous = delay.getValue()
        delay.setValue(300)
        try {
            for ((overflow, word) in listOf(false to "candidate", true to "candidate",
                true to "a-very-long-candidate-that-overflows-its-slot")) {
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                try {
                    val activity = controller.get()
                    val ui = CandidateItemUi(activity, ThemePreset.MaterialLight, Typeface.MONOSPACE)
                    ui.setHorizontalOverflowEnabled(overflow)
                    ui.updateCandidate(CandidateWord("", word, "", false))
                    val touch = Touch(ui.root)
                    activity.setContentView(ui.root, ViewGroup.LayoutParams(200, 80))
                    ui.root.setViewTreeLifecycleOwner(activity)
                    ui.root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
                    ui.root.layout(0, 0, 200, 80)
                    block(touch)
                } finally { controller.pause().stop().destroy() }
            }
        } finally { delay.setValue(previous) }
    }

    @Test fun fastVerticalExitCancelsTheTapAtEitherEdge() = candidates { touch ->
        for (y in listOf(-1f, 80f)) {
            touch.send(MotionEvent.ACTION_DOWN)
            touch.idle(10)
            touch.send(MotionEvent.ACTION_MOVE, y = y)
            assertFalse(touch.view.isPressed)
            touch.send(MotionEvent.ACTION_UP, y = y)
            assertEquals(0, touch.clicks)
        }
    }

    @Test fun releaseOutsideCancelsEvenWithoutAMoveEvent() = candidates { touch ->
        for ((x, y) in listOf(100f to -1f, 100f to 80f, -1f to 40f, 200f to 40f)) {
            touch.send(MotionEvent.ACTION_DOWN)
            touch.idle(10)
            touch.send(MotionEvent.ACTION_UP, x, y)
            assertEquals(0, touch.clicks)
        }
    }

    @Test fun returningInsideDoesNotReactivateTheCancelledTapOrHold() = candidates { touch ->
        touch.send(MotionEvent.ACTION_DOWN)
        touch.send(MotionEvent.ACTION_MOVE, y = -1f)
        touch.send(MotionEvent.ACTION_MOVE)
        touch.idle(331)
        touch.send(MotionEvent.ACTION_UP)
        assertEquals(0, touch.clicks)
        assertEquals(0, touch.holds)
        assertEquals(0, touch.longClicks)

        touch.send(MotionEvent.ACTION_DOWN)
        touch.send(MotionEvent.ACTION_UP)
        assertEquals(1, touch.clicks)
    }

    @Test fun normalTapAndSmallMovementInsideStillSelectTheCandidate() = candidates { touch ->
        touch.send(MotionEvent.ACTION_DOWN)
        touch.send(MotionEvent.ACTION_MOVE, x = 102f, y = 42f)
        touch.send(MotionEvent.ACTION_UP, x = 102f, y = 42f)
        assertEquals(1, touch.clicks)
        assertEquals(0, touch.longClicks)
    }

    @Test fun aCancelledTouchNeverSelectsAndTheNextTapStillWorks() = candidates { touch ->
        touch.send(MotionEvent.ACTION_DOWN)
        touch.send(MotionEvent.ACTION_CANCEL)
        assertEquals(0, touch.clicks)
        touch.send(MotionEvent.ACTION_DOWN)
        touch.send(MotionEvent.ACTION_UP)
        assertEquals(1, touch.clicks)
    }

    @Test fun armedHoldStillAllowsSwipingOutsideToChooseACharacter() = candidates { touch ->
        touch.send(MotionEvent.ACTION_DOWN)
        touch.idle(331)
        touch.send(MotionEvent.ACTION_MOVE, y = -40f)
        touch.send(MotionEvent.ACTION_UP, y = -40f)
        assertEquals(1, touch.holds)
        assertEquals(1, touch.swipes)
        assertEquals(0, touch.clicks)
        assertEquals(0, touch.longClicks)
    }
}
