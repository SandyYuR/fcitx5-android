/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.keyboardtest

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** The host owns dismissal on stop; reopening always creates a fresh test editor. */
internal class KeyboardTestDialog(context: Context) : BottomSheetDialog(context) {
    val panel = KeyboardTestPanel(context, ::dismiss)

    init {
        setContentView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit
                    else minOf(limit, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isSaveEnabled = false
            addView(panel, ViewGroup.LayoutParams(-1, -2))
        }, ViewGroup.LayoutParams(-1, -2))
        behavior.isDraggable = false
        behavior.skipCollapsed = true
        setOnShowListener {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            panel.input.requestFocus()
            panel.input.post {
                if (isShowing && panel.input.isAttachedToWindow) window?.let { host ->
                    WindowCompat.getInsetsController(host, panel.input).show(WindowInsetsCompat.Type.ime())
                }
            }
        }
    }

    override fun dismiss() {
        window?.let { WindowCompat.getInsetsController(it, panel.input).hide(WindowInsetsCompat.Type.ime()) }
        panel.clear()
        super.dismiss()
    }
}
