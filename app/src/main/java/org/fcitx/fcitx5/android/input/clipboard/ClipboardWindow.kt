/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class ClipboardWindow(initialCategory: ClipboardCategory? = null) :
    InputWindow.ExtendedInputWindow<ClipboardWindow>(ClipboardWindow::class) {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val content by lazy {
        ClipboardContent(context, theme, service,
            returnToKeyboard = { windowManager.attachWindow(KeyboardWindow) },
            splitText = { windowManager.attachWindow(TokenizedClipboardWindow(it)) },
            initialCategory = initialCategory)
    }

    override fun onCreateView(): View = content.ui.root
    override fun onAttached() = content.onAttached()
    override fun onDetached() = content.onDetached()
    override val title: String by lazy { context.getString(R.string.clipboard) }
    override fun onCreateBarExtension(): View = content.ui.extension
}
