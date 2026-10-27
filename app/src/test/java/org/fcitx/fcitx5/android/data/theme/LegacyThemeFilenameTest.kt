/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class LegacyThemeFilenameTest {
    private val root: File get() = ReflectionHelpers.callInstanceMethod(ThemeFilesManager, "themeDir")
    private fun write(name: String, theme: Theme.Custom) = File(root, name).apply {
        writeText(Json.encodeToString(CustomThemeSerializer, theme))
    }
    private fun theme() = appContext.assets.open("bundled/themes/sage-journey.json").bufferedReader().use {
        Json.decodeFromString(CustomThemeSerializer, it.readText())
    }
    @Before fun clean() { root.mkdirs(); root.listFiles()?.forEach { it.deleteRecursively() } }

    @Test fun allActualBundledThemesAreLoadedAndTheirNamesBecomeCanonical() {
        // Robolectric cannot open UTF-8 APK asset names whose ZIP flag aapt leaves unset.
        // Use the exact checked-in payloads to test the production filesystem reader.
        val fixtures = File("src/main/assets/bundled/themes").takeIf { it.isDirectory }
            ?: File("app/src/main/assets/bundled/themes")
        val files = requireNotNull(fixtures.listFiles()).filter { it.extension == "json" }
        val expected = files.map { file ->
            val bytes = file.readBytes()
            File(root, file.name).writeBytes(bytes)
            Json.decodeFromString(CustomThemeSerializer, bytes.decodeToString()).name
        }.toSet()
        val loaded = ThemeFilesManager.listThemes()
        assertEquals(12, expected.size)
        assertEquals(expected, loaded.map { it.name }.toSet())
        assertEquals(expected.map { "$it.json" }.toSet(), root.listFiles()!!.map { it.name }.toSet())
        assertEquals(expected, ThemeFilesManager.listThemes().map { it.name }.toSet())
    }

    @Test fun anExistingCanonicalThemeIsNotOverwrittenByAnOlderAlias() {
        val theme = theme()
        val existing = write("${theme.name}.json", theme)
        val original = existing.readText()
        val alias = write("legacy.json", theme.copy(isDark = !theme.isDark))
        val aliasContents = alias.readText()
        val loaded = ThemeFilesManager.listThemes()
        assertEquals(1, loaded.size); assertEquals(theme.isDark, loaded.single().isDark)
        assertEquals(original, existing.readText()); assertEquals(aliasContents, alias.readText())
    }

    @Test fun migratedThemeCanBeDeletedWithoutReappearingFromItsAlias() {
        val theme = theme()
        val alias = write("legacy.json", theme)
        val loaded = ThemeFilesManager.listThemes().single()
        assertFalse(alias.exists())
        ThemeFilesManager.deleteThemeFiles(loaded)
        assertTrue(ThemeFilesManager.listThemes().isEmpty())
    }

    @Test fun hostileNamesDoNotEscapeTheDirectoryDuringMigration() {
        val outside = File(root.parentFile, "private-config.json").apply { writeText("keep") }
        val alias = write("legacy.json", theme().copy(name = "../private-config"))
        assertTrue(ThemeFilesManager.listThemes().isEmpty())
        assertTrue(alias.isFile); assertEquals("keep", outside.readText())
    }
}
