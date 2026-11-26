/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.keyboard.IconTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class IconThemeGrayPaletteTest {
    private fun bundledWatercolor(): Map<String, String> {
        val relative = "src/main/assets/bundled/icon_themes/水彩.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("icons").jsonObject
            .mapValues { it.value.jsonPrimitive.content }
    }

    @Test fun bundledEqualAndDecimalKeepBothGrayShadesOnActualKeyViews() {
        val icons = bundledWatercolor()
        val previous = IconThemeManager.activeTheme
        try {
            IconThemeManager.activeTheme = IconTheme("gray palette regression", icons = icons)
            listOf("keys.numpad.equal", "keys.numpad.decimal").forEach { slot ->
                val svg = icons.getValue(slot)
                assertTrue(svg.contains("#d3d3d3"))
                assertTrue(svg.contains("#9c9c9c"))
                assertFalse(slot, IconThemeManager.shouldTintInlineSvg(svg))
                assertFalse(slot, requireNotNull(IconThemeManager.resolveIconDrawableInfo(slot, 72, 0xFF101010.toInt())).tintWithTheme)
                val view = IconTextKeyView(RuntimeEnvironment.getApplication(), ThemePreset.PixelLight,
                    KeyDef.Appearance.Text(displayText = if (slot.endsWith("equal")) "=" else ".", textSize = 16f),
                    themedIconSlot = slot)
                assertNotNull(slot, view.img.drawable)
                assertNull(slot, view.img.imageTintList)
            }
        } finally {
            IconThemeManager.activeTheme = previous
        }
    }

    @Test fun singleInkAndCurrentColorRemainAdaptive() {
        listOf("", """ fill="#777777" """, """ fill="currentColor" """).forEach { ink ->
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path$ink d="M0 0h24v24H0z"/></svg>"""
            assertTrue(ink, IconThemeManager.shouldTintInlineSvg(svg))
        }
    }

    @Test fun differentGrayFillAndStrokeAreNotFlattened() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#d3d3d3" stroke="#9c9c9c" d="M1 1h20v20H1z"/></svg>"""
        assertFalse(IconThemeManager.shouldTintInlineSvg(svg))
    }
}
