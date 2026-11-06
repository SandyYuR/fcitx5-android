/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.widget.LinearLayout

/** Compact visual rows, with each icon still receiving a 48 dp touch target. */
internal class ThemeCardDetails(context: Context, private val actions: List<View>) : LinearLayout(context) {
    private var activeDelegate: TouchDelegate? = null
    init { orientation = VERTICAL }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activeDelegate = null
            for (action in actions) {
                if (action.visibility != VISIBLE || !action.isEnabled || !action.isClickable) continue
                val bounds = Rect(0, 0, action.width, action.height)
                offsetDescendantRectToMyCoords(action, bounds)
                bounds.top = (bounds.bottom - (48 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
                if (bounds.contains(event.x.toInt(), event.y.toInt())) {
                    activeDelegate = TouchDelegate(bounds, action)
                    break
                }
            }
        }
        activeDelegate?.let { delegate ->
            val copy = MotionEvent.obtain(event)
            val handled = try { delegate.onTouchEvent(copy) } finally { copy.recycle() }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) activeDelegate = null
            return handled
        }
        return super.dispatchTouchEvent(event)
    }
}
