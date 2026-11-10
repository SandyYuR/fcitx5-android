/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.TooltipCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.utils.rippleDrawable
import org.fcitx.fcitx5.android.utils.settingsColor
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.setPaddingDp

class ThemeThumbnailUi(override val ctx: Context) : Ui {

    enum class State { Normal, Selected, LightMode, DarkMode }
    private val keyBorder by ThemeManager.prefs.keyBorder

    private var loadJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadGeneration = 0L

    val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    val bar = view(::View)

    val themeNameText = textView {
        textSize = 14f
        minLines = 1
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        includeFontPadding = false
        isClickable = false
        isFocusable = false
        setPaddingDp(12, 0, 12, 0)
    }

    val spaceBar = view(::View)

    val returnKey = view(::View)
    private var swatchTheme: Theme? = null
    private val swatches = object : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            val theme = swatchTheme ?: return
            val gap = dp(3).toFloat()
            val cellWidth = (width - gap * 7) / 6f
            val cellHeight = (height - gap * 3) / 2f
            paint.color = theme.keyBackgroundColor
            for (row in 0..1) for (column in 0..5) {
                val x = gap + column * (cellWidth + gap)
                val y = gap + row * (cellHeight + gap)
                canvas.drawRoundRect(x, y, x + cellWidth, y + cellHeight, gap, gap, paint)
            }
        }
    }

    val checkMark = imageView {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val stateLabel = textView {
        textSize = 11f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(ctx.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
    }
    private val badge = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingDp(6, 4, 6, 4)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(ctx.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer))
        }
        addView(checkMark, LinearLayout.LayoutParams(dp(14), dp(14)))
        addView(stateLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        visibility = View.GONE
    }

    val editButton = imageView {
        setPaddingDp(14, 6, 14, 6)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_edit_24
        contentDescription = ctx.getString(R.string.edit_theme)
    }

    val exportButton = imageView {
        setPaddingDp(14, 6, 14, 6)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_share_24
        contentDescription = ctx.getString(R.string.theme_export_this)
    }

    val dynamicIcon = imageView {
        setPaddingDp(4)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_auto_awesome_24
        contentDescription = ctx.getString(R.string.text_keyboard_layout_key_color_mode_monet_pick)
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    val randomButton = imageView {
        setPaddingDp(14, 6, 14, 6)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_random_theme_24
    }

    val scoreText = textView {
        textSize = 11f
        maxLines = 1
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
    }

    val thumbnailView = constraintLayout {
        add(bkg, lParams(matchParent, matchParent))
        add(bar, lParams(matchParent, dp(14)))
        add(swatches, lParams(matchParent, dp(36)) {
            topOfParent(dp(29))
        })
        add(spaceBar, lParams(height = dp(10)) {
            centerHorizontally()
            bottomOfParent(dp(6))
            matchConstraintPercentWidth = 0.5f
        })
        add(returnKey, lParams(dp(14), dp(14)) {
            rightOfParent(dp(4))
            bottomOfParent(dp(4))
        })
        add(dynamicIcon, lParams(dp(28), dp(28)) {
            topOfParent(dp(6))
            startOfParent(dp(6))
        })
        add(badge, lParams(wrapContent, wrapContent) {
            topOfParent(dp(6))
            endOfParent(dp(6))
            constrainedWidth = true
            startToEnd = dynamicIcon.id
            marginStart = dp(4)
            goneStartMargin = dp(6)
            horizontalBias = 1f
        })
        add(scoreText, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent(dp(22))
        })
    }

    override val root = object : MaterialCardView(ctx) {
        private val contentClip = android.graphics.Path()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            contentClip.reset()
            contentClip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, android.graphics.Path.Direction.CW)
        }
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            // Also clip software draws and cached previews, where clipToOutline is not applied.
            val save = canvas.save()
            canvas.clipPath(contentClip)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }.apply {
        clipToOutline = true
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = ctx.settingsColor(com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(ctx.settingsColor(com.google.android.material.R.attr.colorSurfaceBright))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(thumbnailView, LinearLayout.LayoutParams(-1, dp(88)))
            addView(ThemeCardDetails(ctx, listOf(randomButton, exportButton, editButton)).apply {
                addView(themeNameText, LinearLayout.LayoutParams(-1, themeCardNameHeight(ctx)))
                addView(LinearLayout(ctx).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPaddingDp(4, 0, 4, 0)
                    addView(randomButton, LinearLayout.LayoutParams(dp(48), dp(32)))
                    addView(exportButton, LinearLayout.LayoutParams(dp(48), dp(32)))
                    addView(editButton, LinearLayout.LayoutParams(dp(48), dp(32)))
                }, LinearLayout.LayoutParams(-1, dp(32)))
            }, LinearLayout.LayoutParams(-1, -2))
        }, android.widget.FrameLayout.LayoutParams(-1, -2))
    }

    fun setTheme(theme: Theme) {
        swatchTheme = theme
        swatches.invalidate()
        bkg.setBackgroundColor(theme.backgroundColor)
        root.apply {
            foreground = rippleDrawable(ctx.settingsColor(com.google.android.material.R.attr.colorOutlineVariant))
        }
        
        // Set other non-time-consuming UI elements
        bar.backgroundColor = theme.barColor
        themeNameText.apply {
            text = formatThemeName(theme.name)
            setTextColor(ctx.settingsColor(com.google.android.material.R.attr.colorOnSurface))
            TooltipCompat.setTooltipText(this, theme.name)
        }
        scoreText.apply {
            val score = if (ThemeManager.isRandomTheme(theme)) ThemeManager.randomThemeScore else null
            text = score?.let { ctx.getString(R.string.random_theme_score_short, it.total) } ?: ""
            setTextColor(theme.altKeyTextColor)
            visibility = if (score == null) View.GONE else View.VISIBLE
            swatches.isVisible = score == null
        }
        spaceBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(2f)
            setColor(theme.spaceBarColor)
        }
        returnKey.background = ShapeDrawable(OvalShape()).apply {
            paint.color = theme.accentKeyBackgroundColor
        }
        val foregroundTint = ColorStateList.valueOf(ctx.settingsColor(androidx.appcompat.R.attr.colorPrimary))
        val supportsMonetMapping = theme is Theme.Monet && ThemeMonet.supportsCustomMappingEditor(ctx)
        editButton.apply {
            visibility = if (ThemeManager.isRandomTheme(theme) || theme is Theme.Custom || supportsMonetMapping)
                View.VISIBLE else View.GONE
            background = rippleDrawable(ctx.settingsColor(com.google.android.material.R.attr.colorOutlineVariant))
            imageTintList = foregroundTint
            TooltipCompat.setTooltipText(this, contentDescription)
        }
        exportButton.apply {
            visibility = if ((theme is Theme.Custom && !ThemeManager.isRandomTheme(theme)) || theme is Theme.Monet)
                View.VISIBLE else View.GONE
            background = rippleDrawable(ctx.settingsColor(com.google.android.material.R.attr.colorOutlineVariant))
            imageTintList = foregroundTint
            TooltipCompat.setTooltipText(this, contentDescription)
        }
        dynamicIcon.apply {
            isVisible = supportsMonetMapping
            imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        }
        randomButton.apply {
            visibility = if (ThemeManager.isRandomTheme(theme)) {
                View.VISIBLE
            } else View.GONE
            imageTintList = foregroundTint
            background = rippleDrawable(ctx.settingsColor(com.google.android.material.R.attr.colorOutlineVariant))
        }
        checkMark.imageTintList = ColorStateList.valueOf(ctx.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))

        loadBackgroundAsync(theme, ++loadGeneration)
    }

    /**
     * Asynchronously load background image to avoid blocking main thread.
     * Cancels any ongoing loading task.
     */
    private fun loadBackgroundAsync(theme: Theme, generation: Long) {
        loadJob?.cancel()
        bkg.imageDrawable = null

        loadJob = scope.launch {
            try {
                val drawable = withContext(Dispatchers.IO) {
                    when (theme) {
                        is Theme.Custom -> {
                            if (theme.shouldApplyBlur()) {
                                theme.blurredBackgroundDrawable(enableBlur = true, keyBorder = keyBorder)
                            } else {
                                theme.backgroundDrawable()
                            }
                        }
                        else -> theme.backgroundDrawable()
                    }
                }
                if (generation != loadGeneration) return@launch
                bkg.imageDrawable = drawable
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                bkg.imageDrawable = theme.backgroundDrawable()
            }
        }
    }

    private fun formatThemeName(name: String): String {
        // Truncate UUID to first 8 characters for display
        return if (name.length == 36 && name.count { it == '-' } == 4) {
            name.take(8)
        } else {
            name
        }
    }

    fun setChecked(checked: Boolean) {
        setChecked(if (checked) State.Selected else State.Normal)
    }

    fun setChecked(state: State) {
        badge.isVisible = state != State.Normal
        root.isSelected = state != State.Normal
        root.strokeWidth = ctx.dp(if (state == State.Normal) 1 else 2)
        root.strokeColor = ctx.settingsColor(if (state == State.Normal)
            com.google.android.material.R.attr.colorOutlineVariant else androidx.appcompat.R.attr.colorPrimary)
        stateLabel.text = when (state) {
            State.Normal -> ""
            State.Selected -> ctx.getString(R.string.ui_theme_selected)
            State.LightMode -> ctx.getString(R.string.ui_theme_day)
            State.DarkMode -> ctx.getString(R.string.ui_theme_night)
        }
        ViewCompat.setStateDescription(root, stateLabel.text.takeIf { it.isNotEmpty() })
        checkMark.imageResource = when (state) {
            State.Normal -> 0
            State.Selected -> R.drawable.ic_baseline_check_24
            State.LightMode -> R.drawable.ic_baseline_light_mode_24
            State.DarkMode -> R.drawable.ic_baseline_dark_mode_24
        }
    }

    fun cleanup() {
        loadJob?.cancel()
        loadGeneration++
        bkg.imageDrawable = null
    }

    fun setRandomAction(onClick: (() -> Unit)?) {
        randomButton.setOnClickListener(onClick?.let { listener -> View.OnClickListener { listener() } })
        randomButton.contentDescription = if (onClick == null) null else ctx.getString(R.string.random_theme)
    }

}
