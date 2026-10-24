/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import androidx.appcompat.widget.TooltipCompat
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.utils.settingsColor

internal object EditorUi {
    fun button(context: Context, label: Int, tonal: Boolean = false, action: () -> Unit) =
        MaterialButton(ContextThemeWrapper(context, if (tonal)
            R.style.ThemeOverlay_Fcitx_KeyboardEditor_Tonal else R.style.ThemeOverlay_Fcitx_KeyboardEditor)).apply {
            setText(label)
            isAllCaps = false
            textSize = 14f
            minHeight = context.dp(48)
            minimumHeight = context.dp(48)
            setOnClickListener { action() }
        }

    fun icon(context: Context, drawable: Int, label: Int, action: () -> Unit) =
        MaterialButton(ContextThemeWrapper(context, R.style.ThemeOverlay_Fcitx_KeyboardEditor)).apply {
            text = ""
            setIconResource(drawable)
            iconSize = context.dp(24)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            contentDescription = context.getString(label)
            TooltipCompat.setTooltipText(this, context.getString(label))
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
            setOnClickListener { action() }
        }

    /** Same bright card surface and 20dp outer corners as the settings preference groups. */
    fun card(context: Context, content: View) = MaterialCardView(context).apply {
        radius = context.dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(context.settingsColor(com.google.android.material.R.attr.colorSurfaceBright))
        addView(content)
    }

    /** Settings palette and rounded ripple tiles, independent of the rendered keyboard theme. */
    fun tile(view: TextView, emphasized: Boolean, customized: Boolean = false) {
        val context = view.context
        val drawable = (view.background as? RippleDrawable)?.getDrawable(0) as? MaterialShapeDrawable
            ?: MaterialShapeDrawable(ShapeAppearanceModel.builder()
                .setAllCornerSizes(context.dp(12).toFloat()).build()).also { shape ->
                view.background = RippleDrawable(
                    ColorStateList.valueOf(context.settingsColor(androidx.appcompat.R.attr.colorControlHighlight)),
                    shape, MaterialShapeDrawable(shape.shapeAppearanceModel).apply {
                        fillColor = ColorStateList.valueOf(Color.WHITE)
                    })
            }
        drawable.fillColor = ColorStateList.valueOf(context.settingsColor(if (emphasized)
            com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurfaceContainer))
        drawable.setStroke(context.dp(if (customized) 2 else 1).toFloat(), context.settingsColor(if (customized)
            com.google.android.material.R.attr.colorTertiary else com.google.android.material.R.attr.colorOutlineVariant))
        view.setTextColor(context.settingsColor(if (emphasized)
            com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurface))
        view.isFocusable = true
    }
}
