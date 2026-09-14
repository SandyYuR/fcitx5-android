/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordToolbarPolicyTest {

    @Test
    fun higherPriorityContentPrecedesForcedAndAutomaticPasswordNumberRow() {
        assertEquals(
            IdleToolbarContent.Clipboard,
            resolveIdleToolbarContent(
                clipboardFresh = true,
                inlineSuggestionPresent = true,
                forceNumberRow = true,
                passwordMode = PasswordToolbarMode.NumberRow,
                toolbarCollapsed = false
            )
        )
        assertEquals(
            IdleToolbarContent.InlineSuggestion,
            resolveIdleToolbarContent(
                clipboardFresh = false,
                inlineSuggestionPresent = true,
                forceNumberRow = true,
                passwordMode = PasswordToolbarMode.NumberRow,
                toolbarCollapsed = false
            )
        )
    }

    @Test
    fun passwordFallbackAppliesAfterHigherPriorityContent() {
        assertEquals(
            IdleToolbarContent.NumberRow,
            resolveIdleToolbarContent(
                clipboardFresh = false,
                inlineSuggestionPresent = false,
                forceNumberRow = false,
                passwordMode = PasswordToolbarMode.NumberRow,
                toolbarCollapsed = false
            )
        )
        assertEquals(
            IdleToolbarContent.Hidden,
            resolveIdleToolbarContent(
                clipboardFresh = false,
                inlineSuggestionPresent = false,
                forceNumberRow = false,
                passwordMode = PasswordToolbarMode.HideButtons,
                toolbarCollapsed = false
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
        assertEquals(
            IdleToolbarContent.Toolbar,
            resolveIdleToolbarContent(
                clipboardFresh = false,
                inlineSuggestionPresent = false,
                forceNumberRow = false,
                passwordMode = PasswordToolbarMode.NotPassword,
                toolbarCollapsed = false
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

    @Test
    fun expandAndToggleSwitchesResolveCollapseExactlyAsBeforeThePolicyRewrite() {
        // expandToolbarByDefault == toolbarManuallyToggled -> collapsed (Empty)
        assertTrue(isToolbarCollapsed(expandToolbarByDefault = false, toolbarManuallyToggled = false))
        assertTrue(isToolbarCollapsed(expandToolbarByDefault = true, toolbarManuallyToggled = true))
        // disagreeing switches -> expanded (Toolbar)
        assertFalse(isToolbarCollapsed(expandToolbarByDefault = false, toolbarManuallyToggled = true))
        assertFalse(isToolbarCollapsed(expandToolbarByDefault = true, toolbarManuallyToggled = false))
    }

    @Test
    fun toggleButtonAlwaysFlipsBetweenEmptyAndToolbar() {
        // The click handler writes `!expandToolbarByDefault` to expand and `expandToolbarByDefault`
        // to collapse; both must land on the opposite collapse decision.
        for (expandByDefault in listOf(false, true)) {
            val afterClickOnEmpty = !expandByDefault
            assertFalse(
                "clicking the toggle on Empty must expand (default=$expandByDefault)",
                isToolbarCollapsed(expandByDefault, afterClickOnEmpty)
            )
            val afterClickOnToolbar = expandByDefault
            assertTrue(
                "clicking the toggle on Toolbar must collapse (default=$expandByDefault)",
                isToolbarCollapsed(expandByDefault, afterClickOnToolbar)
            )
        }
    }

    @Test
    fun collapsedToolbarResolvesToEmptyCenterStrip() {
        assertEquals(
            IdleToolbarContent.Empty,
            resolveIdleToolbarContent(
                clipboardFresh = false,
                inlineSuggestionPresent = false,
                forceNumberRow = false,
                passwordMode = PasswordToolbarMode.NotPassword,
                toolbarCollapsed = true
            )
        )
        assertEquals(
            IdleToolbarContent.Toolbar,
            resolveIdleToolbarContent(
                clipboardFresh = false,
                inlineSuggestionPresent = false,
                forceNumberRow = false,
                passwordMode = PasswordToolbarMode.NotPassword,
                toolbarCollapsed = false
            )
        )
    }

    @Test
    fun requestedContentOutranksCollapsedToolbar() {
        // Collapsing the toolbar must never swallow clipboard / inline suggestion / number row /
        // password-hidden pages: those are shown by the field's own request.
        assertEquals(
            IdleToolbarContent.Clipboard,
            resolveIdleToolbarContent(true, false, false, PasswordToolbarMode.NotPassword, toolbarCollapsed = true)
        )
        assertEquals(
            IdleToolbarContent.InlineSuggestion,
            resolveIdleToolbarContent(false, true, false, PasswordToolbarMode.NotPassword, toolbarCollapsed = true)
        )
        assertEquals(
            IdleToolbarContent.NumberRow,
            resolveIdleToolbarContent(false, false, false, PasswordToolbarMode.NumberRow, toolbarCollapsed = true)
        )
        assertEquals(
            IdleToolbarContent.NumberRow,
            resolveIdleToolbarContent(false, false, true, PasswordToolbarMode.NotPassword, toolbarCollapsed = true)
        )
        assertEquals(
            IdleToolbarContent.Hidden,
            resolveIdleToolbarContent(false, false, false, PasswordToolbarMode.HideButtons, toolbarCollapsed = true)
        )
    }
}
