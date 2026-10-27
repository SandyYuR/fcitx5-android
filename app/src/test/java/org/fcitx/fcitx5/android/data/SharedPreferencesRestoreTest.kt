package org.fcitx.fcitx5.android.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class SharedPreferencesRestoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val good = """<?xml version="1.0" encoding="utf-8"?><map><string name="value">saved &amp; ready</string><set name="items"><string>a</string></set></map>"""

    @Test fun atomicBackupWinsOverAPartiallyWrittenMainFile() {
        val main = File(temp.root, "prefs.xml").apply { writeText("<map>partial") }
        val backup = File(temp.root, "prefs.xml.bak").apply { writeText(good) }
        SharedPreferencesRestore.normalize(temp.root)
        assertEquals(good, main.readText()); assertFalse(backup.exists())
    }
    @Test fun atomicBackupWithoutMainFileIsRecovered() {
        File(temp.root, "prefs.xml.bak").writeText(good)
        SharedPreferencesRestore.normalize(temp.root)
        assertEquals(good, File(temp.root, "prefs.xml").readText())
    }
    @Test fun malformedInputCannotBecomeAPreparedPreference() {
        for (bad in listOf("", "<map><string name=\"x\">oops", "<other/>", "<!DOCTYPE map [<!ENTITY x 'value'>]><map/>")) {
            File(temp.root, "prefs.xml").writeText(bad)
            assertTrue(bad, runCatching { SharedPreferencesRestore.normalize(temp.root) }.isFailure)
        }
    }
    @Test fun invalidBackupIsRetainedAndDoesNotOverwriteMain() {
        val main = File(temp.root, "prefs.xml").apply { writeText(good) }
        val backup = File(temp.root, "prefs.xml.bak").apply { writeText("<broken") }
        assertTrue(runCatching { SharedPreferencesRestore.normalize(temp.root) }.isFailure)
        assertEquals(good, main.readText()); assertTrue(backup.exists())
    }
    @Test fun excessiveNestingIsRejectedBeforeRestore() {
        File(temp.root, "prefs.xml").writeText("<map>" + "<set>".repeat(9) + "</set>".repeat(9) + "</map>")
        assertTrue(runCatching { SharedPreferencesRestore.normalize(temp.root) }.isFailure)
    }

    @Test fun unsupportedOrMisplacedTagsCannotSilentlyResetPreferencesAfterImport() {
        for (value in listOf("<unsupported name=\"x\"/>", "<string name=\"x\"><string>nested</string></string>",
            "<set name=\"x\"><int value=\"1\"/></set>", "<map name=\"nested\"/>", "unparsed text")) {
            File(temp.root, "prefs.xml").writeText("<map>$value</map>")
            assertTrue(value, runCatching { SharedPreferencesRestore.normalize(temp.root) }.isFailure)
        }
    }

    @Test fun invalidPrimitiveValuesAreRejectedBeforeChangingInstalledData() {
        for ((tag, value) in listOf("int" to "2147483648", "int" to "text", "long" to "9223372036854775808",
            "float" to "not-a-number", "boolean" to "yes")) {
            File(temp.root, "prefs.xml").writeText("<map><$tag name=\"x\" value=\"$value\"/></map>")
            assertTrue("$tag/$value", runCatching { SharedPreferencesRestore.normalize(temp.root) }.isFailure)
        }
        File(temp.root, "prefs.xml").writeText("<map><int name=\"x\"/></map>")
        assertTrue(runCatching { SharedPreferencesRestore.normalize(temp.root) }.isFailure)
    }

    @Test fun supportedValuesAreAlsoReadByActualAndroidSharedPreferences() {
        val input = """<map><string name="s">saved &amp; ready</string><int name="i" value="-2147483648"/>
            <long name="l" value="9223372036854775807"/><float name="f" value="NaN"/>
            <boolean name="b" value="true"/><set name="set"><string>a</string><null/></set><null name="null"/></map>"""
        val file = File(temp.root, "prefs.xml").apply { writeText(input) }
        SharedPreferencesRestore.normalize(temp.root)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val name = "restore-types-${java.util.UUID.randomUUID()}"
        val target = File(app.applicationInfo.dataDir, "shared_prefs/$name.xml").apply { parentFile!!.mkdirs() }
        file.copyTo(target)
        val preferences = app.getSharedPreferences(name, 0)
        assertEquals("saved & ready", preferences.getString("s", null))
        assertEquals(Int.MIN_VALUE, preferences.getInt("i", 0))
        assertEquals(Long.MAX_VALUE, preferences.getLong("l", 0))
        assertTrue(preferences.getFloat("f", 0f).isNaN())
        assertTrue(preferences.getBoolean("b", false))
        assertEquals(setOf("a", null), preferences.getStringSet("set", null))
    }

    @Test fun preferenceSizeBudgetTracksSmallHeapsAndRemainsCapped() {
        assertEquals(4L * 1024 * 1024, SharedPreferencesRestore.fileLimit(64L * 1024 * 1024))
        assertEquals(16L * 1024 * 1024, SharedPreferencesRestore.fileLimit(512L * 1024 * 1024))
    }
}
