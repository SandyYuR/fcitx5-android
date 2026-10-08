/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.input.keyboard.KeyAction

internal fun popupKeyAction(
    text: String,
    isValidFcitxKey: (String) -> Boolean = ::isValidFcitxKey
): KeyAction {
    val useFcitx = text.codePointCount(0, text.length) == 1 && isValidFcitxKey(text)
    return if (useFcitx) {
        KeyAction.FcitxKeyAction(text)
    } else {
        KeyAction.CommitAction(text)
    }
}

private fun isValidFcitxKey(text: String): Boolean = runCatching {
    val parsed = Key.parse(text)
    parsed != Key.None && parsed.portableString == text
}.getOrDefault(false)
