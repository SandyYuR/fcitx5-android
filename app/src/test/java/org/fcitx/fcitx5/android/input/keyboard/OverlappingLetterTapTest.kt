/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class OverlappingLetterTapTest {
    @Test fun bothReleaseOrdersProduceTwoClicks() {
        for (firstRelease in 0..1) {
            val probe = TouchProbe()
            probe.pressBoth()
            probe.pointerUp(firstRelease)
            assertEquals(1, probe.clicks)
            probe.end(1 - firstRelease)
            assertEquals(2, probe.clicks)
        }
    }

    @Test fun individualCancellationOnlySuppressesTheCancelledFinger() {
        for (cancelled in 0..1) {
            val probe = TouchProbe()
            probe.pressBoth()
            probe.pointerUp(cancelled, MotionEvent.FLAG_CANCELED)
            assertEquals(0, probe.clicks)
            probe.end(1 - cancelled)
            assertEquals(1, probe.clicks)
        }
    }

    @Test fun finalFingerCanAlsoBeCancelled() {
        val probe = TouchProbe()
        probe.pressBoth()
        probe.pointerUp(0)
        probe.end(1, MotionEvent.FLAG_CANCELED)
        assertEquals(1, probe.clicks)
    }

    @Test fun wholeGestureCancellationDoesNotCommitEitherHeldFinger() {
        val probe = TouchProbe()
        probe.pressBoth()
        probe.dispatch(MotionEvent.ACTION_CANCEL, 50, 0, 1)
        assertEquals(0, probe.clicks)
    }

    private class TouchProbe {
        var clicks = 0
            private set
        private val start = SystemClock.uptimeMillis()
        private val view = CustomGestureView(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 100, 100)
            letterTapHandoverEnabled = true
            setOnClickListener { clicks++ }
        }

        fun pressBoth() {
            dispatch(MotionEvent.ACTION_DOWN, 0, 0)
            dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 28, 0, 1)
            assertEquals(0, clicks)
        }

        fun pointerUp(index: Int, flags: Int = 0) {
            dispatch(MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, 0, 1, flags = flags)
        }

        fun end(pointerId: Int, flags: Int = 0) {
            dispatch(MotionEvent.ACTION_UP, 68, pointerId, flags = flags)
        }

        fun dispatch(action: Int, offset: Long, vararg ids: Int, flags: Int = 0) {
            val properties = ids.map { pointer ->
                MotionEvent.PointerProperties().apply { id = pointer; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }.toTypedArray()
            val coordinates = ids.map { pointer ->
                MotionEvent.PointerCoords().apply { x = 40f + 10f * pointer; y = 50f; pressure = 1f; size = 1f }
            }.toTypedArray()
            val event = MotionEvent.obtain(start, start + offset, action, ids.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
