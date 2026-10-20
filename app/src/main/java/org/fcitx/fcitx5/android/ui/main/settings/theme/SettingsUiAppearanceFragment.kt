/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.Keep
import androidx.fragment.app.activityViewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences.Selection
import org.fcitx.fcitx5.android.data.theme.SettingsKeyboardColors
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference
import org.fcitx.fcitx5.android.ui.main.settingsPageToolbar
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.settingsColor

/** Draft controls use the same preference rows as other settings and the exact themed preview. */
class SettingsUiAppearanceFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var original = Selection()
    private var draft = Selection()
    private var applyItem: MenuItem? = null
    private lateinit var preview: PreviewPreference
    private lateinit var mode: ListPreference
    private lateinit var pureBlack: SwitchPreferenceCompat
    private lateinit var followKeyboard: SwitchPreferenceCompat
    private lateinit var dynamicColors: SwitchPreferenceCompat
    private lateinit var palettes: PalettePreference
    private lateinit var recipe: ListPreference
    private lateinit var contrast: ListPreference
    @Keep
    private val onKeyboardThemeChanged = ThemeManager.OnThemeChangeListener { preview.update(draft) }

    private val paletteOptions = listOf(
        SettingsUiPreferences.PALETTE_COMPAT to R.string.settings_ui_palette_compat,
        SettingsUiPreferences.PALETTE_SEA_SALT to R.string.settings_ui_palette_sea_salt,
        SettingsUiPreferences.PALETTE_PINE to R.string.settings_ui_palette_pine,
        SettingsUiPreferences.PALETTE_BERRY to R.string.settings_ui_palette_berry,
        SettingsUiPreferences.PALETTE_AMBER to R.string.settings_ui_palette_amber,
        SettingsUiPreferences.PALETTE_WISTERIA to R.string.settings_ui_palette_wisteria,
        SettingsUiPreferences.PALETTE_GRAPHITE to R.string.settings_ui_palette_graphite
    )
    private val recipeOptions = listOf(
        SettingsUiPreferences.RECIPE_CMF_2026 to R.string.settings_ui_recipe_cmf2026,
        SettingsUiPreferences.RECIPE_TONAL_2025 to R.string.settings_ui_recipe_tonal2025,
        SettingsUiPreferences.RECIPE_VIBRANT_2025 to R.string.settings_ui_recipe_vibrant2025,
        SettingsUiPreferences.RECIPE_NEUTRAL_2025 to R.string.settings_ui_recipe_neutral2025,
        SettingsUiPreferences.RECIPE_EXPRESSIVE_2025 to R.string.settings_ui_recipe_expressive2025,
        SettingsUiPreferences.RECIPE_CLASSIC_2021 to R.string.settings_ui_recipe_classic2021
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        original = SettingsUiPreferences.read(requireContext())
        draft = savedInstanceState?.let {
            original.copy(
                mode = it.getString("draft_mode") ?: original.mode,
                palette = it.getString("draft_palette") ?: original.palette,
                recipe = it.getString("draft_recipe") ?: original.recipe,
                contrast = it.getString("draft_contrast") ?: original.contrast,
                pureBlack = it.getBoolean("draft_pure_black", original.pureBlack),
                followKeyboardTheme = it.getBoolean("draft_follow_keyboard", original.followKeyboardTheme),
                manualPalette = it.getString("draft_manual_palette") ?: original.manualPalette
            )
        } ?: original
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        preview = PreviewPreference(requireContext()).also { preferenceScreen.addPreference(it) }

        category(R.string.settings_ui_display)
        mode = choice("settings_ui_mode", R.string.settings_ui_mode_title, listOf(
            SettingsUiPreferences.MODE_SYSTEM to R.string.settings_ui_mode_system,
            SettingsUiPreferences.MODE_LIGHT to R.string.settings_ui_mode_light,
            SettingsUiPreferences.MODE_DARK to R.string.settings_ui_mode_dark
        )) { draft = draft.copy(mode = it) }
        pureBlack = toggle(SettingsUiPreferences.KEY_PURE_BLACK,
            R.string.settings_ui_pure_black, R.string.settings_ui_pure_black_summary
        ) { draft = draft.copy(pureBlack = it) }

        category(R.string.settings_ui_color_title)
        followKeyboard = toggle(SettingsUiPreferences.KEY_FOLLOW_KEYBOARD,
            R.string.settings_ui_follow_keyboard, R.string.settings_ui_follow_keyboard_summary
        ) { draft = draft.copy(followKeyboardTheme = it) }
        if (!SettingsKeyboardColors.isAvailable()) {
            followKeyboard.isEnabled = false
            followKeyboard.setSummary(R.string.settings_ui_follow_keyboard_unavailable)
        }
        dynamicColors = toggle("settings_ui_dynamic_colors",
            R.string.settings_ui_dynamic_colors, R.string.settings_ui_dynamic_colors_summary
        ) { enabled ->
            draft = if (enabled) {
                draft.copy(palette = SettingsUiPreferences.PALETTE_SYSTEM,
                    manualPalette = draft.palette.takeUnless { it == SettingsUiPreferences.PALETTE_SYSTEM }
                        ?: draft.manualPalette)
            } else draft.copy(palette = draft.manualPalette)
        }
        palettes = PalettePreference(requireContext(), paletteOptions) { value ->
            draft = draft.copy(palette = value, manualPalette = value)
            render()
        }.also { preferenceScreen.addPreference(it) }
        recipe = choice("settings_ui_recipe", R.string.settings_ui_recipe_title, recipeOptions) {
            draft = draft.copy(recipe = it)
        }
        contrast = choice("settings_ui_contrast", R.string.settings_ui_contrast_title, listOf(
            SettingsUiPreferences.CONTRAST_STANDARD to R.string.settings_ui_contrast_standard,
            SettingsUiPreferences.CONTRAST_MEDIUM to R.string.settings_ui_contrast_medium,
            SettingsUiPreferences.CONTRAST_HIGH to R.string.settings_ui_contrast_high
        )) { draft = draft.copy(contrast = it) }

        category(R.string.settings_ui_interaction)
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.settings_ui_motion_title)
            setSummary(R.string.settings_ui_motion_summary)
            isIconSpaceReserved = false
            widgetLayoutResource = R.layout.preference_settings_navigation
            setOnPreferenceClickListener { navigateWithAnim(SettingsRoute.SettingsUiMotion); true }
        })
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.settings_ui_restore_default)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                draft = SettingsUiPreferences.defaultSelection()
                render()
                true
            }
        })
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setSummary(R.string.settings_ui_intro)
            isIconSpaceReserved = false
            isSelectable = false
        })
        render()
    }

    private fun category(title: Int) {
        preferenceScreen.addPreference(PreferenceCategory(requireContext()).apply { setTitle(title) })
    }

    private fun choice(
        key: String, title: Int, options: List<Pair<String, Int>>, changed: (String) -> Unit
    ) = ListPreference(requireContext()).apply {
        this.key = key
        setTitle(title)
        entries = options.map { getString(it.second) }.toTypedArray()
        entryValues = options.map { it.first }.toTypedArray()
        isPersistent = false
        isIconSpaceReserved = false
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, value -> changed(value.toString()); render(); true }
    }.also { preferenceScreen.addPreference(it) }

    private fun toggle(key: String, title: Int, summary: Int, changed: (Boolean) -> Unit) =
        SwitchPreferenceCompat(requireContext()).apply {
            this.key = key
            setTitle(title)
            setSummary(summary)
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, value -> changed(value as Boolean); render(); true }
        }.also { preferenceScreen.addPreference(it) }

    private fun render() {
        mode.value = draft.mode
        pureBlack.isChecked = draft.pureBlack
        pureBlack.isEnabled = draft.mode != SettingsUiPreferences.MODE_LIGHT
        val dynamic = draft.palette == SettingsUiPreferences.PALETTE_SYSTEM
        val keyboard = SettingsUiPreferences.usesKeyboardColors(draft)
        followKeyboard.isChecked = keyboard
        dynamicColors.isChecked = dynamic
        dynamicColors.isVisible = !keyboard && SettingsUiPreferences.systemDynamicColorsAvailable()
        palettes.isVisible = !keyboard && !dynamic
        palettes.update(draft)
        recipe.value = draft.recipe
        recipe.isVisible = !keyboard && !dynamic && draft.palette != SettingsUiPreferences.PALETTE_COMPAT
        contrast.value = draft.contrast
        contrast.isVisible = keyboard || !dynamic
        preview.update(draft)
        applyItem?.isEnabled = draft != original
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyItem = settingsPageToolbar().menu.item(R.string.apply, showAsAction = true) {
            SettingsUiPreferences.save(requireContext(), draft)
            requireActivity().recreate()
        }
        render()
        scrollToPendingPreference(viewModel)
    }

    override fun onDestroyView() {
        applyItem = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        ThemeManager.addOnChangedListener(onKeyboardThemeChanged)
        preview.update(draft)
    }

    override fun onStop() {
        ThemeManager.removeOnChangedListener(onKeyboardThemeChanged)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft_mode", draft.mode)
        outState.putString("draft_palette", draft.palette)
        outState.putString("draft_recipe", draft.recipe)
        outState.putString("draft_contrast", draft.contrast)
        outState.putBoolean("draft_pure_black", draft.pureBlack)
        outState.putBoolean("draft_follow_keyboard", draft.followKeyboardTheme)
        outState.putString("draft_manual_palette", draft.manualPalette)
    }

    private class PreviewPreference(context: Context) : Preference(context) {
        private var selection = Selection()
        private var keyboardPalette: SettingsKeyboardColors.Palette? = null
        init {
            key = "settings_ui_preview"
            layoutResource = R.layout.preference_settings_ui_preview
            isSelectable = false
        }
        fun update(value: Selection) {
            val palette = SettingsKeyboardColors.current(context, value)
            if (selection != value || keyboardPalette != palette) {
                selection = value
                keyboardPalette = palette
                notifyChanged()
            }
        }
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val container = holder.findViewById(R.id.settings_ui_preview_container) as ViewGroup
            val state = selection to keyboardPalette
            if (container.tag == state) return
            val themed = SettingsUiPreferences.themedContext(context, selection)
            val shape = ShapeAppearanceModel.builder().setAllCornerSizes(24f * context.resources.displayMetrics.density).build()
            container.background = MaterialShapeDrawable(shape).apply {
                fillColor = ColorStateList.valueOf(themed.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            }
            container.removeAllViews()
            LayoutInflater.from(themed).inflate(R.layout.settings_ui_appearance_preview, container, true)
            container.tag = state
        }
    }

    private class PalettePreference(
        context: Context, private val options: List<Pair<String, Int>>, private val changed: (String) -> Unit
    ) : Preference(context) {
        private var selection = Selection()
        init {
            key = "settings_ui_palette"
            layoutResource = R.layout.preference_settings_ui_palettes
            isSelectable = false
        }
        fun update(value: Selection) {
            if (selection != value) { selection = value; notifyChanged() }
        }
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val chips = holder.findViewById(R.id.settings_ui_palette_chips) as ChipGroup
            chips.setOnCheckedStateChangeListener(null)
            chips.removeAllViews()
            for ((value, label) in options) {
                val themed = SettingsUiPreferences.themedContext(context, selection.copy(palette = value))
                chips.addView(Chip(context).apply {
                    id = View.generateViewId()
                    tag = value
                    setText(label)
                    isCheckable = true
                    isChecked = value == selection.palette
                    chipIcon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(themed.settingsColor(androidx.appcompat.R.attr.colorPrimary))
                        setSize(24, 24)
                    }
                    chipIconTint = null
                    isChipIconVisible = true
                    chipIconSize = 20f * resources.displayMetrics.density
                })
            }
            chips.setOnCheckedStateChangeListener { group, ids ->
                val selected = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as String }
                if (selected != null && selected != selection.palette) changed(selected)
            }
        }
    }
}
