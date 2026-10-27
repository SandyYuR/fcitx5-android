/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import android.os.Looper
import android.os.UserManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ThemeStartupSafetyTest {
    @Test fun lockedStartupDoesNotLoadOrGenerateCredentialProtectedThemes() {
        val users = appContext.getSystemService(UserManager::class.java)
        shadowOf(users).setUserUnlocked(false)
        try {
            assertNull(ThemeManager.init(appContext.resources.configuration))
            assertNotNull(ThemeManager.activeTheme)
        } finally { shadowOf(users).setUserUnlocked(true) }
    }

    @Test fun asynchronousSeedingPreservesTheSelectedThemeAndCachesItsScore() {
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.prefs.normalModeTheme.setValue(ThemePreset.PixelLight)
        val initialization = ThemeManager.init(appContext.resources.configuration)!!
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!initialization.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(initialization.isCompleted)
        assertFalse(initialization.isCancelled)
        assertEquals(ThemePreset.PixelLight.name, ThemeManager.activeTheme.name)
        assertTrue(ThemeManager.getAllThemes().any { it.name == ThemeManager.RANDOM_THEME_NAME })
        val score = ThemeManager.currentRandomResult!!.score
        assertSame(score, ThemeManager.currentRandomResult!!.score)
    }
}
