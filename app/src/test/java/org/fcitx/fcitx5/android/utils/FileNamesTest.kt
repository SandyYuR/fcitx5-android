/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the path-safety rules relied on by A10 (icon picked from a content provider). */
class FileNamesTest {

    @Test
    fun basenameDropsDirectories() {
        assertEquals("evil.png", FileNames.basename("../../evil.png"))
        assertEquals("evil.png", FileNames.basename("/absolute/path/evil.png"))
        assertEquals("evil.png", FileNames.basename("..\\..\\evil.png"))
        assertEquals("plain.png", FileNames.basename("plain.png"))
    }

    @Test
    fun sanitizeReplacesIllegalCharactersAndTrimsDots() {
        assertEquals("a_b", FileNames.sanitize("a/b"))
        assertEquals("a_b", FileNames.sanitize("a:b"))
        assertEquals("a_b_c", FileNames.sanitize("a<b>c"))
        assertEquals("hidden", FileNames.sanitize(".hidden"))
        assertEquals("", FileNames.sanitize(".."))
        assertEquals("name", FileNames.sanitize("  name  "))
    }

    @Test
    fun traversalNamesAreReducedToASafeFileName() {
        assertEquals("evil.png", FileNames.safeImageFileName("../../evil.png"))
        assertEquals("evil.png", FileNames.safeImageFileName("/etc/evil.png"))
        // Basename is empty after sanitizing, so the fallback base is used.
        assertEquals("image.png", FileNames.safeImageFileName("...png", fallbackBaseName = "image"))
    }

    @Test
    fun onlyWhitelistedExtensionsAreAccepted() {
        assertEquals("a.png", FileNames.safeImageFileName("a.png"))
        assertEquals("a.svg", FileNames.safeImageFileName("a.svg"))
        assertEquals("a.xml", FileNames.safeImageFileName("a.XML"))
        assertNull(FileNames.safeImageFileName("a.sh"))
        assertNull(FileNames.safeImageFileName("noextension"))
        assertNull(FileNames.safeImageFileName(""))
    }

    @Test
    fun extensionIsNormalizedToLowercase() {
        assertEquals("photo.jpeg", FileNames.safeImageFileName("photo.JPEG"))
    }
}
