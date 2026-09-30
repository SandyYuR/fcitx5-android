/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.input.editing.TextEditingStyle

/**
 * 文本编辑设置：默认风格时只显示风格选择，Foxy 风格启用触控板参数。
 * 底部按键布局由同一份 text_editor 键盘布局文件负责，入口仍在键盘自定义页。
 */
class TextEditingSettingsFragment :
    ManagedPreferenceFragment(AppPrefs.getInstance().textEditing) {

    private val textEditing = AppPrefs.getInstance().textEditing
    private val promptPreferences = mutableListOf<Preference>()

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

        screen.addPreference(
            Preference(context).apply {
                key = "text_editing_layout_editor"
                title = context.getString(R.string.text_editing_edit_layout)
                summary = context.getString(R.string.text_editing_edit_layout_summary)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(context, TextKeyboardLayoutEditorActivity::class.java).apply {
                            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_LAYOUT, "text_editor")
                        }
                    )
                    true
                }
            }
        )

        textEditing.registerOnChangeListener(styleChangeListener)
        updateFoxyPreferencesEnabled()
    }

    private fun updateFoxyPreferencesEnabled() {
        val enabled = textEditing.style.getValue() == TextEditingStyle.FoxySwipe
        promptPreferences.forEach { it.isEnabled = enabled }
    }

    override fun onDestroy() {
        textEditing.unregisterOnChangeListener(styleChangeListener)
        super.onDestroy()
    }
}
