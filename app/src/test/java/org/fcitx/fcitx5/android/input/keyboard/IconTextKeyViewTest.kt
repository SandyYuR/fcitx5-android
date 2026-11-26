/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class IconTextKeyViewTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val mono = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M4 10h16v4H4z"/></svg>"""
    private val color = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#ff0000" d="M0 0h12v24H0z"/><path fill="#0000ff" d="M12 0h12v24H12z"/></svg>"""

    private fun withTheme(icons: Map<String, String>, block: () -> Unit) {
        val previous = IconThemeManager.activeTheme
        try {
            IconThemeManager.activeTheme = IconTheme("numeric text test", icons = icons)
            block()
        } finally {
            IconThemeManager.activeTheme = previous
        }
    }

    private fun key() = IconTextKeyView(context, ThemePreset.PixelLight,
        KeyDef.Appearance.Text(displayText = "+", textSize = 16f), 1f, "keys.numpad.add")

    @Test fun explicitNumericSlotReplacesTextAndRestoresItAfterThemeRemoval() =
        withTheme(mapOf("keys.numpad.add" to mono)) {
            val view = key()
            assertEquals("keys.numpad.add", view.themedIconSlot)
            assertTrue(view.img.drawable is BitmapDrawable)
            assertEquals(View.VISIBLE, view.img.visibility)
            assertEquals(View.GONE, view.mainText.visibility)
            assertEquals("+", view.contentDescription)
            assertEquals(ThemePreset.PixelLight.keyTextColor, view.img.imageTintList!!.defaultColor)
            IconThemeManager.activeTheme = IconTheme("empty")
            view.reapplyIconThemeOverride()
            assertEquals(View.GONE, view.img.visibility)
            assertNull(view.img.drawable)
            assertEquals(View.VISIBLE, view.mainText.visibility)
            assertEquals("+", view.mainText.text.toString())
            assertNull(view.contentDescription)
        }

    @Test fun multicolorIconPreservesColorsAndUsesTheExistingFixedIconBox() =
        withTheme(mapOf("keys.numpad.add" to color)) {
            val view = key()
            view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 300, 160)
            assertNull(view.img.imageTintList)
            assertEquals(view.img.layoutParams.width, view.img.layoutParams.height)
            assertTrue(view.img.layoutParams.width <= 24 * context.resources.displayMetrics.density)
        }

    @Test fun realKeyboardCreatesIconAwareNumericTextKeyAndRefreshesCachedView() =
        withTheme(mapOf("keys.numpad.add" to mono)) {
            val layout = Json.parseToJsonElement("""{"rime":{"default":[[{"type":"NumPadKey","label":"+","sym":65451,"iconSlot":"keys.numpad.add"}]]}}""").jsonObject
            val ime = InputMethodEntry(uniqueName = "rime", name = "Rime", icon = "", nativeName = "",
                label = "R", languageCode = "en", addon = "rime", isConfigurable = false)
            SplitKeyboardStateManager.init(context)
            TextKeyboard.withPreviewLayout(layout) {
                val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = ime, isPreview = true)
                try {
                    keyboard.onInputMethodUpdate(ime)
                    val view = keyboard.allViews.filterIsInstance<IconTextKeyView>().single()
                    assertEquals(View.GONE, view.mainText.visibility)
                    IconThemeManager.activeTheme = IconTheme("empty")
                    keyboard.refreshIconTheme()
                    assertEquals(View.VISIBLE, view.mainText.visibility)
                    assertEquals(View.GONE, view.img.visibility)
                } finally {
                    keyboard.onDetach()
                }
            }
        }
}
