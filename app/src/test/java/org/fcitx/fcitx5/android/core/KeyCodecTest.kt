/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class KeyCodecTest {
    @Test fun existingLayoutsAndLegacyModifiersRemainReadable() {
        for (text in listOf("Control+Alt+Shift+Return", "CTRL_ALT_SHIFT_Return")) {
            val key = KeyCodec.parse(text)
            assertEquals(0xff0d, key.sym)
            assertEquals(13, key.states)
            assertEquals("Control+Alt+Shift+Return", key.portableString)
        }
        assertEquals(0xff0d, KeyCodec.parse("ENTER").sym)
        assertEquals(0xffff, KeyCodec.parse("DELETE").sym)
        assertEquals(0x1008ff12, KeyCodec.parse("XF86AudioMute").sym)
        assertEquals('a'.code, KeyCodec.parse("a").sym)
        assertEquals('+'.code, KeyCodec.parse("+").sym)
        assertEquals("Control+plus", KeyCodec.parse("Control++").portableString)
        assertEquals(0, KeyCodec.parse("not-a-key").sym)
        assertEquals("Control+<38>", KeyCodec.parse("CTRL_<38>").portableString)
        assertEquals("", KeyCodec.create(0x123456, 4).portableString)
    }
    @Test fun unicodeAndAliasesSurviveSerialization() {
        for (text in listOf("你", "😀", "é", "ą", "Ж")) {
            val key = KeyCodec.parse(text)
            assertNotEquals(text, 0, key.sym)
            assertEquals(key.sym, KeyCodec.parse(key.portableString).sym)
            assertEquals(text, key.localizedString)
        }
        assertEquals("Tab", KeyCodec.create(0xfe20, 0).portableString)
        assertEquals("Left Control", KeyCodec.create(0xffe3, 4).localizedString)
        assertEquals("Control+Space", KeyCodec.parse("Control+space").localizedString)
        assertEquals("Page Up", KeyCodec.parse("Page_Up").localizedString)
    }

    @Test fun everyCataloguedLegacyKeysymRoundTripsThroughUnicode() {
        val rows = checkNotNull(javaClass.getResourceAsStream("/keysym-compat.tsv"))
            .bufferedReader()
            .useLines { lines ->
                lines.filter { it.isNotEmpty() && !it.startsWith('#') }
                    .map { it.split('\t') }
                    .filter { it.getOrNull(3).orEmpty().isNotEmpty() }
                    .map { it[0] to (it[1].toInt(16) to it[3].toInt(16)) }
                    .toList()
            }
        assertTrue(rows.isNotEmpty())
        for ((name, pair) in rows) {
            val (sym, codePoint) = pair
            assertEquals("$name keysym must decode to its Unicode character", codePoint, KeyCodec.toUnicode(sym))
            assertEquals(
                "U+${codePoint.toString(16)} must survive Unicode-to-keysym-to-Unicode",
                codePoint,
                KeyCodec.toUnicode(KeyCodec.fromUnicode(codePoint))
            )
        }
    }

    @Test fun unicodeEncodingAvoidsLegacyKeysymNumberCollisions() {
        // A raw Unicode U+03C0 is π, while X11 keysym 0x03C0 represents Ā.
        assertEquals(0x07f0, KeyCodec.fromUnicode(0x03c0))
        assertEquals(0x03c0, KeyCodec.toUnicode(0x07f0))
        assertEquals(0x010001f1, KeyCodec.fromUnicode(0x01f1))
        assertEquals(0x01f1, KeyCodec.toUnicode(0x010001f1))
    }
}
