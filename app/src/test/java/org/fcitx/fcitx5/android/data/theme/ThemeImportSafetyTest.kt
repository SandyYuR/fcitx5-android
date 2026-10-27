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
    private fun zip(theme: Theme.Custom): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            out.putNextEntry(ZipEntry("theme.json"))
            out.write(Json.encodeToString(CustomThemeSerializer, theme).toByteArray())
            out.closeEntry()
            listOf("source.png", "cropped.png").forEach {
                out.putNextEntry(ZipEntry(it)); out.write(byteArrayOf(1, 2)); out.closeEntry()
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
    @Test fun normalImportRoundTripsInsideTheThemeDirectory() {
        val theme = ThemePreset.PixelLight.deriveCustomBackground("safe-import", "source.png", "cropped.png")
        val imported = ThemeFilesManager.importTheme(zip(theme).inputStream()).getOrThrow().second
        assertTrue(File(imported.backgroundImage!!.srcFilePath).isFile)
        assertEquals(imported, ThemeFilesManager.listThemes().first { it.name == "safe-import" })
        ThemeFilesManager.deleteThemeFiles(imported)
    }
}
