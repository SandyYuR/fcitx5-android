/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences.Selection
import org.fcitx.fcitx5.android.data.theme.SettingsKeyboardColors
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.search.scrollToPendingPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.settingsColor

/**
 * Appearance controls use the same preference rows as other settings.
 *
 * Each row is saved and applied on the spot: mode, theme resource, dynamic colors, the AMOLED
 * overlay and the keyboard palette are all decided in [org.fcitx.fcitx5.android.ui.common.SettingsUiActivity.onCreate],
 * so a change becomes visible through one rebuild of the page itself.
 */
class SettingsUiAppearanceFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var selection = Selection()
    private var rebuildScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable {
        rebuildScheduled = false
        val activity = activity ?: return@Runnable
        if (!isAdded || activity.isFinishing || activity.isDestroyed) return@Runnable
        // A closing preference dialog removes its fragment through a queued transaction; run it
        // first so the rebuild does not restore the dialog the user just dismissed.
        if (!parentFragmentManager.isStateSaved) parentFragmentManager.executePendingTransactions()
        activity.recreate()
    }
    private lateinit var mode: ListPreference
    private lateinit var pureBlack: SwitchPreferenceCompat
    private lateinit var followKeyboard: SwitchPreferenceCompat
    private lateinit var dynamicColors: SwitchPreferenceCompat
    private lateinit var palettes: PalettePreference
    private lateinit var recipe: ListPreference
    private lateinit var contrast: ListPreference

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
        selection = SettingsUiPreferences.read(requireContext())
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        category(R.string.settings_ui_display)
        mode = choice("settings_ui_mode", R.string.settings_ui_mode_title, listOf(
            SettingsUiPreferences.MODE_SYSTEM to R.string.settings_ui_mode_system,
            SettingsUiPreferences.MODE_LIGHT to R.string.settings_ui_mode_light,
            SettingsUiPreferences.MODE_DARK to R.string.settings_ui_mode_dark
        )) { commit(selection.copy(mode = it)) }
        pureBlack = toggle(SettingsUiPreferences.KEY_PURE_BLACK,
            R.string.settings_ui_pure_black, R.string.settings_ui_pure_black_summary
        ) { commit(selection.copy(pureBlack = it)) }

        category(R.string.settings_ui_color_title)
        followKeyboard = toggle(SettingsUiPreferences.KEY_FOLLOW_KEYBOARD,
            R.string.settings_ui_follow_keyboard, R.string.settings_ui_follow_keyboard_summary
        ) { commit(selection.copy(followKeyboardTheme = it)) }
        if (!SettingsKeyboardColors.isAvailable()) {
            followKeyboard.isEnabled = false
            followKeyboard.setSummary(R.string.settings_ui_follow_keyboard_unavailable)
        }
        dynamicColors = toggle("settings_ui_dynamic_colors",
            R.string.settings_ui_dynamic_colors, R.string.settings_ui_dynamic_colors_summary
        ) { enabled ->
            commit(
                if (enabled) {
                    selection.copy(palette = SettingsUiPreferences.PALETTE_SYSTEM,
                        manualPalette = selection.palette
                            .takeUnless { it == SettingsUiPreferences.PALETTE_SYSTEM }
                            ?: selection.manualPalette)
                } else selection.copy(palette = selection.manualPalette)
            )
        }
        palettes = PalettePreference(requireContext(), paletteOptions) { value ->
            commit(selection.copy(palette = value, manualPalette = value))
        }.also { preferenceScreen.addPreference(it) }
        recipe = choice("settings_ui_recipe", R.string.settings_ui_recipe_title, recipeOptions) {
            commit(selection.copy(recipe = it))
        }
        contrast = choice("settings_ui_contrast", R.string.settings_ui_contrast_title, listOf(
            SettingsUiPreferences.CONTRAST_STANDARD to R.string.settings_ui_contrast_standard,
            SettingsUiPreferences.CONTRAST_MEDIUM to R.string.settings_ui_contrast_medium,
            SettingsUiPreferences.CONTRAST_HIGH to R.string.settings_ui_contrast_high
        )) { commit(selection.copy(contrast = it)) }

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
            // Reset touches seven values at once and cannot be undone from this page, so it keeps
            // a confirmation step that the single-value rows deliberately do not have.
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_ui_restore_default)
                    .setMessage(R.string.settings_ui_restore_default_message)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        commit(SettingsUiPreferences.defaultSelection())
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
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
        setOnPreferenceChangeListener { _, value -> changed(value.toString()); true }
    }.also { preferenceScreen.addPreference(it) }

    private fun toggle(key: String, title: Int, summary: Int, changed: (Boolean) -> Unit) =
        SwitchPreferenceCompat(requireContext()).apply {
            this.key = key
            setTitle(title)
            setSummary(summary)
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, value -> changed(value as Boolean); true }
        }.also { preferenceScreen.addPreference(it) }

    /**
     * Persist and apply one step. Everything on this page is decided when the host activity is
     * created ([org.fcitx.fcitx5.android.ui.common.SettingsUiActivity] resolves the night mode in
     * `attachBaseContext`, then the theme, dynamic colors, AMOLED overlay and keyboard palette in
     * `onCreate`), so a change can only be shown by rebuilding that activity.
     *
     * Rebuilds are deferred to the next message and coalesced: a closing dialog finishes first,
     * and several taps in one burst cost one rebuild.
     */
    private fun commit(next: Selection) {
        if (next == selection) {
            render()
            return
        }
        SettingsUiPreferences.save(requireContext(), next)
        // save() normalizes; read back so the page never holds a value the engine would reject.
        selection = SettingsUiPreferences.read(requireContext())
        render()
        if (rebuildScheduled) return
        rebuildScheduled = true
        mainHandler.post(rebuildRunnable)
    }

    private fun render() {
        mode.value = selection.mode
        pureBlack.isChecked = selection.pureBlack
        pureBlack.isEnabled = selection.mode != SettingsUiPreferences.MODE_LIGHT
        val dynamic = selection.palette == SettingsUiPreferences.PALETTE_SYSTEM
        val keyboard = SettingsUiPreferences.usesKeyboardColors(selection)
        followKeyboard.isChecked = keyboard
        dynamicColors.isChecked = dynamic
        dynamicColors.isVisible = !keyboard && SettingsUiPreferences.systemDynamicColorsAvailable()
        palettes.isVisible = !keyboard && !dynamic
        palettes.update(selection)
        recipe.value = selection.recipe
        recipe.isVisible = !keyboard && !dynamic && selection.palette != SettingsUiPreferences.PALETTE_COMPAT
        contrast.value = selection.contrast
        contrast.isVisible = keyboard || !dynamic
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render()
        scrollToPendingPreference(viewModel)
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(rebuildRunnable)
        rebuildScheduled = false
        super.onDestroyView()
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
