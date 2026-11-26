/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.view.KeyEvent
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.action.RimeSchemaMenuAction
import org.fcitx.fcitx5.android.input.action.executeMacroSteps
import org.fcitx.fcitx5.android.input.action.resolveKeyRefCode
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

internal class TextEditingMacroSelectionLock {
    private val heldShiftKeys = mutableSetOf<Int>()
    private val selectionShift = KeyRef.Android(KeyEvent.KEYCODE_SHIFT_LEFT)

    fun transform(step: MacroStep, selectionLocked: Boolean): List<MacroStep> = when (step) {
        is MacroStep.Down -> {
            updateShiftKeys(step.keys, pressed = true)
            listOf(step)
        }
        is MacroStep.Up -> {
            updateShiftKeys(step.keys, pressed = false)
            listOf(step)
        }
        is MacroStep.Tap -> {
            if (!selectionLocked || heldShiftKeys.isNotEmpty() || step.keys.none(::isNavigationKey)) {
                listOf(step)
            } else buildList {
                step.keys.forEach { key ->
                    if (isNavigationKey(key)) {
                        add(MacroStep.Down(listOf(selectionShift)))
                        add(MacroStep.Tap(listOf(key)))
                        add(MacroStep.Up(listOf(selectionShift)))
                    } else {
                        add(MacroStep.Tap(listOf(key)))
                    }
                }
            }
        }
        is MacroStep.Shortcut -> {
            if (!selectionLocked || heldShiftKeys.isNotEmpty() || !isNavigationKey(step.key) ||
                step.modifiers.any(::isShiftKey)
            ) {
                listOf(step)
            } else {
                listOf(step.copy(modifiers = step.modifiers + selectionShift))
            }
        }
        else -> listOf(step)
    }

    private fun updateShiftKeys(keys: List<KeyRef>, pressed: Boolean) {
        keys.map(::resolveKeyRefCode).filter(::isShiftCode).forEach { code ->
            if (pressed) heldShiftKeys.add(code) else heldShiftKeys.remove(code)
        }
    }

    private fun isShiftKey(key: KeyRef): Boolean = isShiftCode(resolveKeyRefCode(key))

    private fun isShiftCode(code: Int): Boolean =
        code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT

    private fun isNavigationKey(key: KeyRef): Boolean = resolveKeyRefCode(key) in NAVIGATION_KEYS

    private companion object {
        val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN
        )
    }
}

internal fun textEditingButtonActionId(action: KeyAction): String? = when (action) {
    KeyAction.ShowRimeSchemaMenuAction -> RimeSchemaMenuAction.id
    else -> null
}

class TextEditingWindow : InputWindow.ExtendedInputWindow<TextEditingWindow>(TextEditingWindow::class),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val hapticOnRepeat by AppPrefs.getInstance().keyboard.hapticOnRepeat
    private val textEditingStyle = AppPrefs.getInstance().textEditing.style
    private val cursorStepDp by AppPrefs.getInstance().textEditing.cursorStepDp
    private val cursorLongPressDelay by AppPrefs.getInstance().textEditing.cursorLongPressDelay
    private val cursorPromptMove by AppPrefs.getInstance().textEditing.cursorPromptMove
    private val cursorPromptLongPress by AppPrefs.getInstance().textEditing.cursorPromptLongPress
    private val cursorPromptSelecting by AppPrefs.getInstance().textEditing.cursorPromptSelecting
    private val cursorPromptReleaseSelection by AppPrefs.getInstance().textEditing.cursorPromptReleaseSelection

    private val buttonRipple by ThemeManager.prefs.keyRippleEffect
    private val buttonBorder by ThemeManager.prefs.keyBorder
    private val buttonRadius by ThemeManager.prefs.textEditingButtonRadius

    private var hasSelection = false
    private var userSelection = false

    private val iconThemeListener = IconThemeManager.OnIconThemeChangeListener {
        ui.refreshThemedIcons()
    }

    private fun sendDirectionKey(
        keyEventCode: Int,
        forceSelection: Boolean = false,
        preserveExistingSelection: Boolean = true
    ) {
        service.sendCombinationKeyEvents(
            keyEventCode,
            shift = forceSelection ||
                (preserveExistingSelection && (hasSelection || userSelection))
        )
    }

    private fun bindCommonActions(ui: TextEditingUi) {
        fun CustomGestureView.onClickWithRepeating(block: () -> Unit) {
            setOnClickListener { block() }
            repeatEnabled = true
            onRepeatListener = {
                block()
                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(this)
            }
        }

        ui.selectButton.setOnClickListener { toggleSelection() }
        ui.selectAllButton.setOnClickListener {
            userSelection = true
            service.currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }
        ui.cutButton.setOnClickListener {
            userSelection = false
            service.currentInputConnection?.performContextMenuAction(android.R.id.cut)
        }
        ui.copyButton.setOnClickListener {
            userSelection = false
            service.currentInputConnection?.performContextMenuAction(android.R.id.copy)
        }
        ui.pasteButton.setOnClickListener {
            userSelection = false
            service.currentInputConnection?.performContextMenuAction(android.R.id.paste)
        }
        ui.backspaceButton.onClickWithRepeating {
            userSelection = false
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        ui.undoButton.setOnClickListener {
            service.currentInputConnection?.performContextMenuAction(android.R.id.undo)
        }
        ui.redoButton.setOnClickListener {
            service.currentInputConnection?.performContextMenuAction(android.R.id.redo)
        }
        ui.clipboardButton.setOnClickListener {
            windowManager.attachWindow(ClipboardWindow())
        }
    }

    private fun bindDefaultActions(ui: TextEditingUi) {
        fun CustomGestureView.onClickWithRepeating(block: () -> Unit) {
            setOnClickListener { block() }
            repeatEnabled = true
            onRepeatListener = {
                block()
                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(this)
            }
        }

        ui.leftButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        ui.upButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_UP) }
        ui.downButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        ui.rightButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        ui.homeButton.setOnClickListener { sendDirectionKey(KeyEvent.KEYCODE_MOVE_HOME) }
        ui.endButton.setOnClickListener { sendDirectionKey(KeyEvent.KEYCODE_MOVE_END) }
    }

    private fun toggleSelection() {
        if (hasSelection) {
            userSelection = false
            service.cancelSelection()
        } else {
            userSelection = !userSelection
        }
        ui.updateSelection(hasSelection && userSelection, userSelection)
    }

    private fun executeEditingMacro(steps: List<MacroStep>) {
        val pending = mutableListOf<MacroStep>()
        val selectionLock = TextEditingMacroSelectionLock()
        fun flush() {
            if (pending.isNotEmpty()) {
                executeMacroSteps(pending.toList(), service, context)
                pending.clear()
            }
        }
        for (step in steps) {
            if (step is MacroStep.Edit) {
                flush()
                when (step.action) {
                    "toggleSelection", "toggle_selection" -> toggleSelection()
                    "selectAll", "selectall", "select_all" -> {
                        userSelection = true
                        executeMacroSteps(listOf(step), service, context)
                        ui.updateSelection(hasSelection, userSelection)
                    }
                    else -> {
                        if (step.action in setOf("copy", "cut", "paste")) userSelection = false
                        executeMacroSteps(listOf(step), service, context)
                        ui.updateSelection(hasSelection, userSelection)
                    }
                }
            } else pending += selectionLock.transform(step, hasSelection || userSelection)
        }
        flush()
    }

    private fun executeTextEditingAction(action: KeyAction) {
        when (action) {
            is MacroAction -> executeEditingMacro(action.steps)
            is KeyAction.CommitAction -> {
                userSelection = false
                service.commitText(action.text)
            }
            is KeyAction.FcitxKeyAction -> {
                if (!action.up) {
                    userSelection = false
                    service.commitText(action.act)
                }
            }
            is KeyAction.SymAction -> {
                val keyCode = action.sym.keyCode
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    val direction = keyCode in setOf(
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
                        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN
                    )
                    if (!direction) userSelection = false
                    service.sendCombinationKeyEvents(
                        keyCode,
                        alt = action.states.alt,
                        ctrl = action.states.ctrl,
                        shift = action.states.shift || (direction && (hasSelection || userSelection))
                    )
                }
            }
            is KeyAction.MoveSelectionAction -> {
                service.applySelectionOffset(action.start, action.end)
            }
            is KeyAction.DeleteSelectionAction -> service.deleteSelection()
            is KeyAction.LayoutSwitchAction -> windowManager.attachWindow(KeyboardWindow)
            is KeyAction.LangSwitchAction -> service.sendStandaloneShiftTap()
            is KeyAction.CapsAction -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_CAPS_LOCK)
            is KeyAction.ShowRimeSchemaMenuAction -> {
                textEditingButtonActionId(action)?.let { service.inputView?.executeButtonAction(it) }
            }
            else -> Unit
        }
    }
    private fun bindFoxyActions(ui: FoxyTextEditingUi) {
        ui.cursorPad.directionListener = TextEditingCursorPadKey.DirectionListener { keyCode, selecting ->
            sendDirectionKey(
                keyCode,
                forceSelection = selecting,
                preserveExistingSelection = false
            )
        }
        ui.backToKeyboardButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    private val ui: TextEditingUi by lazy {
        if (textEditingStyle.getValue() == TextEditingStyle.FoxySwipe) {
            FoxyTextEditingUi(
                context,
                theme,
                buttonRipple,
                buttonBorder,
                context.dp(buttonRadius.toFloat()),
                cursorStepDp,
                cursorLongPressDelay.toLong(),
                cursorPromptMove,
                cursorPromptLongPress,
                cursorPromptSelecting,
                cursorPromptReleaseSelection,
                TextEditingLayoutLoader.loadSwipeBar(),
                ::executeTextEditingAction
            ).also {
                bindCommonActions(it)
                bindFoxyActions(it)
            }
        } else {
            val ime = TextKeyboard.ime
            val bases = listOfNotNull(ime?.uniqueName, ime?.displayName, ime?.subMode?.name, "rime", "default")
                .filter { it.isNotBlank() }.distinct()
            val layout = TextEditingLayoutLoader.load(bases)
            if (layout != null) ConfiguredTextEditingUi(
                context, theme, buttonRipple, buttonBorder, context.dp(buttonRadius.toFloat()),
                layout, ::executeTextEditingAction
            ).also(::bindCommonActions) else TextEditingUi(
                context,
                theme,
                buttonRipple,
                buttonBorder,
                context.dp(buttonRadius.toFloat())
            ).also {
                bindCommonActions(it)
                bindDefaultActions(it)
            }
        }
    }

    override fun onCreateView(): View = ui.root

    override fun onAttached() {
        IconThemeManager.addOnChangedListener(iconThemeListener)
        val range = service.currentInputSelection
        onSelectionUpdate(range.start, range.end)
    }

    override fun onDetached() {
        IconThemeManager.removeOnChangedListener(iconThemeListener)
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        hasSelection = start != end
        ui.updateSelection(hasSelection, userSelection)
    }

    override val title by lazy {
        context.getString(R.string.text_editing)
    }

    override fun onCreateBarExtension(): View = ui.extension
}
