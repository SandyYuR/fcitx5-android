/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.graphics.Color
import android.os.Bundle
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
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.utils.settingsColor

/** Base for app-owned settings shells; IME windows and transient overlay activities do not use it. */
abstract class SettingsUiActivity : AppCompatActivity() {

    @OptIn(PredictiveBackControl::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // FragmentManager requires this switch before any transactions, including restoration.
        // The motion settings screen recreates its activity when this preference changes.
        FragmentManager.enablePredictiveBack(
            android.os.Build.VERSION.SDK_INT >= 34 &&
                SettingsUiMotionPreferences.read(this).predictiveBack
        )
        // AppCompat's configuration must agree with the selected palette, including Monet.
        delegate.localNightMode = when (SettingsUiPreferences.read(this).mode) {
            SettingsUiPreferences.MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsUiPreferences.MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        setTheme(SettingsUiPreferences.themeResource(this))
        if (SettingsUiPreferences.usesSystemDynamicColors(this)) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        // AppCompat reapplies its base theme in onCreate; surface overrides must follow it.
        SettingsUiPreferences.applySurfaceOptions(this)
        val bars = if (SettingsUiPreferences.isDark(this)) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
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
