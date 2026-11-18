/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.editing

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.shadowedKeyBackgroundDrawable
import org.fcitx.fcitx5.android.input.keyboard.insetRadiusDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageResource
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class TextEditingButton(
    ctx: Context,
    private val theme: Theme,
    private val rippled: Boolean,
    private val bordered: Boolean,
    private val radius: Float,
    private val altStyle: Boolean = false,
    backgroundColor: Int? = null,
    shadowColor: Int? = null,
    textColor: Int? = null
) : CustomGestureView(ctx) {

    // bordered
    private val shadowWidth = dp(1)
    private val hInset = dp(4)
    private val vInset = dp(4)

    // !bordered
    private val lineWidth = max(1, dp(1) / 2)

    // 布局可以给单个按键指定颜色；未指定时沿用主题的普通键/功能键配色。
    private val keyBackgroundColor =
        backgroundColor ?: if (altStyle) theme.altKeyBackgroundColor else theme.keyBackgroundColor
    private val keyShadowColor = shadowColor ?: theme.keyShadowColor
    private val keyTextColor =
        textColor ?: if (altStyle) theme.altKeyTextColor else theme.keyTextColor
    private val iconTintColor = textColor ?: theme.altKeyTextColor

    /** 当前显示的主题图标槽位；尺寸变化时按新尺寸重新光栅化。 */
    private var themedIconSlot: String? = null

    /** 首帧还没量出尺寸时挂起的一次补画，避免重复 post。 */
    private var pendingIconResize = false

    private fun roundedBorderDrawable(backgroundColor: Int = Color.TRANSPARENT): Drawable =
        GradientDrawable().apply {
            setColor(backgroundColor)
            setStroke(lineWidth, theme.dividerColor)
            cornerRadius = radius
        }

    private fun roundedPressDrawable(color: Int): Drawable =
        insetRadiusDrawable(0, 0, radius, color)

    private fun roundedPressForeground(): Drawable = if (rippled) {
        RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor),
            null,
            roundedPressDrawable(Color.WHITE)
        )
    } else {
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedPressDrawable(theme.keyPressHighlightColor)
            )
        }
    }

    init {
        if (bordered) {
            background = shadowedKeyBackgroundDrawable(
                keyBackgroundColor, keyShadowColor,
                radius, shadowWidth, hInset, vInset
            )
            foreground = if (rippled) {
                RippleDrawable(
                    ColorStateList.valueOf(theme.keyPressHighlightColor), null,
                    insetRadiusDrawable(hInset, vInset, radius)
                )
            } else {
                StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        insetRadiusDrawable(hInset, vInset, radius, theme.keyPressHighlightColor)
                    )
                }
            }
        } else {
            background = roundedBorderDrawable()
            foreground = roundedPressForeground()
        }
    }

    val textView = textView {
        isClickable = false
        isFocusable = false
        background = null
        setTextColor(keyTextColor)
    }

    val imageView = imageView {
        isClickable = false
        isFocusable = false
        imageTintList = ColorStateList.valueOf(iconTintColor)
    }

    private fun detachContentViews() {
        if (textView.parent === this) removeView(textView)
        if (imageView.parent === this) removeView(imageView)
    }

    fun setText(id: Int) {
        setText(context.getText(id))
    }

    fun setText(text: CharSequence) {
        textView.text = text
        detachContentViews()
        add(textView, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun setIcon(@DrawableRes icon: Int) {
        imageView.imageResource = icon
        imageView.imageTintList = ColorStateList.valueOf(iconTintColor)
        detachContentViews()
        add(imageView, lParams(wrapContent, wrapContent, gravityCenter))
    }

    /**
     * 有主题图标就换成图标并返回 true；没有则保持当前内容不动，由调用方继续显示文字。
     * 布局里的 iconSlot 走这条路径，避免为每个槽位准备一张回退图。
     *
     * 图标盒与 [org.fcitx.fcitx5.android.input.keyboard.KeyView] 同规：先取 24dp 上限，键面
     * 太矮时再按高度 × [ICON_MAX_RATIO] 收缩，最后按 8px 分桶。文本编辑栏的键比字母键高，
     * 只按比例算会得到 32dp 以上的盒子，图标墨迹占键面近一半，看上去比字母键明显偏大。
     */
    fun trySetThemedIcon(slot: String): Boolean {
        val sizePx = iconSizePx()
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(
            slot, sizePx, iconTintColor
        ) ?: return false
        themedIconSlot = slot
        // 按键是加进容器之后才设置图标的，此时可能还没量出尺寸，只能先拿到 24dp 的兜底位图。
        // 等这一帧布局结束再按真实尺寸重画一次，否则图标会一直偏小。
        if (sizePx == null && !pendingIconResize) {
            pendingIconResize = true
            post {
                pendingIconResize = false
                if (width > 0 && height > 0) themedIconSlot?.let(::trySetThemedIcon)
            }
        }
        detachContentViews()
        add(imageView, lParams(wrapContent, wrapContent, gravityCenter))
        imageView.setImageDrawable(iconInfo.drawable)
        if (iconInfo.tintWithTheme) {
            imageView.imageTintList = ColorStateList.valueOf(iconTintColor)
        } else {
            imageView.imageTintList = null
            imageView.drawable?.setTintList(null)
        }
        return true
    }

    private fun iconSizePx(): Int? {
        val h = height
        if (h <= 0) return null
        val fixed = (KEY_ICON_SIZE_DP * resources.displayMetrics.density).roundToInt()
        val raw = minOf(fixed, (h * ICON_MAX_RATIO).roundToInt())
        return maxOf(8, raw / 8 * 8)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 图标位图是按按键尺寸生成的，尺寸变了要按新尺寸重画，否则会被拉伸。
        val slot = themedIconSlot ?: return
        if (w != oldw || h != oldh) trySetThemedIcon(slot)
    }

    fun setThemedIcon(slot: String, @DrawableRes fallback: Int) {
        if (!trySetThemedIcon(slot)) setIcon(fallback)
    }

    fun enableActivatedState() {
        textView.setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_activated),
                    intArrayOf(android.R.attr.state_enabled)
                ),
                intArrayOf(
                    theme.genericActiveForegroundColor,
                    keyTextColor
                )
            )
        )
        imageView.imageTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_activated),
                intArrayOf(android.R.attr.state_enabled)
            ),
            intArrayOf(
                theme.genericActiveForegroundColor,
                iconTintColor
            )
        )
        background = if (bordered) {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_activated),
                    shadowedKeyBackgroundDrawable(
                        theme.genericActiveBackgroundColor, keyShadowColor,
                        radius, shadowWidth, hInset, vInset
                    )
                )
                addState(
                    intArrayOf(android.R.attr.state_enabled),
                    shadowedKeyBackgroundDrawable(
                        keyBackgroundColor, keyShadowColor,
                        radius, shadowWidth, hInset, vInset
                    )
                )
            }
        } else {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_activated),
                    roundedBorderDrawable(theme.genericActiveBackgroundColor)
                )
                addState(
                    intArrayOf(android.R.attr.state_enabled),
                    roundedBorderDrawable()
                )
            }
        }
    }

    private companion object {
        /** 图标盒边长上限（dp），与 KeyView 的按键图标一致。 */
        const val KEY_ICON_SIZE_DP = 24f

        /** 键面太矮时的上限比例，避免图标顶出键面。 */
        const val ICON_MAX_RATIO = 0.68f
    }
}
