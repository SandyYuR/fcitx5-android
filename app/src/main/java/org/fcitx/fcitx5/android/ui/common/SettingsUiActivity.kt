/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Build
import android.os.UserManager
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.PredictiveBackControl
import com.google.android.material.color.DynamicColors
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.data.theme.SettingsKeyboardColors
import org.fcitx.fcitx5.android.utils.settingsColor

/** Shared Material theme for app pages, floating editors and transparent selection hosts. */
abstract class SettingsUiActivity : AppCompatActivity() {
    private var appliedKeyboardPalette: SettingsKeyboardColors.Palette? = null
    protected open val floatingSettingsWindow: Boolean get() = false
    protected open val transparentSettingsWindow: Boolean get() = false

    private fun applyWindowTheme() {
        when {
            floatingSettingsWindow -> theme.applyStyle(R.style.ThemeOverlay_FcitxSettings_Floating, true)
            transparentSettingsWindow -> theme.applyStyle(R.style.ThemeOverlay_FcitxSettings_Transparent, true)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Resolve the mode before AppCompat wraps the context. Changing it in onCreate can
        // schedule a second recreation while the requested destination is being restored.
        val storageContext = if (Build.VERSION.SDK_INT >= 24 &&
            !newBase.getSystemService(UserManager::class.java).isUserUnlocked) {
            newBase.createDeviceProtectedStorageContext()
        } else newBase
        val selection = SettingsUiPreferences.read(storageContext)
        delegate.localNightMode = when (selection.mode) {
            SettingsUiPreferences.MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsUiPreferences.MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.attachBaseContext(storageContext)
    }

    @OptIn(PredictiveBackControl::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // FragmentManager requires this switch before any transactions, including restoration.
        // The motion settings screen recreates its activity when this preference changes.
        FragmentManager.enablePredictiveBack(
            android.os.Build.VERSION.SDK_INT >= 34 &&
                SettingsUiMotionPreferences.read(this).predictiveBack
        )
        setTheme(SettingsUiPreferences.themeResource(this))
        applyWindowTheme()
        super.onCreate(savedInstanceState)
        // AppCompat reapplies its base theme in onCreate. Apply overlays afterwards, using
        // the final night configuration, and before any settings content is inflated.
        if (SettingsUiPreferences.usesSystemDynamicColors(this)) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        // AppCompat can replace Resources while wrapping the base context. Install the loader on
        // the final activity Resources, just as MDC does for its content-based dynamic colors.
        appliedKeyboardPalette = SettingsKeyboardColors.current(this, SettingsUiPreferences.read(this))
        appliedKeyboardPalette?.let { SettingsKeyboardColors.applyToActivity(this, it) }
        SettingsUiPreferences.applySurfaceOptions(this)
        applyWindowTheme()
        if (floatingSettingsWindow) {
            val shape = ShapeAppearanceModel.builder()
                .setAllCornerSizes(28f * resources.displayMetrics.density).build()
            window.setBackgroundDrawable(MaterialShapeDrawable(shape).apply {
                fillColor = ColorStateList.valueOf(settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            })
            return
        }
        if (transparentSettingsWindow) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            return
        }
        window.setBackgroundDrawable(ColorDrawable(settingsColor(com.google.android.material.R.attr.colorSurfaceContainer)))
        val bars = if (SettingsUiPreferences.isDark(this)) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    /** Main settings can restore navigation/drafts. Standalone editors keep their unsaved work. */
    protected fun refreshKeyboardPalette() {
        if (isFinishing || isDestroyed) return
        val palette = SettingsKeyboardColors.current(this, SettingsUiPreferences.read(this)) ?: return
        if (palette != appliedKeyboardPalette) {
            appliedKeyboardPalette = palette
            recreate()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (floatingSettingsWindow || transparentSettingsWindow) return
        // Own the bottom/side insets once. Individual toolbars own only the status-bar inset.
        // Consuming these here also keeps existing editor/list helpers from adding them twice.
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val sides = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            view.updatePadding(left = sides.left, right = sides.right, bottom = bottom)
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun setSupportActionBar(toolbar: Toolbar?) {
        super.setSupportActionBar(toolbar)
        toolbar ?: return
        toolbar.elevation = 0f
        toolbar.minimumHeight = (64 * resources.displayMetrics.density).toInt()
        toolbar.setBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
        toolbar.setTitleTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurface))
        toolbar.setSubtitleTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        (toolbar as? MaterialToolbar)?.setNavigationIconTint(
            settingsColor(com.google.android.material.R.attr.colorOnSurface)
        )
        if (toolbar.parent !is CollapsingToolbarLayout) {
            val top = toolbar.paddingTop
            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
                val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                view.updatePadding(top = top + statusTop)
                view.minimumHeight = (64 * resources.displayMetrics.density).toInt() + top + statusTop
                insets
            }
        }
    }
}
