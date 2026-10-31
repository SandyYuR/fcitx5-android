/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ThemeStoragePathsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun namesAreSingleComponentsAndCannotEscapeThroughLinks() {
        val root = temp.newFolder("theme")
        listOf("", " ", ".", "..", "../config/x", "a/b", "a\\b", "C:x", "a\u0000b").forEach {
            assertTrue(it, runCatching { ThemeStoragePaths.json(root, it) }.isFailure)
        }
        assertEquals(File(root, "我的主题 (2).json"), ThemeStoragePaths.json(root, "我的主题 (2)"))
        val outside = temp.newFolder("outside")
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(root, "link.json").toPath(), File(outside, "secret").toPath())
        assertTrue(runCatching { ThemeStoragePaths.json(root, "link") }.isFailure)
    }
    @Test fun containmentUsesACompletePathComponent() {
        val root = temp.newFolder("theme")
        assertTrue(runCatching { ThemeStoragePaths.within(root, File(root.parentFile, "theme-copy/image.png")) }.isFailure)
        assertTrue(runCatching { ThemeStoragePaths.within(root, File(root, "../config/image.png")) }.isFailure)
        assertEquals(File(root, "images/a.png"), ThemeStoragePaths.within(root, File(root, "images/a.png")))
    }
}
