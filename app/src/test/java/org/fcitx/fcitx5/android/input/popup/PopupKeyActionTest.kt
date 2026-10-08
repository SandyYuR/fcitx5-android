/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupKeyActionTest {

    @Test
    fun validSingleCodePointUsesFcitxAction() {
        assertTrue(
            popupKeyAction("é") { true } is KeyAction.FcitxKeyAction
        )
    }

    @Test
    fun multiCodePointTextUsesCommitAction() {
        val text = "👨‍💻"
        assertEquals(
            KeyAction.CommitAction(text),
            popupKeyAction(text) { true }
        )
    }

    @Test
    fun invalidSingleCodePointUsesCommitAction() {
        val text = "�"
        assertEquals(
            KeyAction.CommitAction(text),
            popupKeyAction(text) { false }
        )
    }

    @Test
    fun emptyTextUsesCommitAction() {
        assertEquals(
            KeyAction.CommitAction(""),
            popupKeyAction("") { true }
        )
    }
}
