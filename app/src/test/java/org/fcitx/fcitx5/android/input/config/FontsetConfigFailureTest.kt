/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class FontsetConfigFailureTest {
    /**
     * 损坏的配置不能被包装成「没有配置」：应用成套主题时会拿空 map 去写回，
     * 用户其它字体设置会被整份覆盖。
     */
    @Test fun corruptFontsetConfigIsReportedAsFailure() {
        val file = UserConfigFiles.fontsetJson()
        assertNotNull("fontsDir is unavailable in this test environment", file)
        file!!.parentFile?.mkdirs()
        file.writeText("{ this is not valid fontset json")
        try {
            val result = ConfigProviders.readFontsetPathMapSnapshot()
            assertTrue("A corrupt fontset.json must not look like an empty config", result.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun absentFontsetConfigIsAnEmptySuccess() {
        val file = UserConfigFiles.fontsetJson()
        file?.delete()
        val result = ConfigProviders.readFontsetPathMapSnapshot()
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }
}
