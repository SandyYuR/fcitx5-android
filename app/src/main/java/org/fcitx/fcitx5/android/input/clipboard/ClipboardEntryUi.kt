/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.graphics.Bitmap
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardEntryUi(override val ctx: Context, theme: Theme, radius: Float,
                       private val palette: ClipboardColors = ClipboardColors.keyboard(theme)) : Ui {

    val preview = imageView {
        visibility = View.GONE
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumHeight = dp(72)
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(palette.preview)
        }
    }

    private val imagePlaceholder = imageView {
        visibility = View.GONE
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumHeight = dp(72)
        setPaddingDp(16, 8, 16, 8)
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(palette.preview)
        }
        imageDrawable = drawable(R.drawable.ic_baseline_image_24)
        imageTintList = ColorStateList.valueOf(palette.muted)
    }

    val textView = textView {
        minLines = 1
        maxLines = 4
        textSize = 14f
        includeFontPadding = false
        setPaddingDp(12, 8, 12, 8)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(palette.text)
    }

    val pin = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
            alpha = 230
        }
        imageTintList = ColorStateList.valueOf(palette.muted)
    }

    val layout = constraintLayout {
        add(preview, lParams(matchParent, dp(72)) {
            topOfParent(dp(6))
        })
        add(imagePlaceholder, lParams(matchParent, dp(72)) {
            topOfParent(dp(6))
        })
        add(textView, lParams(matchParent, wrapContent) {
            centerVertically()
        })
        add(pin, lParams(dp(18), dp(18)) {
            bottomOfParent(dp(8))
            endOfParent(dp(8))
        })
    }

    override val root = CustomGestureView(ctx).apply {
        isClickable = true
        minimumHeight = dp(48)
        foreground = RippleDrawable(
            ColorStateList.valueOf(palette.ripple), null,
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
        )
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(palette.card)
        }
        add(layout, lParams(matchParent, matchParent))
    }

    fun setEntry(text: String, pinned: Boolean, previewBitmap: Bitmap? = null) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
        ViewCompat.setStateDescription(root, if (pinned) ctx.getString(R.string.ui_clipboard_pinned) else null)
        if (previewBitmap != null) {
            preview.setImageBitmap(previewBitmap)
            preview.visibility = View.VISIBLE
            imagePlaceholder.visibility = View.GONE
            if (text.isEmpty()) {
                // Image only, no text
                textView.visibility = View.GONE
                root.minimumHeight = ctx.dp(84)
            } else {
                // Image with text
                textView.visibility = View.VISIBLE
                textView.maxLines = 2
                textView.setPaddingRelative(ctx.dp(12), ctx.dp(82), ctx.dp(if (pinned) 34 else 12), ctx.dp(8))
                root.minimumHeight = ctx.dp(122)
            }
        } else {
            preview.visibility = View.GONE
            if (text.isEmpty()) {
                // No image preview and no text - show image placeholder icon
                imagePlaceholder.visibility = View.VISIBLE
                textView.visibility = View.GONE
                root.minimumHeight = ctx.dp(84)
            } else {
                imagePlaceholder.visibility = View.GONE
                textView.visibility = View.VISIBLE
                textView.maxLines = 4
                textView.setPaddingRelative(ctx.dp(12), ctx.dp(8), ctx.dp(if (pinned) 34 else 12), ctx.dp(8))
                root.minimumHeight = ctx.dp(48)
            }
        }
    }
}
