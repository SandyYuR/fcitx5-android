/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ThemeAssetTransactionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun failedDefinitionLeavesOriginalImagesAndDefinitionUntouched() {
        val root = temp.newFolder("theme")
        val old = File(root, "old.png").apply { writeText("old image") }
        val definition = File(root, "theme.json").apply { writeText("old definition") }
        val crop = temp.newFile("cropped.png").apply { writeText("new image") }
        assertThrows(IOException::class.java) {
            ThemeAssetTransaction.publish(root, crop, null) { assets ->
                assertEquals("new image", assets.cropped.readText())
                throw IOException("definition publication failed")
            }
        }
        assertEquals("old image", old.readText())
        assertEquals("old definition", definition.readText())
        assertEquals(setOf("old.png", "theme.json"), root.list()!!.toSet())
        assertTrue(crop.exists())
    }

    @Test fun definitionSeesCompleteIndependentImagesAndOldAssetsRemainAvailable() {
        val root = temp.newFolder("theme")
        val old = File(root, "old.png").apply { writeText("old image") }
        val crop = temp.newFile("cropped.png").apply { writeText("crop") }
        val source = temp.newFile("source.jpg").apply { writeText("source") }
        val assets = ThemeAssetTransaction.publish(root, crop, source) { staged ->
            assertEquals("crop", staged.cropped.readText())
            assertEquals("source", staged.source.readText())
            staged
        }
        assertEquals("old image", old.readText())
        assertEquals(root.canonicalFile, assets.cropped.parentFile.parentFile)
        assertNotEquals(crop, assets.cropped)
        assertTrue(assets.cropped.isFile)
        assertTrue(source.isFile)
    }

    @Test fun emptyCroppedImageCannotBePublished() {
        val root = temp.newFolder("theme")
        assertThrows(IllegalArgumentException::class.java) {
            ThemeAssetTransaction.publish(root, temp.newFile(), null) { fail("must not publish") }
        }
        assertTrue(root.list()!!.isEmpty())
    }
}
