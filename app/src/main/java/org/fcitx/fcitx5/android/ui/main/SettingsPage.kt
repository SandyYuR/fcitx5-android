/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.SettingsPageBinding
import org.fcitx.fcitx5.android.ui.main.settings.AddonDirProfileManagerFragment
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.GenericMultiSelectFragment
import org.fcitx.fcitx5.android.ui.main.settings.ListFragment
import org.fcitx.fcitx5.android.ui.main.settings.PunctuationEditorFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.AdvancedSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.CandidatesSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.DataBackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardModesFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SymbolSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TextEditingSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import org.fcitx.fcitx5.android.ui.main.settings.theme.SettingsUiAppearanceFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.SettingsUiMotionFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.lazyRoute

/**
 * Resolve the destination's own title before its first frame, and move its header and body as
 * one view. Nested pages (such as the keyboard-theme tabs) retain their existing parent header.
 */
internal fun Fragment.withSettingsPage(content: View): View {
    val host = activity as? MainActivity ?: return content
    if (parentFragment !is NavHostFragment) return content

    val page = SettingsPageBinding.inflate(layoutInflater)
    applySettingsPageMotion()
    val home = this is MainFragment
    val title = settingsPageTitle()
    page.collapsingToolbar.isTitleEnabled = home
    if (home) {
        page.collapsingToolbar.title = title
        page.collapsingToolbar.updateLayoutParams<AppBarLayout.LayoutParams> {
            height = (resources.displayMetrics.density *
                (64f + 48f * resources.configuration.fontScale.coerceAtLeast(1f))).toInt()
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
        }
    } else {
        page.toolbar.title = title
    }
    page.toolbar.setNavigationOnClickListener { host.onSupportNavigateUp() }
    if (home) host.populateHomeMenu(page.toolbar.menu)
    page.settingsPageContent.addView(content, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    ViewCompat.setOnApplyWindowInsetsListener(page.root) { _, insets ->
        page.appBar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
        insets
    }
    ViewCompat.requestApplyInsets(page.root)
    return page.root
}

/** Menus stay on their own page while both destinations are visible during a transition. */
internal fun Fragment.settingsPageToolbar(): MaterialToolbar = requireView().findViewById(R.id.toolbar)

private fun Fragment.settingsPageTitle(): String {
    // Use the Fragment's arguments even while NavController is restoring or preparing a pop.
    // No lifecycle callback or asynchronous configuration load needs to replace the title later.
    when (this) {
        is MainFragment -> return AppUtil.appLabel(requireContext())
        is FcitxPreferenceFragment -> return getPageTitle()
        is SettingsGroupFragment -> {
            val args by lazyRoute<SettingsRoute.SettingsGroup>()
            return SettingsGroupSpecs.find(args.id)?.let { getString(it.title) }
                ?: AppUtil.appLabel(requireContext())
        }
        is KeyboardGroupFragment -> {
            val args by lazyRoute<SettingsRoute.KeyboardGroup>()
            return getString(KeyboardGroupFragment.groupTitleRes(args.group))
        }
        is ListFragment -> {
            val args by lazyRoute<SettingsRoute.ListConfig>(SettingsRoute.ListConfig.Params.TypeMap)
            return args.desc.description ?: args.desc.name
        }
        is PunctuationEditorFragment -> {
            val args by lazyRoute<SettingsRoute.Punctuation>()
            return args.title
        }
        is GenericMultiSelectFragment -> {
            val args by lazyRoute<SettingsRoute.MultiSelect>()
            return args.title
        }
        is AddonDirProfileManagerFragment -> {
            val args by lazyRoute<SettingsRoute.AddonDirProfileManager>()
            return args.title
        }
    }
    return getString(when (this) {
        is ThemeFragment -> R.string.theme
        is SettingsUiAppearanceFragment -> R.string.settings_ui_appearance
        is SettingsUiMotionFragment -> R.string.settings_ui_motion_title
        is KeyboardSettingsFragment -> R.string.virtual_keyboard
        is CandidatesSettingsFragment -> R.string.candidates_window
        is ClipboardSettingsFragment -> R.string.clipboard
        is SymbolSettingsFragment -> R.string.emoji_and_symbols
        is TextEditingSettingsFragment -> R.string.text_editing
        is KeyboardModesFragment -> R.string.keyboard_modes_title
        is DataBackupFragment -> R.string.settings_group_data
        is AdvancedSettingsFragment -> R.string.advanced
        is DeveloperFragment -> R.string.developer
        is LicensesFragment -> R.string.license
        is AboutFragment -> R.string.about
        else -> error("Missing settings page title for ${javaClass.name}")
    })
}
