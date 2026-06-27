/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Macro 步骤类型
 * 参考 Vial 的 macro 设计，支持以下操作：
 * - Down: 按下键（不释放）
 * - Up: 释放键
 * - Tap: 点击并释放键
 * - Text: 提交文本
 * - Clipboard: 剪贴板操作（copy/cut/paste/selectAll/undo/redo）
 * - Shortcut: 快捷键（自动展开为 modifier down + key tap + modifier up）
 */
@Serializable
sealed class MacroStep {
    @Serializable @SerialName("down")
    data class Down(val keys: List<KeyRef>) : MacroStep()

    @Serializable @SerialName("up")
    data class Up(val keys: List<KeyRef>) : MacroStep()

    @Serializable @SerialName("tap")
    data class Tap(val keys: List<KeyRef>) : MacroStep()

    @Serializable @SerialName("text")
    data class Text(val text: String) : MacroStep()

    @Serializable @SerialName("edit")
    data class Edit(val action: String) : MacroStep()

    @Serializable @SerialName("shortcut")
    data class Shortcut(val modifiers: List<KeyRef>, val key: KeyRef) : MacroStep()

    @Serializable @SerialName("app_action")
    data class AppAction(val id: String) : MacroStep()

    @Serializable @SerialName("layer_switch")
    data class LayerSwitch(val mode: KeyAction.LayerSwitchMode, val target: String) : MacroStep()
}

@Serializable
sealed class KeyRef {
    @Serializable @SerialName("fcitx")
    data class Fcitx(val code: String) : KeyRef()

    @Serializable @SerialName("android")
    data class Android(val code: Int) : KeyRef()
}

@Serializable
data class MacroAction(val steps: List<MacroStep>) : KeyAction()

sealed class KeyAction {
    @Serializable
    enum class LayerSwitchMode { TO, OSL }

    data class FcitxKeyAction(
        val act: String,
        val code: Int = ScancodeMapping.charToScancode(act[0]),
        val states: KeyStates = KeyStates.Virtual,
        val up: Boolean = false
    ) : KeyAction()

    data class SymAction(val sym: KeySym, val states: KeyStates = KeyStates.Virtual) : KeyAction()

    data class CommitAction(val text: String) : KeyAction()

    data class CapsAction(val lock: Boolean) : KeyAction()

    data object QuickPhraseAction : KeyAction()

    data object UnicodeAction : KeyAction()

    data object LangSwitchAction : KeyAction()

    data object ShowInputMethodPickerAction : KeyAction()

    data class LayoutSwitchAction(val act: String = "") : KeyAction()

    data class MoveSelectionAction(val start: Int = 0, val end: Int = 0) : KeyAction()

    data class DeleteSelectionAction(val totalCnt: Int = 0) : KeyAction()

    data class PickerSwitchAction(val key: PickerWindow.Key? = null) : KeyAction()

    data object SpaceLongPressAction : KeyAction()

    data object VoiceInputHoldEnd : KeyAction()

    data class LayerSwitchAction(
        val mode: LayerSwitchMode,
        val target: String
    ) : KeyAction()

    /**
     * Internal notification from BaseKeyboard after a MacroAction has performed
     * non-layer work. KeyboardWindow uses this to consume one-shot layers.
     */
    data object MacroConsumedAction : KeyAction()
}
