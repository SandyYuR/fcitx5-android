/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.OvalShape
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.bar.ToolbarMetrics
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.padding
import kotlin.math.roundToInt

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

        /** 图标四周留白，随「工具栏大小」一起缩放（原实现固定 10dp）。 */
        private const val IMAGE_PADDING_DP = 10
    }

    val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(IMAGE_PADDING_DP)
        // 默认 CENTER_INSIDE 保证「没被 applyToolbarScale 显式调过」的 ToolButton
        // （剪贴板、文本编辑、状态区等页面里的按钮）行为与本改动之前逐像素一致。
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    val textView = view(::AutoScaleTextView) {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = gravityCenter
        visibility = View.GONE
    }

    private var theme: Theme? = null
    private var isActive: Boolean = false
    private var usingCustomDrawable: Boolean = false
    private var customDrawableTintWithTheme: Boolean = false
    @ColorInt
    private var pressHighlightColor: Int = Color.TRANSPARENT

    var iconRotation: Float
        get() = image.rotation
        set(value) {
            image.rotation = value
        }

    /**
     * 应用「工具栏大小」图标缩放。
     *
     * 图标要**跟随工具栏一起变大**，光把按钮撑高是不够的：`wrap_content` 的 ImageView 按
     * 「intrinsic 尺寸 + padding」测量；在 40dp 高工具栏中，24dp intrinsic 图标会被上下 10dp 留白压到 20dp。`maxWidth` 只能**封顶**、不能**撑大**，所以这里改成
     * 直接给 image 一个显式方框尺寸。
     *
     * 尺寸来自 [ToolbarMetrics]，**不来自父容器实测宽度**：留白会吃掉一部分宽度，只看父容器
     * 的话 `FIT_CENTER` 会把图案撑满整个按钮（含留白区），实际比预期大。
     *
     * 关键约束是**图上放大有硬上限**：工具栏**横向空间不随百分比变化**。图标顶破 44dp 格子
     * 的话，中间那一行就被撑到放不下，整行退回横向滚动、可见按钮个数骤减（初版 200% 时只剩
     * 3 个图标就是这么来的）。所以 [ToolbarMetrics.iconSizeDp] 对 200% 只放到 40dp，
     * 留白同步从 10dp 收到 2dp——**格子总宽恒为 44dp**，按钮个数因此不变。
     *
     * 100% 时图标方框为 40dp（20dp 有效图案 + 两侧 10dp 留白），按钮横向足迹仍为 44dp；这与改动前
     * 40dp 高度约束下实际绘制的 20dp 图案逐像素一致。
     *
     * @param slotDp 这个按钮能拿到的格子宽度（dp）。中间按钮行用 [ToolbarMetrics.BUTTON_FOOTPRINT_DP]；
     *   工具栏左右两侧的方形按钮边长等于栏高，传栏高即可（栏调高它们才跟着长大）。
     */
    fun applyToolbarScale(scale: Float, slotDp: Int = ToolbarMetrics.BUTTON_FOOTPRINT_DP) {
        val s = scale.coerceAtLeast(0.1f)
        val percent = (s * 100f).roundToInt()
        val boxPx = dp(ToolbarMetrics.iconBoxDp(percent, slotDp))
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.padding = dp(ToolbarMetrics.iconPaddingDp(percent, slotDp))
        image.updateLayoutParams<FrameLayout.LayoutParams> {
            width = boxPx
            height = boxPx
        }
        textView.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            ToolbarMetrics.BASE_LABEL_SIZE * s
        )
    }

    /**
     * 把一个按钮的横向足迹钉死在 [footprintDp]（dp）。
     *
     * 必须**同时**写 minimumWidth 与 layoutParams.width：行布局在平分模式下用 EXACTLY
     * 测量（会覆盖 layoutParams），滚动模式下才看得到 layoutParams 与 minimumWidth。
     * 只设其中之一，就会在其中一种模式下把足迹算错。
     */
    fun applyHorizontalFootprint(footprintDp: Int) {
        val px = dp(footprintDp)
        minimumWidth = px
        updateLayoutParams { width = px }
    }

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        this.theme = theme
        image.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        textView.setTextColor(theme.altKeyTextColor)
        setIcon(icon)
        setPressHighlightColor(theme.keyPressHighlightColor)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
        add(textView, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun iconAnimate(): ViewPropertyAnimator = image.animate()

    /**
     * End a touch ripple before this button's containing UI is replaced.
     */
    fun clearTransientPressState() {
        cancelGestures()
        jumpDrawablesToCurrentState()
    }

    fun setIcon(@DrawableRes icon: Int) {
        usingCustomDrawable = false
        textView.visibility = View.GONE
        image.visibility = View.VISIBLE
        image.imageTintList = currentIconColor()?.let { ColorStateList.valueOf(it) }
        image.imageResource = icon
    }

    fun setIconFromDrawable(drawable: Drawable?, tintWithTheme: Boolean = true) {
        if (drawable != null) {
            usingCustomDrawable = true
            customDrawableTintWithTheme = tintWithTheme
            textView.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.imageDrawable = drawable.mutate()
            if (tintWithTheme) {
                image.imageTintList = null
                currentIconColor()?.let { image.imageDrawable?.setTint(it) }
            } else {
                image.imageTintList = null
                image.imageDrawable?.setTintList(null)
            }
        }
    }

    fun setText(text: String?) {
        if (!text.isNullOrEmpty()) {
            image.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = text
        }
    }

    fun setPressHighlightColor(@ColorInt color: Int) {
        pressHighlightColor = color
        applyBackground()
    }

    /**
     * Set the active state of this button.
     * When active, the button icon color changes, background remains transparent.
     */
    fun setActive(active: Boolean) {
        if (isActive == active || theme == null) return
        isActive = active
        updateAppearance()
    }

    private fun updateAppearance() {
        val theme = theme ?: return
        val iconColor = currentIconColor()
        if (usingCustomDrawable) {
            if (customDrawableTintWithTheme) {
                image.imageTintList = null
                if (iconColor != null) image.imageDrawable?.setTint(iconColor)
            } else {
                image.imageTintList = null
                image.imageDrawable?.setTintList(null)
            }
        } else {
            image.imageTintList = iconColor?.let { ColorStateList.valueOf(it) }
        }
        textView.setTextColor(theme.altKeyTextColor)
        applyBackground()
    }

    @ColorInt
    private fun currentIconColor(): Int? {
        val theme = theme ?: return null
        return if (isActive) theme.accentKeyBackgroundColor else theme.altKeyTextColor
    }

    private fun applyBackground() {
        val theme = theme ?: return
        val isText = textView.visibility == View.VISIBLE
        if (isText && isActive) {
            val activeBg = GradientDrawable().apply {
                setColor(theme.accentKeyBackgroundColor and 0x00ffffff or (0x3f shl 24))
                cornerRadius = dp(8).toFloat()
            }
            background = if (disableAnimation) {
                val pressedOverlay = ShapeDrawable(OvalShape()).apply { paint.color = pressHighlightColor }
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), LayerDrawable(arrayOf(activeBg, pressedOverlay)))
                    addState(intArrayOf(), activeBg)
                }
            } else {
                RippleDrawable(ColorStateList.valueOf(pressHighlightColor), activeBg, null)
            }
        } else {
            background = if (disableAnimation) {
                StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        ShapeDrawable(OvalShape()).apply { paint.color = pressHighlightColor }
                    )
                }
            } else {
                borderlessRippleDrawable(pressHighlightColor, dp(20))
            }
        }
    }
}
