/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.picker

import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class SymbolPanelIconThemeTest {
    private val icons = IconTheme(
        name = "Symbol panel test",
        icons = mapOf(
            "toolbar.virtual_keyboard" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#ff0000" d="M0 0h12v24H0z"/><path fill="#0000ff" d="M12 0h12v24H12z"/></svg>""",
            "keys.backspace" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#000000" d="M0 0h24v24H0z"/></svg>"""
        )
    )

    private fun withPanel(
        style: SymbolPanelStyle,
        block: (SymbolPanelUi, MutableList<Pair<KeyAction, KeyActionListener.Source>>) -> Unit
    ) {
        val pref = AppPrefs.getInstance().symbols.symbolPanelStyle
        val previousStyle = pref.getValue()
        val previousTheme = IconThemeManager.activeTheme
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var panel: SymbolPanelUi? = null
        try {
            pref.setValue(style)
            IconThemeManager.activeTheme = IconTheme.default()
            val actions = mutableListOf<Pair<KeyAction, KeyActionListener.Source>>()
            val current = SymbolPanelUi(
                controller.get(), ThemeManager.DefaultTheme, SymbolCatalogType.Symbols, 6, 14f,
                DefaultPickerPolicy(), KeyActionListener { action, source -> actions.add(action to source) },
                PopupActionListener {}
            )
            panel = current
            block(current, actions)
        } finally {
            panel?.detach()
            IconThemeManager.activeTheme = previousTheme
            pref.setValue(previousStyle)
            controller.pause().stop().destroy()
        }
    }

    private fun key(panel: SymbolPanelUi, description: Int): KeyView =
        (panel.root as ViewGroup).descendants.filterIsInstance<KeyView>()
            .single { it.contentDescription == it.context.getString(description) }

    private fun grid(panel: SymbolPanelUi): RecyclerView =
        (panel.root as ViewGroup).descendants.filterIsInstance<RecyclerView>()
            .single { it.layoutManager is GridLayoutManager }

    private fun fallback(panel: SymbolPanelUi, description: Int, drawable: Int) {
        val key = key(panel, description) as ImageKeyView
        assertEquals(drawable, shadowOf(key.img.drawable).createdFromResId)
        assertEquals(ThemeManager.DefaultTheme.altKeyTextColor, key.img.imageTintList!!.defaultColor)
    }

    @Test fun themedUtilityIconsKeepTheirActionsAndColorRulesInBothStyles() {
        for (style in SymbolPanelStyle.entries) withPanel(style) { panel, actions ->
            IconThemeManager.activeTheme = icons
            panel.attach()
            val keyboard = key(panel, R.string.back_to_keyboard) as ImageKeyView
            val backspace = key(panel, R.string.backspace) as ImageKeyView
            assertTrue(keyboard.img.drawable is BitmapDrawable)
            assertTrue(backspace.img.drawable is BitmapDrawable)
            assertNull("Multicolor icons keep their original colors", keyboard.img.imageTintList)
            assertEquals(ThemeManager.DefaultTheme.altKeyTextColor, backspace.img.imageTintList!!.defaultColor)

            keyboard.performClick()
            backspace.performClick()
            assertTrue(backspace.repeatEnabled)
            backspace.onRepeatListener!!.invoke(backspace)
            val source = KeyActionListener.Source.Keyboard
            val delete = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
            assertEquals(listOf(KeyAction.LayoutSwitchAction(TextKeyboard.Name) to source,
                delete to source, delete to source), actions)
        }
    }

    @Test fun liveThemeUpdatesPreserveTheGridAndDetachedPanelsCatchUpOnAttach() {
        for (style in SymbolPanelStyle.entries) withPanel(style) { panel, _ ->
            panel.attach()
            panel.attach()
            val originalGrid = grid(panel)
            val itemCount = originalGrid.adapter!!.itemCount
            IconThemeManager.activeTheme = icons
            shadowOf(Looper.getMainLooper()).idle()
            val keyboard = key(panel, R.string.back_to_keyboard) as ImageKeyView
            assertNull(keyboard.img.imageTintList)
            assertSame(originalGrid, grid(panel))
            assertEquals(itemCount, grid(panel).adapter!!.itemCount)

            panel.detach()
            IconThemeManager.activeTheme = IconTheme(name = "Text icon", icons = mapOf("toolbar.virtual_keyboard" to "Aa"))
            shadowOf(Looper.getMainLooper()).idle()
            assertSame("Hidden panels stop observing theme changes", keyboard, key(panel, R.string.back_to_keyboard))
            panel.attach()
            assertEquals("Aa", (key(panel, R.string.back_to_keyboard) as TextKeyView).mainText.text.toString())
            fallback(panel, R.string.backspace, R.drawable.ic_baseline_backspace_24)

            IconThemeManager.activeTheme = IconTheme.default()
            shadowOf(Looper.getMainLooper()).idle()
            fallback(panel, R.string.back_to_keyboard, R.drawable.ic_baseline_keyboard_24)
            fallback(panel, R.string.backspace, R.drawable.ic_baseline_backspace_24)
            assertSame(originalGrid, grid(panel))
        }
    }
}
