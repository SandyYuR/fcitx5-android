/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.view.View
import com.google.android.material.slider.Slider
import splitties.views.dsl.core.Ui
import kotlin.math.roundToInt

/** Material slider with the integer progress contract used by existing settings editors. */
class SettingsSlider(context: Context) : Slider(context) {
    interface OnSeekBarChangeListener {
        fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(seekBar: SettingsSlider)
        fun onStopTrackingTouch(seekBar: SettingsSlider)
    }

    private var progressListener: OnSeekBarChangeListener? = null

    var max: Int = 100
        set(limit) {
            require(limit >= 0)
            if (value > limit) value = limit.toFloat()
            field = limit
            valueTo = maxOf(1, limit).toFloat()
        }

    var progress: Int
        get() = value.roundToInt().coerceIn(0, max)
        set(progress) { value = progress.coerceIn(0, max).toFloat() }

    init {
        id = View.generateViewId()
        valueTo = 100f
        stepSize = 1f
        addOnChangeListener { _, value, fromUser ->
            if (value > max) this.value = max.toFloat()
            else progressListener?.onProgressChanged(this, value.roundToInt(), fromUser)
        }
        addOnSliderTouchListener(object : OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { progressListener?.onStartTrackingTouch(this@SettingsSlider) }
            override fun onStopTrackingTouch(slider: Slider) { progressListener?.onStopTrackingTouch(this@SettingsSlider) }
        })
    }

    fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) { progressListener = listener }
}

fun Context.settingsSlider(init: SettingsSlider.() -> Unit) = SettingsSlider(this).apply(init)
fun Ui.settingsSlider(init: SettingsSlider.() -> Unit) = ctx.settingsSlider(init)
fun View.settingsSlider(init: SettingsSlider.() -> Unit) = context.settingsSlider(init)
