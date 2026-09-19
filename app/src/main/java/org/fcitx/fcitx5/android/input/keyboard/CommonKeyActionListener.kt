/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.dialog.RimeSchemaMenuDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowRimeSchemaMenuAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.VoiceInputHoldEnd
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val preferredVoiceInput by kbdPrefs.preferredVoiceInput

    private var backspaceSwipeState = Stopped

    private var voiceHoldActive = false

    // there should be a new fcitx API for this
    /**
     * Commit whatever is being composed before a key that inserts its own text.
     *
     * Only an *active composition* is committed. Two problems with keying off the candidate
     * list instead:
     * - it was read as `horizontalCandidate.adapter.total`, a RecyclerView adapter, from the
     *   fcitx dispatcher — a cross-thread read of state the main thread updates;
     * - in prediction mode the preedit is empty while candidates are still shown (predictions
     *   for the *previous* commit), so a symbol / quick phrase / unicode key selected one of
     *   them and inserted a word the user never typed.
     *
     * Both go away by asking only about the preedit, which this thread already has cached.
     */
    private suspend fun FcitxAPI.commitAndReset() {
        val composing = clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: commit the composition by selecting the first candidate.
            if (composing) select(0)
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    /**
     * Rime 专版：语言键长按弹出 Rime 方案切换菜单。
     * 方案名/id 从 SubModeManager 的状态区方案菜单解析（与「不同方案不同布局」共用
     * 同一 selector 语义），点选后走 activateAction 写回引擎。
     */
    private fun showRimeSchemaMenu() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(RimeSchemaMenuDialog.build(it, service, context))
            }
        }
    }

    private fun switchToVoiceInput(): Boolean {
        val isPasswordField = service.currentInputEditorInfo?.let {
            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
        } ?: false
        if (isPasswordField) return false
        if (VoiceInputProviderManager.isProviderId(preferredVoiceInput)) {
            return VoiceInputProviderManager.toggle(
                service = service,
                id = preferredVoiceInput,
                onReady = {
                    VoiceInputProviderManager.voiceReadyCallback?.invoke()
                },
                onPartialResult = {},
                onError = { msg ->
                    // The session is over, so a later key release must not try to stop it
                    // (see VoiceInputHoldEnd).
                    voiceHoldActive = false
                    VoiceInputProviderManager.voiceErrorCallback?.invoke(msg)
                },
                onLevel = { rms ->
                    VoiceInputProviderManager.voiceLevelCallback?.invoke(rms)
                },
                onFinished = {
                    voiceHoldActive = false
                    VoiceInputProviderManager.voiceFinishedCallback?.invoke()
                },
                onStatus = { status ->
                    VoiceInputProviderManager.voiceStatusCallback?.invoke(status)
                },
            )
        }
        val (id, subtype) = InputMethodUtil.findVoiceSubtype(preferredVoiceInput) ?: return false
        InputMethodUtil.switchInputMethod(service, id, subtype)
        return true
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            // 退格边界保护：任何"非退格按键"的用户动作都表示当前退格按住序列已经结束。
            // 关键来源是退格键抬起时键盘发出的 DeleteSelectionAction
            // （BaseKeyboard 的 BackspaceKey 手势 Up 分支），以及其它任何键、
            // 上屏宏、语言切换等动作。以下两类不算结束信号：
            // - 退格键自身的按下/重复（SymAction(FcitxKey_BackSpace)）——长按期间
            //   正是靠它们持续被吞；
            // - MoveSelectionAction：那是手指在键上横向滑动（可能正是同一个按着退格的
            //   手指在滑），属于按住过程中的移动，不是抬起。
            val keepsProtection = when (action) {
                is SymAction -> action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace
                is MoveSelectionAction -> true
                else -> false
            }
            if (!keepsProtection) {
                service.backspaceBoundaryGuard.onUserKeyAction()
            }
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    sendKey(action.act, action.states.states, action.code, action.up)
                }
                is SymAction -> service.postFcitxJob {
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is LangSwitchAction -> {
                    // Rime edition: the language key no longer rotates input methods. It sends
                    // one standalone Shift tap and lets the engine act on it — for Rime that is
                    // ascii_composer's ascii_mode (Chinese/Latin) toggle.
                    service.sendStandaloneShiftTap()
                }
                is ShowInputMethodPickerAction -> InputMethodUtil.showPicker()
                is ShowRimeSchemaMenuAction -> showRimeSchemaMenu()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)
                            ?.prepareCompanionKeyboardHeightPercentOverride()
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        SpaceLongPressBehavior.VoiceInput -> { switchToVoiceInput() }
                        SpaceLongPressBehavior.VoiceInputHold -> {
                            val started = switchToVoiceInput()
                            if (VoiceInputProviderManager.isProviderId(preferredVoiceInput)) {
                                voiceHoldActive = started
                            }
                        }
                    }
                }
                is VoiceInputHoldEnd -> {
                    // Releasing the key must stop the session, never start a new one.
                    //
                    // switchToVoiceInput() calls toggle(), which starts a session whenever none
                    // is active. If the session had already ended on its own (silence timeout,
                    // provider error, commit), voiceHoldActive was still true here, so the
                    // release *opened a new recording* that nothing would ever stop — the
                    // microphone stayed on until the process was killed.
                    if (voiceHoldActive) {
                        voiceHoldActive = false
                        if (VoiceInputProviderManager.isActive()) {
                            switchToVoiceInput()
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
