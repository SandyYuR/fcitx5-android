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

    @Test fun independentlyKnownLegacyGraphicsHaveUnicodeMappings() {
        // libxkbcommon src/keysym-utf.c graphical mappings, independent of the TSV column.
        val expected = listOf(
            "Arabic_hamzaonalef" to (0x5c3 to 0x623),
            "Arabic_hamzaonwaw" to (0x5c4 to 0x624),
            "Arabic_hamzaonyeh" to (0x5c6 to 0x626),
            "Arabic_hamzaunderalef" to (0x5c5 to 0x625),
            "Arabic_maddaonalef" to (0x5c2 to 0x622),
            "Byelorussian_SHORTU" to (0x6be to 0x40e),
            "Cyrillic_HARDSIGN" to (0x6ff to 0x42a),
            "Cyrillic_SOFTSIGN" to (0x6f8 to 0x42c),
            "Greek_ALPHAaccent" to (0x7a1 to 0x386),
            "Greek_ETAaccent" to (0x7a3 to 0x389),
            "Greek_IOTAaccent" to (0x7a4 to 0x38a),
            "Greek_OMEGAaccent" to (0x7ab to 0x38f),
            "Greek_alphaaccent" to (0x7b1 to 0x3ac),
            "Greek_epsilonaccent" to (0x7b2 to 0x3ad),
            "Greek_finalsmallsigma" to (0x7f3 to 0x3c2),
            "Greek_iotaaccent" to (0x7b4 to 0x3af),
            "Greek_iotadieresis" to (0x7b5 to 0x3ca),
            "Greek_omegaaccent" to (0x7bb to 0x3ce),
            "Greek_omicronaccent" to (0x7b7 to 0x3cc),
            "Greek_upsilonaccent" to (0x7b8 to 0x3cd),
            "Hangul_J_KkogjiDalrinIeung" to (0xef9 to 0x11f0),
            "Hangul_J_RieulPhieuf" to (0xee1 to 0x11b5),
            "Hangul_RieulYeorinHieuh" to (0xeef to 0x316d),
            "Hangul_SunkyeongeumMieum" to (0xef0 to 0x3171),
            "Hangul_SunkyeongeumPhieuf" to (0xef4 to 0x3184),
            "Hangul_SunkyeongeumPieub" to (0xef1 to 0x3178),
            "Odoubleacute" to (0x1d5 to 0x150),
            "Thai_saraaimaimalai" to (0xde4 to 0xe44),
            "Udoubleacute" to (0x1db to 0x170),
            "Ukrainian_i" to (0x6a6 to 0x456),
            "crossinglines" to (0x9ee to 0x253c),
            "horizlinescan1" to (0x9ef to 0x23ba),
            "horizlinescan3" to (0x9f0 to 0x23bb),
            "horizlinescan7" to (0x9f2 to 0x23bc),
            "horizlinescan9" to (0x9f3 to 0x23bd),
            "odoubleacute" to (0x1f5 to 0x151),
            "prolongedsound" to (0x4b0 to 0x30fc),
            "semivoicedsound" to (0x4df to 0x309c),
            "udoubleacute" to (0x1fb to 0x171)
        )
        for ((name, pair) in expected) {
            val (sym, codePoint) = pair
            assertEquals(name, sym, KeyCodec.parse(name).sym)
            assertEquals(name, codePoint, KeyCodec.toUnicode(sym))
            assertEquals(name, codePoint, KeyCodec.toUnicode(KeyCodec.fromUnicode(codePoint)))
        }
        for (sym in listOf(0xff51, 0xff0d, 0xffe3, 0xffb1)) {
            assertEquals("Control and keypad keysyms stay non-text", 0, KeyCodec.toUnicode(sym))
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
