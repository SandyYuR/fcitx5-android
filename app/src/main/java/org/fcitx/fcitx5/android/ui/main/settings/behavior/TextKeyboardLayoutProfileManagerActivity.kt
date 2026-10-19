/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.utils.settingsColor

/**
 * Hierarchy and section styling adapted from boomker/fcitx5-android@1d85a0d5.
 * Browsing only reads files: no migration, preset resets, profile metadata or ordering prefs.
 * File operations delegate to the existing editor and its validation/confirmation flows.
 */
class TextKeyboardLayoutProfileManagerActivity : SettingsUiActivity() {
    private data class Profile(val name: String, val keys: List<String>, val failed: Boolean, val builtin: Boolean)
    private val collapsed = mutableSetOf<String>()
    private var profiles = emptyList<Profile>()
    private var selected = UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
    private var reloadJob: Job? = null
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        selected = savedInstanceState?.getString("selected") ?: activeProfile()
        collapsed.addAll(savedInstanceState?.getStringArrayList("collapsed").orEmpty())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(settingsColor(android.R.attr.colorPrimary))
            setTitleTextColor(settingsColor(android.R.attr.textColorPrimary))
            elevation = dp(4f)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.text_keyboard_layout_manage_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            view.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)
        val header = horizontal().apply { setPadding(dp(16), dp(8), dp(8), dp(8)) }
        summary = label("", 14f)
        header.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(icon(R.drawable.ic_baseline_edit_24, R.string.text_keyboard_layout_file_rename) {
            openEditor(action = TextKeyboardLayoutEditorActivity.ACTION_RENAME_PROFILE)
        })
        header.addView(icon(R.drawable.ic_baseline_delete_24, R.string.text_keyboard_layout_file_delete) {
            openEditor(action = TextKeyboardLayoutEditorActivity.ACTION_DELETE_PROFILE)
        })
        root.addView(header)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = horizontal().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(settingsColor(android.R.attr.colorBackgroundFloating))
        }
        listOf(
            Triple(R.drawable.ic_baseline_plus_24, R.string.text_keyboard_layout_file_create, TextKeyboardLayoutEditorActivity.ACTION_CREATE_PROFILE),
            Triple(R.drawable.ic_baseline_arrow_downward_24, R.string.text_keyboard_layout_qr_import_scan, TextKeyboardLayoutEditorActivity.ACTION_IMPORT_PROFILE),
            Triple(R.drawable.ic_baseline_share_24, R.string.text_keyboard_layout_qr_export, TextKeyboardLayoutEditorActivity.ACTION_SHARE_PROFILE)
        ).forEach { (image, title, action) ->
            bottom.addView(icon(image, title) { openEditor(action = action) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected", selected)
        outState.putStringArrayList("collapsed", ArrayList(collapsed))
    }

    override fun onResume() {
        super.onResume()
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            profiles = withContext(Dispatchers.IO) {
                (UserConfigFiles.listTextKeyboardLayoutProfiles() + activeProfile()).distinct()
                    .sortedWith(compareBy({ it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
                    .map { name ->
                        val file = UserConfigFiles.textKeyboardLayoutJson(name)
                        val builtin = file != null && !file.exists()
                        val keys = if (builtin) listOf("default") else runCatching {
                            LayoutDataManager(this@TextKeyboardLayoutProfileManagerActivity)
                                .parseJsonText(file!!.readText(), file.name, fallbackToDefault = false).keys.toList()
                        }.getOrDefault(emptyList())
                        Profile(name, keys, keys.isEmpty(), builtin)
                    }
            }
            if (profiles.none { it.name == selected }) selected = activeProfile()
            render()
        }
    }

    private fun activeProfile(): String = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
    ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

    private fun display(name: String): String = if (name == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        getString(R.string.default_) else name

    private fun render() {
        summary.text = getString(R.string.text_keyboard_layout_manage_active, display(activeProfile())) + "\n" + display(selected)
        list.removeAllViews()
        profiles.forEach { profile ->
            val row = horizontal().apply { setPadding(dp(8), dp(8), 0, dp(4)) }
            val name = label(display(profile.name), 16f).apply {
                setTypeface(null, Typeface.BOLD)
                setOnClickListener { selected = profile.name; render() }
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(icon(R.drawable.ic_baseline_check_24, R.string.text_keyboard_layout_manage_activate) {
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(profile.name)
                ConfigProviders.provider = ConfigProviders.provider
                selected = profile.name
                render()
            }.apply {
                isEnabled = profile.name != activeProfile()
                if (!isEnabled) { setColorFilter(0xff4caf50.toInt()); alpha = 1f }
            })
            row.addView(icon(R.drawable.ic_baseline_edit_24, R.string.text_keyboard_layout_manage_edit) {
                selected = profile.name
                openEditor()
            })
            val isCollapsed = profile.name in collapsed
            row.addView(icon(R.drawable.ic_baseline_arrow_drop_down_24,
                if (isCollapsed) R.string.text_keyboard_layout_manage_expand else R.string.text_keyboard_layout_manage_collapse) {
                if (!collapsed.add(profile.name)) collapsed.remove(profile.name)
                render()
            }.apply { rotation = if (isCollapsed) -90f else 0f })
            list.addView(row)
            if (isCollapsed) return@forEach
            if (profile.failed) list.addView(label(getString(R.string.text_keyboard_layout_manage_read_failed), 14f))
            if (profile.builtin) list.addView(label(getString(R.string.text_keyboard_layout_manage_builtin), 14f))
            val entries = profile.keys.mapNotNull { key -> LayoutJsonUtils.parseEntryKey(key)?.let { key to it } }
            entries.groupBy { it.second.layoutName }.forEach { (base, layouts) ->
                layouts.sortedWith(compareBy({ it.second.subLayout != null }, { it.second.subLayout }, { it.second.isSplit }))
                    .forEach { (key, identity) ->
                        val sub = identity.subLayout
                        val title = if (sub == null && !identity.isSplit) base else
                            "• " + (sub ?: getString(R.string.default_)) +
                                if (identity.isSplit) " · " + getString(R.string.text_keyboard_layout_split_layout) else ""
                        val entryRow = horizontal().apply {
                            setPadding(dp(if (sub == null && !identity.isSplit) 12 else 32), dp(2), dp(4), dp(2))
                        }
                        entryRow.addView(label(title, if (sub == null) 15f else 14f), LinearLayout.LayoutParams(0, -2, 1f))
                        entryRow.addView(icon(R.drawable.ic_baseline_edit_24, R.string.text_keyboard_layout_manage_edit) {
                            selected = profile.name
                            openEditor(key)
                        })
                        list.addView(entryRow)
                    }
            }
        }
    }

    private fun openEditor(key: String? = null, action: String? = null) {
        startActivity(Intent(this, TextKeyboardLayoutEditorActivity::class.java).apply {
            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_PROFILE, selected)
            key?.let { putExtra(TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_LAYOUT, it) }
            action?.let { putExtra(TextKeyboardLayoutEditorActivity.EXTRA_MANAGER_ACTION, it) }
        })
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(settingsColor(android.R.attr.textColorPrimary))
    }

    private fun icon(image: Int, title: Int, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(image)
        contentDescription = getString(title)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setColorFilter(settingsColor(android.R.attr.textColorPrimary))
        val background = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
        setBackgroundResource(background.resourceId)
        setOnClickListener { click() }
    }
}
