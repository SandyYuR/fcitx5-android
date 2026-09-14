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
    Clipboard,
    InlineSuggestion,
    NumberRow,
    Hidden,
    Empty,
    Toolbar
}

/**
 * Whether the center button strip stays collapsed.
 *
 * `expandToolbarByDefault` (setting) and `toolbarManuallyToggled` (the state the toggle button
 * writes) are combined exactly as before this policy existed:
 *
 * ```
 *                             expandToolbarByDefault
 *                        |   \   |    true |   false
 * toolbarManuallyToggled  |  true |   Empty | Toolbar
 *                        | false | Toolbar |   Empty
 * ```
 *
 * i.e. the strip is collapsed when the two switches agree. Tapping the toggle button flips
 * `toolbarManuallyToggled` to the *opposite* of the default, which moves the row to the other
 * column and therefore always switches between expanded and collapsed.
 */
internal fun isToolbarCollapsed(
    expandToolbarByDefault: Boolean,
    toolbarManuallyToggled: Boolean
): Boolean = expandToolbarByDefault == toolbarManuallyToggled

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

/**
 * Resolves what the center strip shows.
 *
 * Clipboard, inline suggestion, the password number row and the password hidden page all take
 * precedence over the expand/collapse decision: those are content the field itself asked for,
 * while [toolbarCollapsed] only decides whether the *regular* button row is collapsed.
 */
internal fun resolveIdleToolbarContent(
    clipboardFresh: Boolean,
    inlineSuggestionPresent: Boolean,
    forceNumberRow: Boolean,
    passwordMode: PasswordToolbarMode,
    toolbarCollapsed: Boolean
): IdleToolbarContent = when {
    clipboardFresh -> IdleToolbarContent.Clipboard
    inlineSuggestionPresent -> IdleToolbarContent.InlineSuggestion
    forceNumberRow || passwordMode == PasswordToolbarMode.NumberRow -> IdleToolbarContent.NumberRow
    passwordMode == PasswordToolbarMode.HideButtons -> IdleToolbarContent.Hidden
    toolbarCollapsed -> IdleToolbarContent.Empty
    else -> IdleToolbarContent.Toolbar
}
