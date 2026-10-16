/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.*
import org.junit.Test

class IniCodecTest {
    @Test fun existingProfilePathsAndEscapedValuesRemainReadable() {
        val ini = IniCodec.decode("""
            # existing profile
            Current=rime
            Profiles="rime\n万象"
            ExternalPathsV2="万象=/storage/方案 目录"
            Unquoted=C:\users\rime
            [Frontend/Options]
            Empty=
            Quoted="a\tb\"c\\d"
            Unknown="\q"
            Broken="a"b"
        """.trimIndent())
        assertEquals("rime\n万象", ini["Profiles"].value)
        assertEquals("C:\\users\\rime", ini["Unquoted"].value)
        assertEquals("a\tb\"c\\d", ini["Frontend"]["Options"]["Quoted"].value)
        assertEquals("q", ini["Frontend"]["Options"]["Unknown"].value)
        assertNull(ini["Frontend"]["Options"].findByName("Broken"))
        val restored = IniCodec.decode(IniCodec.encode(ini))
        assertEquals(ini, restored)
        assertNotNull(IniCodec.decode("[Empty]\n").findByName("Empty"))
    }
    @Test fun writesNestedSectionsWithoutLosingWhitespaceOrSupplementaryCharacters() {
        val values = listOf("", " leading and trailing ", "line1\nline2\t\r", "\\\"", "万象😀", "a=b", "#[]")
        val root = RawConfig(arrayOf(RawConfig("Options", values.mapIndexed { i, v -> RawConfig("K$i", v) }.toTypedArray())))
        assertEquals(root, IniCodec.decode(IniCodec.encode(root)))
    }
}
