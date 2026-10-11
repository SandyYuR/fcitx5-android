/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

internal enum class PasswordToolbarMode {
    NotPassword,
    NumberRow
}

internal enum class IdleToolbarContent {
    Search,
    Clipboard,
    InlineSuggestion,
    NumberRow,
    Toolbar
}

/**
 * Resolves the password-specific number row shown after higher-priority clipboard and inline
 * suggestion content has been considered. Password fields never hide the normal toolbar:
 * without the number row they fall through to the regular toolbar like any other field.
 */
internal fun resolvePasswordToolbarMode(
    isPasswordField: Boolean,
    showNumberRow: Boolean,
    isNumberLayout: Boolean,
    numberRowDismissed: Boolean
): PasswordToolbarMode = when {
    isPasswordField && showNumberRow && !isNumberLayout && !numberRowDismissed ->
        PasswordToolbarMode.NumberRow
    else -> PasswordToolbarMode.NotPassword
}

internal fun resolveIdleToolbarContent(
    searchActive: Boolean,
    clipboardFresh: Boolean,
    inlineSuggestionPresent: Boolean,
    forceNumberRow: Boolean,
    passwordMode: PasswordToolbarMode
): IdleToolbarContent = when {
    searchActive -> IdleToolbarContent.Search
    clipboardFresh -> IdleToolbarContent.Clipboard
    inlineSuggestionPresent -> IdleToolbarContent.InlineSuggestion
    forceNumberRow || passwordMode == PasswordToolbarMode.NumberRow -> IdleToolbarContent.NumberRow
    else -> IdleToolbarContent.Toolbar
}
