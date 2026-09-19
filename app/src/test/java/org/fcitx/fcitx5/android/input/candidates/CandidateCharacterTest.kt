/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateCharacterTest {

    @Test
    fun `splits candidate into grapheme characters`() {
        assertEquals(
            listOf("输", "入", "é", "𠀀"),
            "输入é𠀀".candidateCharacters()
        )
        assertEquals(emptyList<String>(), "".candidateCharacters())
    }

    @Test
    fun `chooses popup grid dimensions from character count`() {
        assertEquals(CandidateCharacterGrid(1, 1), candidateCharacterGrid(1))
        assertEquals(CandidateCharacterGrid(1, 2), candidateCharacterGrid(2))
        assertEquals(CandidateCharacterGrid(1, 3), candidateCharacterGrid(3))
        assertEquals(CandidateCharacterGrid(2, 2), candidateCharacterGrid(4))
        assertEquals(CandidateCharacterGrid(3, 3), candidateCharacterGrid(5))
        assertEquals(CandidateCharacterGrid(2, 3), candidateCharacterGrid(6))
        assertEquals(CandidateCharacterGrid(3, 3), candidateCharacterGrid(9))
        assertEquals(CandidateCharacterGrid(4, 4), candidateCharacterGrid(10))
        assertEquals(CandidateCharacterGrid(4, 4), candidateCharacterGrid(16))
        assertEquals(CandidateCharacterGrid(5, 5), candidateCharacterGrid(17))
    }

    @Test
    fun `cancels character selection outside popup bounds`() {
        assertTrue(isInsideCandidateCharacterPopup(0f, 0f, width = 100, height = 50))
        assertTrue(isInsideCandidateCharacterPopup(99.9f, 49.9f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(-0.1f, 25f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(50f, -0.1f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(100f, 25f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(50f, 50f, width = 100, height = 50))
    }
}
