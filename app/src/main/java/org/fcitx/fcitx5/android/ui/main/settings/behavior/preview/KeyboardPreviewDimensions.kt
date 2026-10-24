/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.preview

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase

internal data class KeyboardPreviewDimensions(
    val keyboardHeightPx: Int,
    val sidePaddingPx: Int,
    val bottomPaddingPx: Int
) {
    companion object {
        /** Match InputView's height base, per-layout override, orientation and padding rules. */
        @Suppress("DEPRECATION")
        fun fromSettings(context: Context, overridePercent: Int?, rowScale: Float, availableWidth: Int): KeyboardPreviewDimensions {
            val prefs = AppPrefs.getInstance()
            val metrics = context.resources.displayMetrics
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val heightBase = when (prefs.advanced.keyboardHeightPercentBase.getValue()) {
                KeyboardHeightPercentBase.DisplayMetrics -> metrics.heightPixels
                KeyboardHeightPercentBase.RealSize -> {
                    val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val display = if (Build.VERSION.SDK_INT >= 30) context.display else manager.defaultDisplay
                    Point().also(display::getRealSize).y
                }
            }
            val keyboard = prefs.keyboard
            return resolve(
                heightBase,
                overridePercent ?: (if (landscape) keyboard.keyboardHeightPercentLandscape else keyboard.keyboardHeightPercent).getValue(),
                rowScale,
                metrics.density,
                (if (landscape) keyboard.keyboardSidePaddingLandscape else keyboard.keyboardSidePadding).getValue(),
                (if (landscape) keyboard.keyboardBottomPaddingLandscape else keyboard.keyboardBottomPadding).getValue(),
                availableWidth.takeIf { it > 0 } ?: metrics.widthPixels
            )
        }

        fun resolve(heightBase: Int, percent: Int, rowScale: Float, density: Float,
                    sidePaddingDp: Int, bottomPaddingDp: Int, widthPx: Int): KeyboardPreviewDimensions {
            val effectivePercent = (percent * rowScale).coerceIn(10f, 90f)
            val maxSidePaddingDp = ((widthPx - widthPx / 2) / 2 / density).toInt()
            return KeyboardPreviewDimensions(
                (heightBase * effectivePercent / 100f).toInt(),
                (sidePaddingDp.coerceIn(0, maxSidePaddingDp.coerceAtLeast(0)) * density).toInt(),
                (bottomPaddingDp * density).toInt()
            )
        }
    }
}
