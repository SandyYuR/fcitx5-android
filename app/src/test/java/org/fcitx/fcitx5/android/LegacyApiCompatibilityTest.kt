/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.appcompat.view.menu.MenuBuilder
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.icon.IconThemeEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.theme.CustomThemeActivity
import org.fcitx.fcitx5.android.utils.compatibleZipInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class LegacyApiCompatibilityTest {
    @Test fun standardUtf8ZipNamesWorkAtTheMinimumSdk() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("我的主题.json")); zip.write("{}".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        compatibleZipInputStream(bytes.inputStream()).use {
            assertEquals("我的主题.json", it.nextEntry.name)
            assertEquals("{}", it.readBytes().decodeToString())
        }
        if (Build.VERSION.SDK_INT < 24) assertTrue(runCatching {
            compatibleZipInputStream(bytes.inputStream(), Charset.forName("GBK"))
        }.isFailure)
    }
    @Test fun iconMenuTintingWorksWithoutPlatformApi26Methods() {
        val activity = Robolectric.buildActivity(IconThemeEditorActivity::class.java).get()
        activity.setTheme(R.style.Theme_FcitxSettings_Base_Light)
        ReflectionHelpers.setField(activity, "isEditingExisting", true)
        val menu = MenuBuilder(activity)
        assertTrue(activity.onCreateOptionsMenu(menu))
        assertEquals(3, menu.size())
        assertNotNull(menu.getItem(0).icon)
    }
    @Test fun colorPreviewUsesItsThemeFallbackOnApi23() {
        val activity = Robolectric.buildActivity(CustomThemeActivity::class.java).get()
        val drawable = GradientDrawable().apply { setColor(Color.RED) }
        val method = CustomThemeActivity::class.java.getDeclaredMethod("previewColorOrNull", GradientDrawable::class.java)
        method.isAccessible = true
        assertEquals(if (Build.VERSION.SDK_INT >= 24) Color.RED else null, method.invoke(activity, drawable))
    }
}
