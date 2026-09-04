/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B12: two clipboard items copied from same-named source files must not share a cache file.
 *
 * The staged copy used to be named after the source file, so copying Download/IMG_001.jpg and
 * then Pictures/IMG_001.jpg wrote to the same path and both history entries ended up pointing at
 * the second file's content.
 */
class ClipboardUriStoreNamingTest {

    @Test
    fun stagedNamesAreUniquePerCall() {
        val a = ClipboardUriStore.uniqueStagedName("IMG_001.jpg")
        val b = ClipboardUriStore.uniqueStagedName("IMG_001.jpg")
        assertNotEquals("same display name must not map to the same cache file", a, b)
    }

    @Test
    fun stagedNameKeepsTheOriginalNameAsASuffix() {
        val name = ClipboardUriStore.uniqueStagedName("IMG_001.jpg")
        assertTrue("receiving apps should still see a meaningful name: $name", name.endsWith("-IMG_001.jpg"))
    }

    @Test
    fun stagedNameHasNoPathSeparators() {
        val name = ClipboardUriStore.uniqueStagedName("photo.png")
        assertEquals(-1, name.indexOf('/'))
        assertEquals(-1, name.indexOf('\\'))
    }

    @Test
    fun manyCallsProduceDistinctNames() {
        val names = (1..200).map { ClipboardUriStore.uniqueStagedName("a.png") }
        assertEquals("all staged names must be distinct", names.size, names.toSet().size)
    }
}
