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
import androidx.core.view.isVisible
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
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        setPaddingDp(8, 4, 8, 4)
    }

    val spaceBar = view(::View)

    val returnKey = view(::View)

    val checkMark = imageView {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val editButton = imageView {
        setPaddingDp(16, 4, 4, 16)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_edit_24
        contentDescription = "Edit theme"
    }

    /**
     * 导出按钮。
     *
     * 导出原先只能长按卡片触发，界面上没有任何可见线索。放在**左下角**：
     * 右上已被编辑按钮占用、左上被动态图标占用，左下是这张卡片仅剩的空白角
     * （右下有 returnKey 与 spaceBar）。
     */
    val exportButton = imageView {
        setPaddingDp(4, 16, 16, 4)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_share_24
        contentDescription = "Export theme"
    }

    val dynamicIcon = imageView {
        setPaddingDp(5, 5, 5, 5)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_auto_awesome_24
    }

    val scoreText = textView {
        textSize = 10f
        maxLines = 1
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
    }

    val thumbnailView = constraintLayout {
        outlineProvider = ViewOutlineProvider.BOUNDS
        elevation = dp(2f)
        add(bkg, lParams(matchParent, matchParent))
        add(bar, lParams(matchParent, dp(14)))
        add(themeNameText, lParams(matchParent, wrapContent) {
            centerInParent()
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
        add(dynamicIcon, lParams(dp(32), dp(32)) {
            topOfParent(dp(2))
            startOfParent(dp(2))
        })
        add(scoreText, lParams(matchParent, wrapContent) {
            bottomOfParent(dp(18))
            centerHorizontally()
        })
        add(checkMark, lParams(dp(60), dp(60)) {
            centerInParent()
        })
        add(editButton, lParams(dp(44), dp(44)) {
            topOfParent()
            endOfParent()
        })
        add(exportButton, lParams(dp(44), dp(44)) {
            bottomOfParent()
            startOfParent()
        })
    }

    override val root = thumbnailView

    fun setTheme(theme: Theme) {
        root.apply {
            foreground = rippleDrawable(theme.keyPressHighlightColor)
        }
        
        // Set other non-time-consuming UI elements
        bar.backgroundColor = theme.barColor
        themeNameText.apply {
            text = formatThemeName(theme.name)
            setTextColor(theme.keyTextColor)
        }
        scoreText.apply {
            val score = if (ThemeManager.isRandomTheme(theme)) ThemeManager.randomThemeScore else null
            text = score?.let { ctx.getString(R.string.random_theme_score_short, it.total) } ?: ""
            setTextColor(theme.altKeyTextColor)
            visibility = if (score == null) View.GONE else View.VISIBLE
        }
        spaceBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(2f)
            setColor(theme.spaceBarColor)
        }
        returnKey.background = ShapeDrawable(OvalShape()).apply {
            paint.color = theme.accentKeyBackgroundColor
        }
        val foregroundTint = ColorStateList.valueOf(theme.altKeyTextColor)
        editButton.apply {
            visibility =
                if (ThemeManager.isRandomTheme(theme) || theme is Theme.Custom ||
                    (theme is Theme.Monet && ThemeMonet.supportsCustomMappingEditor(ctx))) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            background = rippleDrawable(theme.keyPressHighlightColor)
            imageTintList = foregroundTint
        }
        exportButton.apply {
            // 只有可导出的主题才显示：内置主题没有对应的可导出文件，
            // 显示一个点了没反应的按钮比不显示更糟。
            visibility =
                if ((theme is Theme.Custom && !ThemeManager.isRandomTheme(theme)) || theme is Theme.Monet) {
                    View.VISIBLE
                } else View.GONE
            background = rippleDrawable(theme.keyPressHighlightColor)
            imageTintList = foregroundTint
            contentDescription = ctx.getString(R.string.theme_export_this)
        }
        dynamicIcon.apply {
            imageResource = if (ThemeManager.isRandomTheme(theme)) {
                R.drawable.ic_random_theme_24
            } else {
                R.drawable.ic_baseline_auto_awesome_24
            }
            visibility = if (ThemeManager.isRandomTheme(theme) ||
                (theme is Theme.Monet && ThemeMonet.supportsCustomMappingEditor(ctx))) {
                View.VISIBLE
            } else View.GONE
            imageTintList = foregroundTint
        }
        checkMark.imageTintList = foregroundTint

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
        checkMark.isVisible = checked
        checkMark.imageResource = R.drawable.ic_baseline_check_24
    }

    fun setChecked(state: State) {
        checkMark.isVisible = state != State.Normal
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
        dynamicIcon.setOnClickListener(onClick?.let { listener -> View.OnClickListener { listener() } })
        dynamicIcon.contentDescription = if (onClick == null) null else ctx.getString(R.string.random_theme)
    }
}
