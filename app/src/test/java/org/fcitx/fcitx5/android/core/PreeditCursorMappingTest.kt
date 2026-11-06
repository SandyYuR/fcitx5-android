/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class PreeditCursorMappingTest {
    @Test fun displaySeparatorsDoNotBecomeRawInputPositions() {
        val expected = listOf(0, 1, 2, 2, 3, 4, 5)
        for (position in expected.indices)
            assertEquals(expected[position], preeditCursorToRawBytes("ni hao", 4, "nihao", 3, position))
    }

    @Test fun supplementaryCharactersUseCodePointsThenUtf8Bytes() {
        val expected = listOf(0, 3, 7, 8)
        for (position in expected.indices)
            assertEquals(expected[position], preeditCursorToRawBytes("汉🙂e", 3, "汉🙂e", 7, position))
    }

    @Test fun knownCaretSeparatesConvertedPrefixFromEditableLiteralTail() {
        assertEquals(2, preeditCursorToRawBytes("你hao", 1, "nihao", 2, 1))
        assertEquals(3, preeditCursorToRawBytes("你hao", 1, "nihao", 2, 2))
        assertEquals(4, preeditCursorToRawBytes("你hao", 1, "nihao", 2, 3))
        assertEquals(5, preeditCursorToRawBytes("你hao", 1, "nihao", 2, 4))
    }

    @Test fun arbitraryTransformsAreNotGuessedFromDisplayLengths() {
        assertNull(preeditCursorToRawBytes("你好", 2, "nihao", 5, 1))
        assertNull(preeditCursorToRawBytes("niXYZ", 5, "nihao", 5, 2))
        assertEquals(0, preeditCursorToRawBytes("你好", 2, "nihao", 5, 0))
        assertEquals(5, preeditCursorToRawBytes("你好", 2, "nihao", 5, 2))
    }

    @Test fun explicitRawDelimiterCanCorrespondToADisplayedSpace() {
        assertEquals(3, preeditCursorToRawBytes("ni hao", 6, "ni'hao", 6, 3))
        assertNull(preeditCursorToRawBytes("ni  hao", 7, "ni hao", 6, 3))
    }

    @Test fun invalidCoordinatesAndSplitUtf8OrUtf16CharactersAreRejected() {
        assertNull(preeditCursorToRawBytes("ni", 2, "ni", 2, -1))
        assertNull(preeditCursorToRawBytes("ni", 2, "ni", 2, 3))
        assertNull(preeditCursorToRawBytes("ni", 2, "ni", 3, 1))
        assertNull(preeditCursorToRawBytes("🙂a", 2, "🙂a", 1, 1))
        assertNull(preeditCursorToRawBytes("🙂a", 1, "🙂a", 4, 1))
    }
}
