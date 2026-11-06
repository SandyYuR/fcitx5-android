/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.File
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class BundledPresetsUpgradeTest {
    @get:Rule val temp = TemporaryFolder()
    private val asset = "bundled/icon_themes/谷歌.json"
    // Use the actual packaged source; Windows Robolectric cannot open its non-ASCII ZIP path.
    private val current get() = File("src/main/assets/$asset").readBytes()
    private val handled get() = ReflectionHelpers.callInstanceMethod<MutableSet<String>>(BundledPresets, "getHandled")
    private fun previous() = javaClass.classLoader!!.getResourceAsStream(
        "bundled/google-icon-theme-before-20261103.json"
    )!!.use { it.readBytes() }
    private fun install(file: File) = ReflectionHelpers.callInstanceMethod<Int>(
        BundledPresets, "installAsset", ClassParameter.from(String::class.java, asset),
        ClassParameter.from(File::class.java, file), ClassParameter.from(ByteArray::class.java, current)
    )

    @Before fun clearHandledVersions() { handled.clear() }

    @Test fun upgradingTheActualPreviousThemeInstallsTheNewBundledIcons() {
        val old = previous()
        assertEquals(56425, old.size)
        val destination = temp.newFile().apply { writeBytes(old) }
        install(destination)
        assertArrayEquals(current, destination.readBytes())
    }

    @Test fun anOldSkipMarkerDoesNotPreventTheCorrectedUpgrade() {
        val destination = temp.newFile().apply { writeBytes(previous()) }
        handled.add("$asset#${current.size}")
        install(destination)
        assertArrayEquals(current, destination.readBytes())
        assertTrue(destination.setLastModified(1000))
        assertEquals(0, install(destination))
        assertEquals(1000L, destination.lastModified())
    }

    @Test fun aUserEditedThemeSurvivesTheCorrectedRegistration() {
        val edited = previous() + "\n ".toByteArray()
        val destination = temp.newFile().apply { writeBytes(edited) }
        handled.add("$asset#${current.size}")
        install(destination)
        assertArrayEquals(edited, destination.readBytes())
    }

    @Test fun aDeletedHandledThemeIsNotRecreatedByTheRegistrationFix() {
        handled.add("$asset#${current.size}")
        val destination = File(temp.root, "deleted-theme.json")
        assertEquals(0, install(destination))
        assertFalse(destination.exists())
    }
}
