/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.settingsColor

/** Shared clipboard geometry can render either the keyboard canvas or a settings-themed panel. */
data class ClipboardColors(
    val surface: Int,
    val card: Int,
    val preview: Int,
    val text: Int,
    val muted: Int,
    val category: Int,
    val primary: Int,
    val onPrimary: Int,
    val outline: Int,
    val ripple: Int,
    val snackbar: Int,
    val onSnackbar: Int,
    val snackbarAction: Int,
) {
    companion object {
        fun keyboard(theme: Theme) = ClipboardColors(
            surface = theme.barColor, card = theme.clipboardEntryColor,
            preview = theme.keyBackgroundColor, text = theme.keyTextColor, muted = theme.altKeyTextColor,
            category = theme.keyBackgroundColor, primary = theme.accentKeyBackgroundColor,
            onPrimary = theme.accentKeyTextColor, outline = theme.dividerColor,
            ripple = theme.keyPressHighlightColor, snackbar = theme.popupBackgroundColor,
            onSnackbar = theme.popupTextColor, snackbarAction = theme.genericActiveBackgroundColor,
        )

        /** Context already includes the selected mode, palette, contrast and keyboard-color option. */
        fun panel(context: Context) = ClipboardColors(
            surface = context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh),
            card = context.settingsColor(com.google.android.material.R.attr.colorSurfaceBright),
            // Media previews belong to the card surface, including their transparent margins.
            preview = context.settingsColor(com.google.android.material.R.attr.colorSurfaceBright),
            text = context.settingsColor(com.google.android.material.R.attr.colorOnSurface),
            muted = context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
            category = context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHighest),
            primary = context.settingsColor(androidx.appcompat.R.attr.colorPrimary),
            onPrimary = context.settingsColor(com.google.android.material.R.attr.colorOnPrimary),
            outline = context.settingsColor(com.google.android.material.R.attr.colorOutlineVariant),
            ripple = ColorUtils.setAlphaComponent(context.settingsColor(androidx.appcompat.R.attr.colorPrimary), 0x30),
            snackbar = context.settingsColor(com.google.android.material.R.attr.colorSurfaceInverse),
            onSnackbar = context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceInverse),
            snackbarAction = context.settingsColor(com.google.android.material.R.attr.colorPrimaryInverse),
        )
    }
}
