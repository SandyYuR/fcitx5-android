/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesVirtualKeyboardPosition
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.SpaceKeyLabelMode
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.editing.TextEditingStyle
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.SymbolCatalogType
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
        val floatingKeyboardWidthPortraitRatio = float("floating_keyboard_width_portrait_ratio", 0f)
        val floatingKeyboardWidthLandscapeRatio = float("floating_keyboard_width_landscape_ratio", 0f)
        val floatingKeyboardHeightPortraitRatio = float("floating_keyboard_height_portrait_ratio", 0f)
        val floatingKeyboardHeightLandscapeRatio = float("floating_keyboard_height_landscape_ratio", 0f)
        // legacy single (orientation-agnostic) ratio prefs kept for one-time migration
        val floatingKeyboardWidthRatio = float("floating_keyboard_width_ratio", 0f)
        val floatingKeyboardHeightRatio = float("floating_keyboard_height_ratio", 0f)
        // legacy px prefs kept for one-time migration to the ratio-based values above
        val floatingKeyboardWidthLegacy = int("floating_keyboard_width", 0)
        val floatingKeyboardHeightLegacy = int("floating_keyboard_height", 0)
        val floatingKeyboardXPortraitRatio = float("floating_keyboard_x_portrait_ratio", -1f)
        val floatingKeyboardYPortraitRatio = float("floating_keyboard_y_portrait_ratio", -1f)
        val floatingKeyboardXLandscapeRatio = float("floating_keyboard_x_landscape_ratio", -1f)
        val floatingKeyboardYLandscapeRatio = float("floating_keyboard_y_landscape_ratio", -1f)
        // legacy px prefs kept for one-time migration to the ratio-based values above
        val floatingKeyboardXPortrait = int("floating_keyboard_x_portrait", -1)
        val floatingKeyboardYPortrait = int("floating_keyboard_y_portrait", -1)
        val floatingKeyboardXLandscape = int("floating_keyboard_x_landscape", -1)
        val floatingKeyboardYLandscape = int("floating_keyboard_y_landscape", -1)
        val oneHandOnRightPortrait = bool("one_hand_on_right_portrait", true)
        val oneHandOnRightLandscape = bool("one_hand_on_right_landscape", true)
        val floatingModeEnabled = bool("floating_mode_enabled", false)
        val oneHandModeEnabled = bool("one_hand_mode_enabled", false)

        /**
         * 横屏时自动使用浮动键盘（竖屏恢复停靠）。
         *
         * 与 [floatingModeEnabled] 是两回事：后者记录**用户手动**选择的键盘形态，
         * 前者只是一个「横屏自动切过去」的规则。因此自动切换只改运行时的 `isFloating`，
         * 不写回 [floatingModeEnabled]——否则一次横屏就把用户的选择永久改写成浮动，
         * 转回竖屏再也恢复不了停靠。
         */
        val autoFloatingLandscape = bool("auto_floating_landscape", false)
        val oneHandKeyboardWidthPortraitRatio = float("one_hand_keyboard_width_portrait_ratio", 0f)
        val oneHandKeyboardWidthLandscapeRatio = float("one_hand_keyboard_width_landscape_ratio", 0f)
        // legacy single (orientation-agnostic) ratio pref kept for one-time migration
        val oneHandKeyboardWidthRatio = float("one_hand_keyboard_width_ratio", 0f)
        // legacy px pref kept for one-time migration to the ratio-based value above
        val oneHandKeyboardWidthLegacy = int("one_hand_keyboard_width", 0)

        // Settings initialization flag
        val settingsInitialized = bool("settings_initialized", false)
        val splitKeyboardMigrated = bool("split_keyboard_migrated", false)
        val lastShareReceiveDirectory = string("last_share_receive_directory", "")
        val lastShareReceiveDirectoryRemembered = bool("last_share_receive_directory_remembered", false)
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        // 控制「全局选项」里 Fcitx 快捷键族是否显示。2026-09-29 从「高级 → 引擎配置」
        // 再上移到「高级」本页：引擎配置那个中转分组已取消，全局选项与中州韵设置
        // 改挂「输入与候选」下，附加组件与这个开关一起回到「高级」。
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // Some vivo and Xiaomi input windows can dispatch a key gesture more than once.
            DeviceUtil.isVivoOriginOS || DeviceUtil.isMIUI
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
        val keyboardHeightPercentBase = enumList(
            R.string.keyboard_height_percent_base,
            "keyboard_height_percent_base",
            // Base the height percent on the real screen size (Display.getRealSize)
            // instead of Resources.getDisplayMetrics, so the same percentage yields
            // a keyboard height that matches the physical screen.
            KeyboardHeightPercentBase.RealSize
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                0,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                0,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val customKeySound = ManagedPreference.PString(
            sharedPreferences,
            "custom_key_sound",
            ""
        ).apply { register() }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) { showVoiceInputButton.getValue() }

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Auto
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.None
        )
        val spaceKeyLabelMode = enumList(
            R.string.space_key_label_mode,
            "space_key_label_mode",
            SpaceKeyLabelMode.Default
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val textKeyboardLayoutProfile = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_profile",
            "default"
        ).apply { register() }
        /**
         * 数字输入框使用的自定义布局键（可为任意布局名或 ime:submode，如 rime:wanxiang）。
         * 留空表示使用内置数字键盘。仅存于应用设置，与布局文件解耦。
         */
        val numericLayoutOverride = ManagedPreference.PString(
            sharedPreferences,
            "numeric_layout_override",
            ""
        ).apply { register() }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                30,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                49,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val dm = appContext.resources.displayMetrics
            val shortEdgeDp = (minOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()
            val longEdgeDp = (maxOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()
            // Keep keyboard width >= 1/2 screen width: sidePadding <= width/4.
            val portraitMaxPaddingDp = (shortEdgeDp / 4).coerceAtLeast(0)
            val landscapeMaxPaddingDp = (longEdgeDp / 4).coerceAtLeast(0)
            val sidePaddingMaxDp = maxOf(portraitMaxPaddingDp, landscapeMaxPaddingDp)
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                0,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                0,
                0,
                sidePaddingMaxDp,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                0,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                0,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        /**
         * 工具栏（Kawaii Bar）自身尺寸百分比：栏高、图标、栏内按钮与文字标签一起缩放。
         *
         * 工具栏高度长期写死 40dp，用户无法让它更高更醒目，这个百分比就是把高度交出去。
         *
         * **候选项字号不在此列**。候选字由「字体设定」（`fontset.json` 的 `cand_font`）独立控制，
         * 那是用户为"字多大"专门准备的旋钮。曾让两者联动，结果是只想加高工具栏的人被顺手
         * 放大了字，还得回头调字体设定抵消；`AutoScaleTextView` 的 Proportional 模式又**只缩
         * 不放**，调大 `cand_font` 也顶不出栏高。两个旋钮必须分开。
         * 想让候选字更大：先把工具栏调高（给字腾出高度），再在「字体设定」里调大 `cand_font`。
         *
         * 换算逻辑在 [org.fcitx.fcitx5.android.input.bar.ToolbarMetrics]（纯函数，有单测）。
         * 下限 80% 而不是更小：工具栏同时承载状态区/隐藏键盘等按钮，再小图标就不可点了。
         */
        val toolbarHeightPercent = int(
            R.string.toolbar_height_percent,
            "toolbar_height_percent",
            100,
            80,
            200,
            "%",
            10
        )

        // ===== Split keyboard settings =====
        // Note: Threshold and gap are managed exclusively via SplitKeyboardCalibrationActivity
        // They are stored in Keyboard category to trigger InputView refresh when changed
        val splitKeyboardEnabled = switch(
            R.string.split_keyboard_enabled,
            "split_keyboard_enabled",
            true  // Default enabled; may be adjusted based on device type on first install
        )

        // Internal split keyboard settings (no UI, managed via calibration activity)
        val splitKeyboardThreshold = ManagedPreference.PInt(sharedPreferences, "split_keyboard_threshold", 470).apply { register() }
        val splitKeyboardGapPercent = ManagedPreference.PInt(sharedPreferences, "split_keyboard_gap_percent", 20).apply { register() }

        // When enabled, use landscape layout parameters (height/padding/etc.) while split keyboard is active
        val splitKeyboardUseLandscapeLayout = switch(
            R.string.split_keyboard_use_landscape_layout,
            "split_keyboard_use_landscape_layout",
            false
        )

        // 分体时，对奇数字符行（如 QWERTY 的 asdfghjkl / zxcvbnm）把几何中间键复制一份，
        // 左右两半各保留一枚（如 g、v），避免中间键只落在一侧够不到。默认开启。
        val splitKeyboardDuplicateMiddleKey = switch(
            R.string.split_keyboard_duplicate_middle,
            "split_keyboard_duplicate_middle",
            true,
            R.string.split_keyboard_duplicate_middle_summary
        )

        // 分体时是否强制左右两半在中缝处对齐（各自占满 (1-中缝)/2 的宽度）。
        //
        // 开启（默认，也是既有行为）：两侧都缩放到恰好半宽，中缝严格居中、上下行对齐。
        // 代价是**按键宽度被强行改写**——某一侧键多（或用户用 splitAfter 把断点大幅
        // 挪偏）时，该侧每一枚键都要被压窄到刚好填满半宽，与另一侧和合体状态下的键宽
        // 都不一致。
        //
        // 关闭：每枚键都保持**合体时的宽度**（弹性键同样只按行内剩余空间算，不受分体
        // 影响），两侧各自靠外沿固定，中缝不再是预留的固定宽度、而是两侧排完后**剩下的
        // 空间**。于是往一行里加空白占位键就能把这一侧往中缝方向推——占位键占多宽就推
        // 多远，且不会影响同行其它键的宽度（见 SplitRowWidths.unalignedWidths）。
        // 代价是中缝实际宽度随行内容变化、各行断点位置可能不齐。
        val splitKeyboardAlignHalves = switch(
            R.string.split_keyboard_align_halves,
            "split_keyboard_align_halves",
            true,
            R.string.split_keyboard_align_halves_summary
        )

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )
        val horizontalCandidateOverflowScroll = switch(
            R.string.horizontal_candidate_overflow_scroll,
            "horizontal_candidate_overflow_scroll",
            true,
            R.string.horizontal_candidate_overflow_scroll_summary
        )
        val highlightFirstCandidate = switch(
            R.string.highlight_first_candidate,
            "highlight_first_candidate",
            false,
            R.string.highlight_first_candidate_summary
        )
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                6,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                8,
                4,
                12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class TextEditing :
        ManagedPreferenceCategory(R.string.text_editing, sharedPreferences) {
        private val foxyEnabled: () -> Boolean = {
            style.getValue() == TextEditingStyle.FoxySwipe
        }

        val style = enumList(
            R.string.text_editing_style,
            "text_editing_style",
            TextEditingStyle.Default
        )
        val cursorStepDp = int(
            R.string.text_editing_cursor_step,
            "text_editing_cursor_step_dp",
            18,
            4,
            80,
            "dp",
            enableUiOn = foxyEnabled
        )
        val cursorLongPressDelay = int(
            R.string.text_editing_cursor_long_press_delay,
            "text_editing_cursor_long_press_delay",
            300,
            100,
            1200,
            "ms",
            10,
            enableUiOn = foxyEnabled
        )

        val cursorPromptMove = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_move",
            ""
        ).apply { register() }
        val cursorPromptLongPress = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_long_press",
            ""
        ).apply { register() }
        val cursorPromptSelecting = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_selecting",
            ""
        ).apply { register() }
        val cursorPromptReleaseSelection = ManagedPreference.PString(
            sharedPreferences,
            "text_editing_cursor_prompt_release_selection",
            ""
        ).apply { register() }
    }

    inner class Candidates : ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val physicalKeyboardHorizontalCandidateBar = switch(
            R.string.physical_keyboard_horizontal_candidate_bar,
            "physical_keyboard_horizontal_candidate_bar",
            false,
            R.string.physical_keyboard_horizontal_candidate_bar_summary
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val virtualKeyboardPosition = enumList(
            R.string.candidates_position,
            "virtual_keyboard_candidates_position",
            FloatingCandidatesVirtualKeyboardPosition.TopLeft
        ) {
            mode.getValue() == FloatingCandidatesMode.Always
        }

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val candidateHighlightRadius =
            int(R.string.candidate_highlight_radius, "candidate_highlight_radius", 2, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        init {
            val legacyKey = "clipboard_limit"
            val localKey = "clipboard_limit_local"
            val remoteKey = "clipboard_limit_remote"
            val mediaKey = "clipboard_limit_media"
            if (!sharedPreferences.contains(localKey) ||
                !sharedPreferences.contains(remoteKey) ||
                !sharedPreferences.contains(mediaKey)
            ) {
                val legacyValue = if (sharedPreferences.contains(legacyKey)) {
                    sharedPreferences.getInt(legacyKey, 100)
                } else {
                    100
                }
                sharedPreferences.edit {
                    if (!sharedPreferences.contains(localKey)) putInt(localKey, legacyValue)
                    if (!sharedPreferences.contains(remoteKey)) putInt(remoteKey, legacyValue)
                    if (!sharedPreferences.contains(mediaKey)) putInt(mediaKey, legacyValue)
                    remove(legacyKey)
                }
            }
            val timeoutKey = "clipboard_item_timeout"
            val timeoutValue = sharedPreferences.getInt(timeoutKey, 30)
            if (timeoutValue < 0) {
                sharedPreferences.edit {
                    putInt(timeoutKey, 0)
                }
            }
        }

        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimitLocal = int(
            R.string.clipboard_limit_local,
            "clipboard_limit_local",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitRemote = int(
            R.string.clipboard_limit_remote,
            "clipboard_limit_remote",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitMedia = int(
            R.string.clipboard_limit_media,
            "clipboard_limit_media",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            0,
            600,
            "s",
            step = 5
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )

        /**
         * 三份符号 catalog 当前选中的文件；[UserConfigFiles.SYMBOL_CATALOG_BUILTIN]
         * 表示使用 APK 内置数据，否则是用户自备 catalog 的文件名。
         *
         * 对应 Foxy 的 `foxy_symbol_catalogs`（键为 SYMBOLS/EMOJI/KAOMOJI，默认 `__builtin__`）。
         * 这里不注册 UI：选项来自目录扫描，用 [ManagedPreference.PString] + 自定义入口。
         */
        val symbolCatalogSymbols = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_symbols", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        val symbolCatalogEmoji = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_emoji", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        val symbolCatalogKaomoji = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_kaomoji", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        /** 按 catalog 种类取对应的选择偏好。 */
        fun catalogPreference(type: SymbolCatalogType): ManagedPreference.PString = when (type) {
            SymbolCatalogType.Symbols -> symbolCatalogSymbols
            SymbolCatalogType.Emoji -> symbolCatalogEmoji
            SymbolCatalogType.Kaomoji -> symbolCatalogKaomoji
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val textEditing = TextEditing().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround,
                internal.floatingKeyboardWidthPortraitRatio,
                internal.floatingKeyboardWidthLandscapeRatio,
                internal.floatingKeyboardHeightPortraitRatio,
                internal.floatingKeyboardHeightLandscapeRatio,
                internal.floatingKeyboardXPortraitRatio,
                internal.floatingKeyboardYPortraitRatio,
                internal.floatingKeyboardXLandscapeRatio,
                internal.floatingKeyboardYLandscapeRatio,
                internal.oneHandOnRightPortrait,
                internal.oneHandOnRightLandscape,
                internal.floatingModeEnabled,
                internal.oneHandModeEnabled,
                internal.oneHandKeyboardWidthPortraitRatio,
                internal.oneHandKeyboardWidthLandscapeRatio
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                textEditing,
                candidates,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
