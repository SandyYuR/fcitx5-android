/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ThemeImportSafetyTest {
    private fun zip(theme: Theme.Custom, includeCrop: Boolean = true, emptyCrop: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            out.putNextEntry(ZipEntry("theme.json"))
            out.write(Json.encodeToString(CustomThemeSerializer, theme).toByteArray())
            out.closeEntry()
            (listOf("source.png") + if (includeCrop) listOf("cropped.png") else emptyList()).forEach {
                out.putNextEntry(ZipEntry(it))
                if (it != "cropped.png" || !emptyCrop) out.write(byteArrayOf(1, 2))
                out.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun invalidThemeNamesAndBackgroundPathsDoNotOverwriteOtherConfiguration() {
        val secret = File(appContext.getExternalFilesDir(null), "config/secret.png").apply { parentFile!!.mkdirs(); writeText("keep") }
        val theme = ThemePreset.PixelLight.deriveCustomBackground("safe", "source.png", "cropped.png")
        val unsafeName = theme.copy(name = "../config/secret")
        assertTrue(ThemeFilesManager.importTheme(zip(unsafeName).inputStream()).isFailure)
        val unsafeImage = theme.copy(backgroundImage = theme.backgroundImage!!.copy(srcFilePath = "../config/secret.png"))
        assertTrue(ThemeFilesManager.importTheme(zip(unsafeImage).inputStream()).isFailure)
        assertEquals("keep", secret.readText())
    }
    @Test fun failedSameNameReplacementRetainsBothOldImagesAndDefinition() {
        val name = "transaction-import"
        val (oldCrop, oldSource) = ThemeFilesManager.newBackgroundImagesForTheme(name)
        oldCrop.writeText("old crop")
        oldSource.writeText("old source")
        val old = ThemePreset.PixelLight.deriveCustomBackground(name, oldSource.path, oldCrop.path)
        ThemeFilesManager.saveThemeFiles(old)
        val definition = File(oldSource.parentFile!!.parentFile, "$name.json")
        val oldDefinition = definition.readBytes()
        val replacement = ThemePreset.PixelLight.deriveCustomBackground(name, "source.png", "cropped.png")
        for (bytes in listOf(zip(replacement, includeCrop = false), zip(replacement, emptyCrop = true))) {
            assertTrue(ThemeFilesManager.importTheme(bytes.inputStream(), name).isFailure)
            assertArrayEquals(oldDefinition, definition.readBytes())
            assertEquals("old crop", oldCrop.readText())
            assertEquals("old source", oldSource.readText())
        }
        val imported = ThemeFilesManager.importTheme(zip(replacement).inputStream(), name).getOrThrow().second
        assertNotEquals(oldCrop.canonicalPath, File(imported.backgroundImage!!.croppedFilePath).canonicalPath)
        assertEquals("old crop", oldCrop.readText())
        assertArrayEquals(byteArrayOf(1, 2), File(imported.backgroundImage!!.croppedFilePath).readBytes())
        ThemeFilesManager.deleteThemeFiles(imported)
    }

    @Test fun directoryPayloadAndDirectoryFloodAreBoundedByBothThemeReaders() {
        fun archive(payload: Int, directories: Int, metadataFirst: Boolean): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                fun metadata() {
                    val valid = ThemePreset.PixelLight.deriveCustomBackground("directory-budget", "source.png", "cropped.png")
                    out.putNextEntry(ZipEntry("theme.json"))
                    out.write(Json.encodeToString(CustomThemeSerializer, valid).toByteArray())
                    out.closeEntry()
                    for (image in listOf("source.png", "cropped.png")) {
                        out.putNextEntry(ZipEntry(image)); out.write(byteArrayOf(1, 2)); out.closeEntry()
                    }
                }
                if (metadataFirst) metadata()
                repeat(directories) { index ->
                    out.putNextEntry(ZipEntry("directory-$index/"))
                    val chunk = ByteArray(8192)
                    var remaining = payload
                    while (remaining > 0) {
                        val count = minOf(remaining, chunk.size)
                        out.write(chunk, 0, count)
                        remaining -= count
                    }
                    out.closeEntry()
                }
                if (!metadataFirst) metadata()
            }
        }.toByteArray()
        for ((payload, directories) in listOf(16 * 1024 * 1024 + 1 to 1, 0 to 257)) {
            // Metadata decoding returns at JSON; only entries it actually traverses consume its budget.
            assertTrue(ThemeFilesManager.decodeTheme(archive(payload, directories, metadataFirst = false).inputStream()).isFailure)
            // Full import must also enforce the budget after valid metadata and image entries.
            assertTrue(ThemeFilesManager.importTheme(archive(payload, directories, metadataFirst = true).inputStream()).isFailure)
        }
    }

    @Test fun iconDirectoryPayloadEntryCountAndAggregateAreBoundedAfterValidJson() {
        fun archive(payload: Int, directories: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("theme.json"))
                out.write("""{"name":"icon-directory-budget","icons":{}}""".toByteArray())
                out.closeEntry()
                repeat(directories) { index ->
                    out.putNextEntry(ZipEntry("directory-$index/"))
                    val chunk = ByteArray(8192)
                    var remaining = payload
                    while (remaining > 0) {
                        val count = minOf(remaining, chunk.size)
                        out.write(chunk, 0, count); remaining -= count
                    }
                    out.closeEntry()
                }
            }
        }.toByteArray()
        for ((payload, directories) in listOf(4 * 1024 * 1024 + 1 to 1, 0 to 257, 4 * 1024 * 1024 to 9)) {
            assertTrue(IconThemeManager.importThemeFromZip(archive(payload, directories).inputStream()).isFailure)
        }
    }

    @Test fun normalImportRoundTripsInsideTheThemeDirectory() {
        val theme = ThemePreset.PixelLight.deriveCustomBackground("safe-import", "source.png", "cropped.png")
        val imported = ThemeFilesManager.importTheme(zip(theme).inputStream()).getOrThrow().second
        assertTrue(File(imported.backgroundImage!!.srcFilePath).isFile)
        assertEquals(imported, ThemeFilesManager.listThemes().first { it.name == "safe-import" })
        ThemeFilesManager.deleteThemeFiles(imported)
    }
}
