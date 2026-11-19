/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.File
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
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
        "bundled/google-icon-theme-before-20261117.json"
    )!!.use { it.readBytes() }
    private fun install(file: File) = ReflectionHelpers.callInstanceMethod<Int>(
        BundledPresets, "installAsset", ClassParameter.from(String::class.java, asset),
        ClassParameter.from(File::class.java, file), ClassParameter.from(ByteArray::class.java, current)
    )

    @Before fun clearHandledVersions() { handled.clear() }

    @Test fun upgradingTheActualPreviousThemeInstallsTheNewBundledIcons() {
        val old = previous()
        assertEquals(68073, old.size)
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

    @Test fun aDeletedHandledThemeIsRecreated() {
        handled.add("$asset#${current.size}")
        val destination = File(temp.root, "deleted-theme.json")
        assertEquals(1, install(destination))
        assertArrayEquals(current, destination.readBytes())
    }

    @Test fun explicitDefaultLayoutProfileSurvivesWhenFirstInstallSelectionIsDisabled() {
        val profile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        profile.setValue(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        ReflectionHelpers.callInstanceMethod<Unit>(
            BundledPresets, "ensureLayoutProfile",
            ClassParameter.from(Boolean::class.java, false)
        )
        assertEquals(
            UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE,
            profile.getValue()
        )
    }

    /**
     * 蓝狐狸改名为普蓝时，只有原版旧布局被退役（删除）才迁移选择。
     * 用户改过的旧布局会被安装器保留，此时必须继续用用户那一份，不能悄悄换成普蓝。
     */
    @Test fun userEditedLegacyLayoutProfileSurvivesTheRename() {
        val profile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        profile.setValue("蓝狐狸")
        UserConfigFiles.textKeyboardLayoutJson("蓝狐狸")!!.apply {
            parentFile?.mkdirs()
            writeText("{\"user\":true}")
        }
        UserConfigFiles.textKeyboardLayoutJson("普蓝")!!.apply {
            parentFile?.mkdirs()
            writeText("{\"bundled\":true}")
        }
        ReflectionHelpers.callInstanceMethod<Unit>(
            BundledPresets, "ensureLayoutProfile",
            ClassParameter.from(Boolean::class.java, false)
        )
        assertEquals("蓝狐狸", profile.getValue())
    }

    /**
     * 完整安装路径：先退役原版旧文件，再迁移布局选择。
     *
     * 顺序反了就会把用户改过的旧布局删掉；或者旧文件还在就迁移，用户自定义布局被
     * 悄悄换成普蓝。这里用真实的 install() 跑一遍，并用与上一版登记值相同的字节数
     * 构造「未改过的原版文件」。
     */
    @Test fun fullInstallRetiresLegacyKitFilesBeforeMigratingTheProfile() {
        val ext = requireNotNull(appContext.getExternalFilesDir(null))
        fun seed(relative: String, size: Int): File =
            File(ext, relative).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(size) { 'x'.code.toByte() })
            }
        val legacyLayout = seed("config/TextKeyboardLayout.蓝狐狸.json", 19729)
        val legacyIcons = seed("icon_themes/蓝狐狸.json", 56240)
        val legacyTheme = seed("theme/蓝狐狸.json", 709)
        // Windows Robolectric 打不开非 ASCII 的 APK asset 名，先放一份目标布局，
        // 让「旧文件不在 + 新文件在」这一迁移条件成立。
        val renamedLayout = seed("config/TextKeyboardLayout.普蓝.json", 0)
        val profile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        val previousProfile = profile.getValue()
        profile.setValue("蓝狐狸")
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(BundledPresets, "install")

            assertFalse("原版旧布局应在迁移前退役", legacyLayout.exists())
            assertFalse("原版旧图标主题应退役", legacyIcons.exists())
            assertFalse("原版旧配色主题应退役", legacyTheme.exists())
            assertTrue(renamedLayout.exists())
            assertEquals("普蓝", profile.getValue())
        } finally {
            profile.setValue(previousProfile)
            listOf(legacyLayout, legacyIcons, legacyTheme, renamedLayout).forEach { it.delete() }
        }
    }

    @Test fun retiredLegacyLayoutProfileMigratesToTheRenamedKit() {
        val profile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        profile.setValue("蓝狐狸")
        UserConfigFiles.textKeyboardLayoutJson("蓝狐狸")?.delete()
        UserConfigFiles.textKeyboardLayoutJson("普蓝")!!.apply {
            parentFile?.mkdirs()
            writeText("{\"bundled\":true}")
        }
        ReflectionHelpers.callInstanceMethod<Unit>(
            BundledPresets, "ensureLayoutProfile",
            ClassParameter.from(Boolean::class.java, false)
        )
        assertEquals("普蓝", profile.getValue())
    }
}
