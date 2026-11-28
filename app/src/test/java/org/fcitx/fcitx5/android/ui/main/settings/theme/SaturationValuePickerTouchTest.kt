/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.MotionEvent
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SaturationValuePickerTouchTest {
    @Test fun pickerOwnsDragUntilUpOrCancelAndReleasesItsParent() {
        val requests = mutableListOf<Boolean>()
        val context = RuntimeEnvironment.getApplication()
        val parent = object : FrameLayout(context) {
            override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                requests += disallow
                super.requestDisallowInterceptTouchEvent(disallow)
            }
        }
        val picker = SaturationValuePickerView(context)
        parent.addView(picker)
        picker.layout(0, 0, 200, 200)
        var changes = 0
        picker.onColorChanged = { _, _ -> changes++ }
        fun send(action: Int, x: Float = 30f, y: Float = 30f) {
            val event = MotionEvent.obtain(0, 10, action, x, y, 0)
            try { assertTrue(picker.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        assertEquals(true, requests.last())
        send(MotionEvent.ACTION_MOVE, 30f, 180f)
        assertEquals(true, requests.last())
        assertEquals(2, changes)
        send(MotionEvent.ACTION_UP)
        assertEquals(false, requests.last())
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_CANCEL)
        assertEquals(false, requests.last())
    }
}
