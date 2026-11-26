/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.math.min
import kotlin.math.roundToInt

/** A plain text key whose explicit theme slot can replace its label, including numpad keys. */
@SuppressLint("ViewConstructor")
class IconTextKeyView(
    context: Context,
    theme: Theme,
    appearance: KeyDef.Appearance.Text,
    horizontalGapScale: Float = 1f,
    override val themedIconSlot: String
) : TextKeyView(context, theme, appearance, horizontalGapScale) {
    val img = ImageView(context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    init {
        appearanceView.addView(img, ConstraintLayout.LayoutParams(0, 0).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        appearanceView.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) reapplyIconThemeOverride()
        }
        reapplyIconThemeOverride()
    }

    fun reapplyIconThemeOverride() {
        // Match the existing letter-key icon box and its 8px raster cache buckets.
        val fixed = (24 * resources.displayMetrics.density).roundToInt()
        val height = appearanceView.height.takeIf { it > 0 }
        val raw = if (height == null) fixed else min(fixed, (height * 0.68f).roundToInt())
        val size = (raw / 8 * 8).coerceAtLeast(8)
        val params = img.layoutParams
        if (params.width != size || params.height != size) {
            params.width = size
            params.height = size
            img.layoutParams = params
        }
        val ink = mainText.currentTextColor
        val info = IconThemeManager.resolveIconDrawableInfo(themedIconSlot, size, ink)
        img.setImageDrawable(info?.drawable)
        img.imageTintList = if (info?.tintWithTheme == true) ColorStateList.valueOf(ink) else null
        if (info?.tintWithTheme == false) img.drawable?.setTintList(null)
        img.visibility = if (info == null) View.GONE else View.VISIBLE
        mainText.visibility = if (info == null) View.VISIBLE else View.GONE
        contentDescription = if (info == null) null else mainText.text.toString().takeIf { it.isNotBlank() }
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        reapplyIconThemeOverride()
    }
}
