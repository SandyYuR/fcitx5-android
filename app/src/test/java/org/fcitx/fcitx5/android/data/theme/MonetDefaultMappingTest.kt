/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MonetDefaultMappingTest {
    @Test @Config(sdk = [32, 33]) fun legacySystemPalettesHaveDistinctDarkRoles() {
        val light = MonetThemeMapping.createDefault(false)
        val dark = MonetThemeMapping.createDefault(true)
        assertEquals(SystemColorResourceId.SYSTEM_NEUTRAL1_50, light.backgroundColor)
        assertEquals(SystemColorResourceId.SYSTEM_NEUTRAL1_900, dark.backgroundColor)
        assertEquals(SystemColorResourceId.SYSTEM_NEUTRAL1_100, dark.keyTextColor)
        assertEquals(SystemColorResourceId.SYSTEM_ACCENT1_200, dark.accentKeyBackgroundColor)
        assertNotEquals(light, dark)
    }
    @Test @Config(sdk = [35]) fun modernSystemPaletteKeepsItsExplicitDarkResource() {
        assertEquals(SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_DARK,
            MonetThemeMapping.createDefault(true).backgroundColor)
    }
}
