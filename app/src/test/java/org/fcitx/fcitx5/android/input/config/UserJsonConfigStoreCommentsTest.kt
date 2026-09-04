/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C22: the runtime layout reader must accept the same `//` comments the editor writes.
 *
 * Without comment stripping, a commented layout parsed fine in the editor but failed at
 * runtime, and the keyboard silently fell back to the built-in QWERTY layout.
 */
class UserJsonConfigStoreCommentsTest {

    @Test
    fun lineCommentsAreRemoved() {
        val input = """
            {
              // leading comment
              "a": 1, // trailing comment
              "b": 2
            }
        """.trimIndent()
        val stripped = UserJsonConfigStore.stripLineComments(input)
        // Only the comment text is dropped; the leading indentation stays, which is fine
        // because the result is fed to a JSON parser.
        assertEquals(
            listOf("{", "  ", "  \"a\": 1, ", "  \"b\": 2", "}"),
            stripped.lines()
        )
    }

    @Test
    fun commentsInsideStringLiteralsArePreserved() {
        val input = """{"url": "https://example.com/x"}"""
        assertEquals(input, UserJsonConfigStore.stripLineComments(input))
    }

    @Test
    fun escapedQuotesDoNotConfuseStringTracking() {
        // The \" keeps us inside the string, so the // that follows is still string content.
        val input = """{"a": "x\"y//z"}"""
        assertEquals(input, UserJsonConfigStore.stripLineComments(input))
    }

    @Test
    fun commentOnTheLastLineWithoutNewlineIsRemoved() {
        // A regex of the form //.*?\n misses this case because there is no trailing newline.
        assertEquals("""{"a": 1} """, UserJsonConfigStore.stripLineComments("""{"a": 1} // done"""))
    }

    @Test
    fun contentWithoutCommentsIsUnchanged() {
        val input = """
            {
              "a": 1,
              "b": [1, 2, 3]
            }
        """.trimIndent()
        assertEquals(input, UserJsonConfigStore.stripLineComments(input))
    }

    @Test
    fun singleSlashIsNotACommentStart() {
        val input = """{"path": "a/b/c"}"""
        assertEquals(input, UserJsonConfigStore.stripLineComments(input))
    }
}
