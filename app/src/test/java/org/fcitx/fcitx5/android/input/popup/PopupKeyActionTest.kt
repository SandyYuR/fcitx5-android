/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.editorUnicode
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

    @Test
    fun bundledSingleCodePointCandidatesKeepTheirLiteralFallback() {
        val candidates = PopupPreset.values.asSequence().flatMap { it.asSequence() }.distinct()
        for (text in candidates.filter { it.codePointCount(0, it.length) == 1 }) {
            when (val action = popupKeyAction(text)) {
                is KeyAction.FcitxKeyAction -> assertEquals(
                    "$text must survive an unhandled Rime key",
                    text.codePointAt(0),
                    Key.parse(action.act).keySym.editorUnicode
                )
                is KeyAction.CommitAction -> assertEquals(text, action.text)
                else -> throw AssertionError("Unexpected popup action for $text: $action")
            }
        }
    }
}
