/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences.Selection

/** Settings shell appearance controls. Draft values only become durable when Apply is pressed. */
class SettingsUiAppearanceFragment : Fragment() {

    private var original = Selection()
    private var draft = Selection()

    private val paletteOptions by lazy {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(SettingsUiPreferences.PALETTE_SYSTEM to R.string.settings_ui_palette_system)
            }
            add(SettingsUiPreferences.PALETTE_COMPAT to R.string.settings_ui_palette_compat)
            add(SettingsUiPreferences.PALETTE_SEA_SALT to R.string.settings_ui_palette_sea_salt)
            add(SettingsUiPreferences.PALETTE_PINE to R.string.settings_ui_palette_pine)
            add(SettingsUiPreferences.PALETTE_BERRY to R.string.settings_ui_palette_berry)
            add(SettingsUiPreferences.PALETTE_AMBER to R.string.settings_ui_palette_amber)
            add(SettingsUiPreferences.PALETTE_WISTERIA to R.string.settings_ui_palette_wisteria)
            add(SettingsUiPreferences.PALETTE_GRAPHITE to R.string.settings_ui_palette_graphite)
        }
    }

    private val recipeOptions = listOf(
        SettingsUiPreferences.RECIPE_CMF_2026 to R.string.settings_ui_recipe_cmf2026,
        SettingsUiPreferences.RECIPE_TONAL_2025 to R.string.settings_ui_recipe_tonal2025,
        SettingsUiPreferences.RECIPE_VIBRANT_2025 to R.string.settings_ui_recipe_vibrant2025,
        SettingsUiPreferences.RECIPE_NEUTRAL_2025 to R.string.settings_ui_recipe_neutral2025,
        SettingsUiPreferences.RECIPE_EXPRESSIVE_2025 to R.string.settings_ui_recipe_expressive2025,
        SettingsUiPreferences.RECIPE_CLASSIC_2021 to R.string.settings_ui_recipe_classic2021
    )

    private val contrastOptions = listOf(
        SettingsUiPreferences.CONTRAST_STANDARD to R.string.settings_ui_contrast_standard,
        SettingsUiPreferences.CONTRAST_MEDIUM to R.string.settings_ui_contrast_medium,
        SettingsUiPreferences.CONTRAST_HIGH to R.string.settings_ui_contrast_high
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_ui_appearance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        original = SettingsUiPreferences.read(requireContext())
        draft = savedInstanceState?.let {
            original.copy(
                mode = it.getString("draft_mode") ?: original.mode,
                palette = it.getString("draft_palette") ?: original.palette,
                recipe = it.getString("draft_recipe") ?: original.recipe,
                contrast = it.getString("draft_contrast") ?: original.contrast
            )
        } ?: original
        val scrollContent = view.findViewById<View>(R.id.settings_ui_scroll)
        val initialBottomPadding = scrollContent.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollContent) { target, insets ->
            val bottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom
            target.updatePadding(bottom = initialBottomPadding + bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(scrollContent)

        val modeGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.settings_ui_mode_group)
        if (resources.configuration.fontScale >= 1.3f) {
            modeGroup.orientation = android.widget.LinearLayout.VERTICAL
            for (index in 0 until modeGroup.childCount) {
                modeGroup.getChildAt(index).layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
        modeGroup.check(modeButtonId(draft.mode))
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            draft = draft.copy(mode = modeFromButtonId(checkedId))
            render(view)
        }

        view.findViewById<MaterialButton>(R.id.settings_ui_palette_button).setOnClickListener {
            showOptions(
                title = R.string.settings_ui_palette_title,
                options = paletteOptions,
                selected = draft.palette
            ) { value ->
                draft = draft.copy(palette = value)
                render(view)
            }
        }
        view.findViewById<MaterialButton>(R.id.settings_ui_recipe_button).setOnClickListener {
            showOptions(
                title = R.string.settings_ui_recipe_title,
                options = recipeOptions,
                selected = draft.recipe
            ) { value ->
                draft = draft.copy(recipe = value)
                render(view)
            }
        }

        view.findViewById<Slider>(R.id.settings_ui_contrast_slider).apply {
            value = contrastOptions.indexOfFirst { it.first == draft.contrast }.coerceAtLeast(0).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    draft = draft.copy(contrast = contrastOptions[value.toInt()].first)
                    render(view)
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.settings_ui_restore_button).setOnClickListener {
            draft = Selection()
            modeGroup.check(modeButtonId(draft.mode))
            view.findViewById<Slider>(R.id.settings_ui_contrast_slider).value = 0f
            render(view)
        }
        view.findViewById<MaterialButton>(R.id.settings_ui_cancel_button).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<MaterialButton>(R.id.settings_ui_apply_button).setOnClickListener {
            if (draft == original) {
                findNavController().navigateUp()
                return@setOnClickListener
            }
            SettingsUiPreferences.save(requireContext(), draft)
            requireActivity().recreate()
        }
        render(view)
    }

    private fun render(view: View) {
        val familyName = getString(labelFor(paletteOptions, draft.palette))
        val recipeName = getString(labelFor(recipeOptions, draft.recipe))
        view.findViewById<MaterialButton>(R.id.settings_ui_palette_button).text =
            getString(R.string.settings_ui_palette_button, familyName)
        view.findViewById<MaterialButton>(R.id.settings_ui_recipe_button).apply {
            text = getString(R.string.settings_ui_recipe_button, recipeName)
            isVisible = draft.palette != SettingsUiPreferences.PALETTE_SYSTEM &&
                draft.palette != SettingsUiPreferences.PALETTE_COMPAT
        }

        val isSystemPalette = draft.palette == SettingsUiPreferences.PALETTE_SYSTEM
        view.findViewById<Slider>(R.id.settings_ui_contrast_slider).isVisible = !isSystemPalette
        view.findViewById<TextView>(R.id.settings_ui_contrast_value).apply {
            isVisible = !isSystemPalette
            text = getString(labelFor(contrastOptions, draft.contrast))
        }
        view.findViewById<TextView>(R.id.settings_ui_contrast_label).isVisible = !isSystemPalette

        val preview = view.findViewById<ViewGroup>(R.id.settings_ui_preview_container)
        if (preview.tag != draft) {
            val themed = SettingsUiPreferences.themedContext(requireContext(), draft)
            preview.removeAllViews()
            LayoutInflater.from(themed).inflate(R.layout.settings_ui_appearance_preview, preview, true)
            preview.tag = draft
        }
        view.findViewById<MaterialButton>(R.id.settings_ui_apply_button).isEnabled = draft != original
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft_mode", draft.mode)
        outState.putString("draft_palette", draft.palette)
        outState.putString("draft_recipe", draft.recipe)
        outState.putString("draft_contrast", draft.contrast)
    }

    private fun showOptions(
        title: Int,
        options: List<Pair<String, Int>>,
        selected: String,
        onSelected: (String) -> Unit
    ) {
        val labels = options.map { getString(it.second) }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                onSelected(options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun labelFor(options: List<Pair<String, Int>>, id: String): Int =
        options.firstOrNull { it.first == id }?.second ?: options.first().second

    private fun modeButtonId(mode: String): Int = when (mode) {
        SettingsUiPreferences.MODE_LIGHT -> R.id.settings_ui_mode_light
        SettingsUiPreferences.MODE_DARK -> R.id.settings_ui_mode_dark
        else -> R.id.settings_ui_mode_system
    }

    private fun modeFromButtonId(buttonId: Int): String = when (buttonId) {
        R.id.settings_ui_mode_light -> SettingsUiPreferences.MODE_LIGHT
        R.id.settings_ui_mode_dark -> SettingsUiPreferences.MODE_DARK
        else -> SettingsUiPreferences.MODE_SYSTEM
    }
}
