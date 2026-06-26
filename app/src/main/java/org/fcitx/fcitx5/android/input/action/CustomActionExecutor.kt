/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SplitKeyboardCalibrationActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor.ImeWebEditorBridgeServer
import org.fcitx.fcitx5.android.utils.AppUtil

private val FCITX_KEY_TO_ANDROID = mapOf(
    "Ctrl_L" to KeyEvent.KEYCODE_CTRL_LEFT,
    "Ctrl_R" to KeyEvent.KEYCODE_CTRL_RIGHT,
    "Shift_L" to KeyEvent.KEYCODE_SHIFT_LEFT,
    "Shift_R" to KeyEvent.KEYCODE_SHIFT_RIGHT,
    "Alt_L" to KeyEvent.KEYCODE_ALT_LEFT,
    "Alt_R" to KeyEvent.KEYCODE_ALT_RIGHT,
    "Meta_L" to KeyEvent.KEYCODE_META_LEFT,
    "Meta_R" to KeyEvent.KEYCODE_META_RIGHT,
    "Enter" to KeyEvent.KEYCODE_ENTER,
    "Tab" to KeyEvent.KEYCODE_TAB,
    "Escape" to KeyEvent.KEYCODE_ESCAPE,
    "Space" to KeyEvent.KEYCODE_SPACE,
    "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
    "BackSpace" to KeyEvent.KEYCODE_DEL,
    "Home" to KeyEvent.KEYCODE_MOVE_HOME,
    "End" to KeyEvent.KEYCODE_MOVE_END,
    "Page_Up" to KeyEvent.KEYCODE_PAGE_UP,
    "Page_Down" to KeyEvent.KEYCODE_PAGE_DOWN,
    "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
    "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "Up" to KeyEvent.KEYCODE_DPAD_UP,
    "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
    "F1" to KeyEvent.KEYCODE_F1, "F2" to KeyEvent.KEYCODE_F2, "F3" to KeyEvent.KEYCODE_F3,
    "F4" to KeyEvent.KEYCODE_F4, "F5" to KeyEvent.KEYCODE_F5, "F6" to KeyEvent.KEYCODE_F6,
    "F7" to KeyEvent.KEYCODE_F7, "F8" to KeyEvent.KEYCODE_F8, "F9" to KeyEvent.KEYCODE_F9,
    "F10" to KeyEvent.KEYCODE_F10, "F11" to KeyEvent.KEYCODE_F11, "F12" to KeyEvent.KEYCODE_F12,
    "NumLock" to KeyEvent.KEYCODE_NUM_LOCK,
    "Caps_Lock" to KeyEvent.KEYCODE_CAPS_LOCK,
) + ('A'..'Z').associate { it.toString() to KeyEvent.KEYCODE_A + (it - 'A') } +
    ('0'..'9').associate { it.toString() to KeyEvent.KEYCODE_0 + (it - '0') }

private val EDIT_ACTION_MAP = mapOf(
    "copy" to android.R.id.copy,
    "cut" to android.R.id.cut,
    "paste" to android.R.id.paste,
    "selectAll" to android.R.id.selectAll,
    "undo" to android.R.id.undo,
    "redo" to android.R.id.redo,
)

private val ROUTE_MAP = mapOf(
    "addon_list" to SettingsRoute.AddonList,
    "table_input_methods" to SettingsRoute.TableInputMethods,
    "quick_phrase_list" to SettingsRoute.QuickPhraseList,
    "pinyin_custom_phrase" to SettingsRoute.PinyinCustomPhrase,
    "pinyin_dict" to SettingsRoute.PinyinDict(),
)

fun resolveKeyCode(fcitxKey: String): Int {
    FCITX_KEY_TO_ANDROID[fcitxKey]?.let { return it }
    if (fcitxKey.length == 1) {
        val c = fcitxKey[0].uppercaseChar()
        FCITX_KEY_TO_ANDROID["$c"]?.let { return it }
    }
    return KeyEvent.KEYCODE_UNKNOWN
}

fun resolveKeyRefCode(ref: KeyRef): Int = when (ref) {
    is KeyRef.Android -> ref.code
    is KeyRef.Fcitx -> resolveKeyCode(ref.code)
}

fun executeMacroSteps(steps: List<MacroStep>, service: FcitxInputMethodService, context: Context? = null) {
    for (step in steps) {
        when (step) {
            is MacroStep.Down -> {
                step.keys.forEach { k ->
                    val kc = resolveKeyRefCode(k)
                    if (kc != KeyEvent.KEYCODE_UNKNOWN) service.sendSimulatedKeyEventOrFallback(kc, true)
                }
            }
            is MacroStep.Up -> {
                step.keys.forEach { k ->
                    val kc = resolveKeyRefCode(k)
                    if (kc != KeyEvent.KEYCODE_UNKNOWN) service.sendSimulatedKeyEventOrFallback(kc, false)
                }
            }
            is MacroStep.Tap -> {
                step.keys.forEach { k ->
                    val kc = resolveKeyRefCode(k)
                    if (kc != KeyEvent.KEYCODE_UNKNOWN) service.sendDownUpKeyEvents(kc)
                }
            }
            is MacroStep.Text -> service.commitText(step.text)
            is MacroStep.Edit -> {
                val menuId = EDIT_ACTION_MAP[step.action]
                if (menuId != null) service.currentInputConnection?.performContextMenuAction(menuId)
            }
            is MacroStep.AppAction -> {
                if (ButtonAction.fromId(step.id) != null) {
                    service.inputView?.executeButtonAction(step.id)
                } else {
                    val route = ROUTE_MAP[step.id]
                    if (route != null && context != null) {
                        AppUtil.launchMainToRoute(context, route)
                    } else if (step.id == "edit_buttons" && context != null) {
                        context.startActivity(Intent(context, org.fcitx.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } else if (step.id == "split_keyboard_calibration" && context != null) {
                        context.startActivity(Intent(context, SplitKeyboardCalibrationActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } else if (step.id == "web_editor_bridge" && context != null) {
                        try {
                            val session = ImeWebEditorBridgeServer.start()
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.editorUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {
                            context.let {
                                Toast.makeText(it, it.getString(R.string.web_editor_bridge_start_failed, "unknown"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        context?.let {
                            Toast.makeText(it, it.getString(R.string.edit_button_unknown_action, step.id), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            is MacroStep.Shortcut -> {
                val keyCode = resolveKeyRefCode(step.key)
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    val ctrl = step.modifiers.any {
                        when (it) {
                            is KeyRef.Fcitx -> it.code == "Ctrl_L" || it.code == "Ctrl_R"
                            is KeyRef.Android -> it.code == KeyEvent.KEYCODE_CTRL_LEFT || it.code == KeyEvent.KEYCODE_CTRL_RIGHT
                        }
                    }
                    val alt = step.modifiers.any {
                        when (it) {
                            is KeyRef.Fcitx -> it.code == "Alt_L" || it.code == "Alt_R"
                            is KeyRef.Android -> it.code == KeyEvent.KEYCODE_ALT_LEFT || it.code == KeyEvent.KEYCODE_ALT_RIGHT
                        }
                    }
                    val shift = step.modifiers.any {
                        when (it) {
                            is KeyRef.Fcitx -> it.code == "Shift_L" || it.code == "Shift_R"
                            is KeyRef.Android -> it.code == KeyEvent.KEYCODE_SHIFT_LEFT || it.code == KeyEvent.KEYCODE_SHIFT_RIGHT
                        }
                    }
                    service.sendCombinationKeyEvents(keyCode, alt, ctrl, shift)
                }
            }
            is MacroStep.LayerSwitch -> {} // Layer switching not applicable from kawaii bar
        }
    }
}

private fun FcitxInputMethodService.sendSimulatedKeyEventOrFallback(keyCode: Int, isDown: Boolean) {
    val eventTime = android.os.SystemClock.uptimeMillis()
    if (isDown) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
    } else {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }
}
