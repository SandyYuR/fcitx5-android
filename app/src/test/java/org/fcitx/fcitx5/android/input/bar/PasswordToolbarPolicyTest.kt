/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordToolbarPolicyTest {

    @Test
    fun higherPriorityContentPrecedesForcedAndAutomaticPasswordNumberRow() {
        assertEquals(
            IdleToolbarContent.Search,
            resolveIdleToolbarContent(
                searchActive = true,
                clipboardFresh = true,
                inlineSuggestionPresent = true,
                forceNumberRow = true,
                passwordMode = PasswordToolbarMode.NumberRow
            )
        )
        assertEquals(
            IdleToolbarContent.Clipboard,
            resolveIdleToolbarContent(
                searchActive = false,
                clipboardFresh = true,
                inlineSuggestionPresent = true,
                forceNumberRow = true,
                passwordMode = PasswordToolbarMode.NumberRow
            )
        )
        assertEquals(
            IdleToolbarContent.InlineSuggestion,
            resolveIdleToolbarContent(
                searchActive = false,
                clipboardFresh = false,
                inlineSuggestionPresent = true,
                forceNumberRow = true,
                passwordMode = PasswordToolbarMode.NumberRow
            )
        )
    }

    @Test
    fun passwordFallbackAppliesAfterHigherPriorityContent() {
        assertEquals(
            IdleToolbarContent.NumberRow,
            resolveIdleToolbarContent(
                searchActive = false,
                clipboardFresh = false,
                inlineSuggestionPresent = false,
                forceNumberRow = false,
                passwordMode = PasswordToolbarMode.NumberRow
            )
        )
        assertEquals(
            IdleToolbarContent.Hidden,
            resolveIdleToolbarContent(
                searchActive = false,
                clipboardFresh = false,
                inlineSuggestionPresent = false,
                forceNumberRow = false,
                passwordMode = PasswordToolbarMode.HideButtons
            )
        )
    }

    @Test
    fun nonPasswordFieldUsesNormalToolbar() {
        assertEquals(
            PasswordToolbarMode.NotPassword,
            resolvePasswordToolbarMode(
                isPasswordField = false,
                showNumberRow = false,
                isNumberLayout = false,
                numberRowDismissed = false
            )
        )
    }

    @Test
    fun enabledPreferenceShowsClickableNumberRowTarget() {
        assertEquals(
            PasswordToolbarMode.NumberRow,
            resolvePasswordToolbarMode(
                isPasswordField = true,
                showNumberRow = true,
                isNumberLayout = false,
                numberRowDismissed = false
            )
        )
    }

    @Test
    fun disabledPreferenceHidesPasswordToolbarButtons() {
        assertEquals(
            PasswordToolbarMode.HideButtons,
            resolvePasswordToolbarMode(
                isPasswordField = true,
                showNumberRow = false,
                isNumberLayout = false,
                numberRowDismissed = false
            )
        )
    }

    @Test
    fun numericPasswordLayoutStillHidesToolbarButtons() {
        assertEquals(
            PasswordToolbarMode.HideButtons,
            resolvePasswordToolbarMode(
                isPasswordField = true,
                showNumberRow = true,
                isNumberLayout = true,
                numberRowDismissed = false
            )
        )
    }

    @Test
    fun dismissingNumberRowDoesNotExposeToolbarButtons() {
        assertEquals(
            PasswordToolbarMode.HideButtons,
            resolvePasswordToolbarMode(
                isPasswordField = true,
                showNumberRow = true,
                isNumberLayout = false,
                numberRowDismissed = true
            )
        )
    }
}
