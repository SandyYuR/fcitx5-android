/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Bundle

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.editing.TextEditingLayoutResolver
import org.fcitx.fcitx5.android.input.editing.TextEditingStyle
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager

/**
 * 文本编辑布局可为常规风格选择当前 profile 中的任意布局；Foxy 风格仍使用独立的
 * text_editor 底栏，并启用触控板参数。
 */
class TextEditingSettingsFragment :
    ManagedPreferenceFragment(AppPrefs.getInstance().textEditing) {

    private val textEditing = AppPrefs.getInstance().textEditing
    private val promptPreferences = mutableListOf<Preference>()
    private var layoutOverridePreference: Preference? = null

    private data class LayoutEntryCache(
        val path: String,
        val lastModified: Long,
        val entries: List<String>
    )

    private var layoutEntryCache: LayoutEntryCache? = null

    private val styleChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key == textEditing.style.key) updateFoxyPreferencesEnabled()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val context = requireContext()
        val prompts = listOf(
            textEditing.cursorPromptMove to R.string.text_editing_cursor_prompt_move,
            textEditing.cursorPromptLongPress to R.string.text_editing_cursor_prompt_long_press,
            textEditing.cursorPromptSelecting to R.string.text_editing_cursor_prompt_selecting,
            textEditing.cursorPromptReleaseSelection to R.string.text_editing_cursor_prompt_release_selection
        )
        prompts.forEach { (managed, titleRes) ->
            promptPreferences += EditTextPreference(context).apply {
                key = managed.key
                title = context.getString(titleRes)
                dialogTitle = context.getString(titleRes)
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                    preference.text?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.text_editing_prompt_default)
                }
                setDefaultValue(managed.defaultValue)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            }.also(screen::addPreference)
        }

        layoutOverridePreference = Preference(context).apply {
            key = textEditing.layoutOverride.key
            title = context.getString(R.string.text_editing_layout_override_title)
            summary = buildLayoutOverrideSummary()
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showLayoutOverrideDialog()
                true
            }
        }.also(screen::addPreference)

        screen.addPreference(
            Preference(context).apply {
                key = "text_editing_layout_editor"
                title = context.getString(R.string.text_editing_edit_layout)
                summary = context.getString(R.string.text_editing_edit_layout_summary)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(context, TextKeyboardLayoutEditorActivity::class.java).apply {
                            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_LAYOUT, layoutEditorTarget())
                        }
                    )
                    true
                }
            }
        )

        textEditing.registerOnChangeListener(styleChangeListener)
        updateFoxyPreferencesEnabled()
    }

    private fun buildLayoutOverrideSummary(): String {
        val value = textEditing.layoutOverride.getValue().trim()
        val entries = collectLayoutEntries()
        val autoKey = TextEditingLayoutResolver.autoDetect(entries, TEXT_EDITING_PREFERRED_BASES)
        val autoSummary = getString(
            R.string.text_editing_layout_override_auto_summary,
            autoKey ?: getString(R.string.text_editing_layout_override_builtin)
        )
        return when {
            value.isEmpty() -> autoSummary
            value == TextEditingLayoutResolver.BUILTIN ->
                getString(R.string.text_editing_layout_override_builtin)
            value in entries -> value
            else -> getString(R.string.text_editing_layout_override_unavailable, value) +
                " · " + autoSummary
        }
    }

    private fun collectLayoutEntries(): List<String> {
        val file = ConfigProviders.provider.textKeyboardLayoutFile() ?: return emptyList()
        val path = file.absolutePath
        val lastModified = file.takeIf { it.exists() }?.lastModified() ?: 0L
        layoutEntryCache?.takeIf { it.path == path && it.lastModified == lastModified }
            ?.let { return it.entries }
        val source = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            LayoutDataManager(requireContext()).parseJsonText(source, file.name, fallbackToDefault = false)
        }.getOrNull() ?: return emptyList()
        return parsed.keys.sorted().also {
            layoutEntryCache = LayoutEntryCache(path, lastModified, it)
        }
    }

    private fun showLayoutOverrideDialog() {
        val entries = collectLayoutEntries()
        val items = listOf(
            getString(R.string.text_editing_layout_override_auto),
            getString(R.string.text_editing_layout_override_builtin)
        ) + entries
        val current = textEditing.layoutOverride.getValue().trim()
        val checked = when {
            current.isEmpty() -> 0
            current == TextEditingLayoutResolver.BUILTIN -> 1
            else -> items.indexOf(current).coerceAtLeast(0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editing_layout_override_title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                val value = when (which) {
                    0 -> ""
                    1 -> TextEditingLayoutResolver.BUILTIN
                    else -> entries[which - 2]
                }
                textEditing.layoutOverride.setValue(value)
                layoutOverridePreference?.summary = buildLayoutOverrideSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun layoutEditorTarget(): String {
        if (textEditing.style.getValue() == TextEditingStyle.FoxySwipe) {
            return TextEditingLayoutResolver.TEXT_EDITOR_LAYOUT
        }
        val entries = collectLayoutEntries()
        val selected = textEditing.layoutOverride.getValue().trim()
            .takeIf { it.isNotEmpty() && it != TextEditingLayoutResolver.BUILTIN && it in entries }
        return selected
            ?: TextEditingLayoutResolver.autoDetect(entries, TEXT_EDITING_PREFERRED_BASES)
            ?: TextEditingLayoutResolver.TEXT_EDITOR_LAYOUT
    }

    private fun updateFoxyPreferencesEnabled() {
        val enabled = textEditing.style.getValue() == TextEditingStyle.FoxySwipe
        promptPreferences.forEach { it.isEnabled = enabled }
        layoutOverridePreference?.isEnabled = !enabled
    }

    override fun onResume() {
        super.onResume()
        layoutOverridePreference?.summary = buildLayoutOverrideSummary()
        updateFoxyPreferencesEnabled()
    }

    override fun onDestroy() {
        textEditing.unregisterOnChangeListener(styleChangeListener)
        super.onDestroy()
    }

    private companion object {
        private val TEXT_EDITING_PREFERRED_BASES = listOf("rime", "default")
    }
}
