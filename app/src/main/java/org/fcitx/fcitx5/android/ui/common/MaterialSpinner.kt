/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatSpinner
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.fcitx.fcitx5.android.utils.settingsColor

/** Outlined Material menu styling while retaining Spinner's adapters and selection callbacks. */
class MaterialSpinner @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatSpinner(context, attrs) {
    init {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val shape = ShapeAppearanceModel.builder().setAllCornerSizes(dp(4).toFloat()).build()
        val surface = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = density
            strokeColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(context.settingsColor(androidx.appcompat.R.attr.colorPrimary),
                    context.settingsColor(com.google.android.material.R.attr.colorOutline))
            )
        }
        val arrow = requireNotNull(AppCompatResources.getDrawable(context,
            com.google.android.material.R.drawable.mtrl_dropdown_arrow)).mutate().apply {
            setTint(context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val layers = LayerDrawable(arrayOf(surface, arrow)).apply {
            setLayerSize(1, dp(24), dp(24))
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetEnd(1, dp(12))
        }
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(context.settingsColor(
            androidx.appcompat.R.attr.colorControlHighlight)), layers,
            MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(Color.WHITE) })
        minimumHeight = dp(56)
        setPaddingRelative(dp(12), dp(4), dp(40), dp(4))
        setPopupBackgroundDrawable(MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            initializeElevationOverlay(context)
            elevation = dp(8).toFloat()
        })
    }
}
