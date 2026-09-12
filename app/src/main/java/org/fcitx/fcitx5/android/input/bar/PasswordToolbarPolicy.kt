/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

internal enum class PasswordToolbarMode {
    NotPassword,
    NumberRow,
    HideButtons
}

internal enum class IdleToolbarContent {
    Search,
    Clipboard,
    InlineSuggestion,
    NumberRow,
    Hidden,
    Toolbar
}

/**
 * Resolves the password-specific fallback shown after higher-priority clipboard and inline
 * suggestion content has been considered.
 */
internal fun resolvePasswordToolbarMode(
    isPasswordField: Boolean,
    showNumberRow: Boolean,
    isNumberLayout: Boolean,
    numberRowDismissed: Boolean
): PasswordToolbarMode = when {
    !isPasswordField -> PasswordToolbarMode.NotPassword
    showNumberRow && !isNumberLayout && !numberRowDismissed -> PasswordToolbarMode.NumberRow
    else -> PasswordToolbarMode.HideButtons
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
    passwordMode == PasswordToolbarMode.HideButtons -> IdleToolbarContent.Hidden
    else -> IdleToolbarContent.Toolbar
}
