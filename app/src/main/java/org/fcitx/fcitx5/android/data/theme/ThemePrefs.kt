/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themePreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Theme,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedThemePreference {
        val pref = ManagedThemePreference(sharedPreferences, key, defaultValue)
        val ui = ManagedThemePreferenceUi(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    val keyBorder = switch(R.string.key_border, "key_border", true)

    val keyBorderStroke = switch(
        R.string.key_border_stroke, "key_border_stroke", false,
        enableUiOn = { keyBorder.getValue() }
    )

    val specialKeyOvalShape = switch(R.string.special_key_oval_shape, "special_key_oval_shape", true)

    val keyRippleEffect = switch(R.string.key_ripple_effect, "key_ripple_effect", false)

    val moveMainTextForAltLabel = switch(
        R.string.move_main_text_for_alt_label,
        "move_main_text_for_alt_label",
        true
    )

    val keyHorizontalMargin: ManagedPreference.PInt
    val keyHorizontalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_horizontal_margin,
            R.string.portrait,
            "key_horizontal_margin",
            2,
            R.string.landscape,
            "key_horizontal_margin_landscape",
            3,
            0,
            24,
            "dp"
        )
        keyHorizontalMargin = primary
        keyHorizontalMarginLandscape = secondary
    }

    val keyVerticalMargin: ManagedPreference.PInt
    val keyVerticalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_vertical_margin,
            R.string.portrait,
            "key_vertical_margin",
            3,
            R.string.landscape,
            "key_vertical_margin_landscape",
            4,
            0,
            24,
            "dp"
        )
        keyVerticalMargin = primary
        keyVerticalMarginLandscape = secondary
    }

    val keyRadius = int(R.string.key_radius, "key_radius", 12, 0, 48, "dp")

    val preeditRadius = int(R.string.preedit_radius, "preedit_radius", 0, 0, 48, "dp")

    val toolbarRadius = int(R.string.toolbar_radius, "toolbar_radius", 0, 0, 48, "dp")

    val textEditingButtonRadius =
        int(R.string.text_editing_button_radius, "text_editing_button_radius", 12, 0, 48, "dp")

    val clipboardEntryRadius =
        int(R.string.clipboard_entry_radius, "clipboard_entry_radius", 8, 0, 48, "dp")

    // 工具栏候选条里被选中候选的高亮圆角（dp）。注意**不是**独立候选窗口——那边由
    // AppPrefs.candidates.candidateHighlightRadius 控制。此项作用于 CandidateItemUi
    // 的 activeBackground。
    val candidateBarHighlightRadius =
        int(R.string.candidate_bar_highlight_radius, "candidate_bar_highlight_radius", 4, 0, 48, "dp")

    // 工具栏候选高亮**内部**的水平内边距（dp），即「高亮边框 ↔ 候选文字」的左右间距。
    // 作用于 CandidateItemUi 的高亮层（内层 content），独立候选窗口不受影响。0 表示文字
    // 紧贴高亮边框。
    //
    // 它不再是「高亮距格子边缘的距离」：格子边缘 ↔ 高亮之间固定为 4dp（上下左右都一样，
    // 见 HorizontalCandidateComponent.ITEM_HORIZONTAL_PADDING_DP），所以高亮整体离格子边缘
    // 的距离恒为 `4dp + 本项`，且**上下也不再贴边**。
    //
    // 之所以把内缩改到「高亮层自己的内边距」上，是因为高亮此前画在 item 根视图上并按格子宽度
    // 铺满，文字到高亮边框的间距 = 格子内边距 − 内缩量，短候选时只剩 2dp 左右，看起来就是
    // 文字贴着高亮框（且候选变长/被 flexGrow 拉宽时还会再变）。现在高亮层宽度恒等于
    // 「文字 + 左右各本项」，间距与候选长短、拉伸都无关。
    //
    // 默认值 8dp 与 boomker/fcitx5-android 的 HORIZONTAL_CANDIDATE_HIGHLIGHT_PADDING_DP 一致；
    // 改默认值不影响已经调过此项的老用户（SharedPreferences 里存过值就以存值为准）。
    val candidateBarHighlightInset =
        int(R.string.candidate_bar_highlight_inset, "candidate_bar_highlight_inset", 8, 0, 24, "dp")

    enum class PunctuationPosition(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.punctuation_pos_none),
        Bottom(R.string.punctuation_pos_bottom),
        TopRight(R.string.punctuation_pos_top_right),
        TopCenter(R.string.punctuation_pos_top_center);
    }

    val punctuationPosition = enumList(
        R.string.punctuation_position,
        "punctuation_position",
        PunctuationPosition.Bottom
    )

    enum class NavbarBackground(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.navbar_bkg_none),
        ColorOnly(R.string.navbar_bkg_color_only),
        Full(R.string.navbar_bkg_full);
    }

    val navbarBackground = enumList(
        R.string.navbar_background,
        "navbar_background",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) NavbarBackground.Full else NavbarBackground.ColorOnly,
        // 35+ forces edge to edge
        enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM }
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            sharedPreferences.edit {
                remove(this@apply.key)
            }
        }
    }

    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        true,
        summary = R.string.follow_system_day_night_theme_summary
    )

    val lightModeTheme = themePreference(
        R.string.light_mode_theme,
        "light_mode_theme",
        if (BuildConfig.DEBUG) ThemePreset.MaterialLight else ThemePreset.PixelLight,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val darkModeTheme = themePreference(
        R.string.dark_mode_theme,
        "dark_mode_theme",
        if (BuildConfig.DEBUG) ThemePreset.MaterialDark else ThemePreset.PixelDark,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )
}
