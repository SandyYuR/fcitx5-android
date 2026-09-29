/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceLongPressBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    None(R.string.space_behavior_none),

    /**
     * 长按持续重复输入空格。
     *
     * 与其它取值不同，这条**不经过** [KeyAction.SpaceLongPressAction]：重复由键盘的
     * `KeyView.repeatEnabled` 机制驱动（见 `BaseKeyboard.applySpaceRepeatBinding`），
     * 这样节奏、抬手/取消时的停止都与退格键连删走同一套已验证逻辑。
     * 因此 `CommonKeyActionListener` 里对应的分支是空实现。
     */
    Repeat(R.string.space_behavior_repeat),
    Enumerate(R.string.space_behavior_enumerate),
    ToggleActivate(R.string.space_behavior_activate),
    ShowPicker(R.string.space_behavior_picker),
    VoiceInput(R.string.space_behavior_voice_input),
    VoiceInputHold(R.string.space_behavior_voice_input_hold);
}
