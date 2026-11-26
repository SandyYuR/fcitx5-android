/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.picker

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.alpha
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class SymbolPanelColorTest {
    private fun fillColor(drawable: Drawable): Int = when (drawable) {
        is DrawableWrapper -> fillColor(drawable.drawable!!)
        is LayerDrawable -> fillColor(drawable.getDrawable(drawable.numberOfLayers - 1))
        is GradientDrawable -> drawable.color!!.defaultColor
        else -> error("Unexpected key background: ${drawable.javaClass.name}")
    }

    private fun assertPanelColors(
        theme: Theme,
        utilityBackground: Int = theme.altKeyBackgroundColor,
        selectedBackground: Int = theme.accentKeyBackgroundColor,
        selectedForeground: Int = theme.accentKeyTextColor
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val preference = AppPrefs.getInstance().symbols.symbolPanelStyle
        val previous = preference.getValue()
        val border = ThemeManager.prefs.keyBorder
        val previousBorder = border.getValue()
        try {
            border.setValue(true)
            for (style in SymbolPanelStyle.entries) {
                preference.setValue(style)
                for (catalog in SymbolCatalogType.entries) {
                    val panel = SymbolPanelUi(controller.get(), theme, catalog, 6, 18f,
                        DefaultPickerPolicy(), KeyActionListener { _, _ -> }, PopupActionListener { })
                    val root = panel.root as ViewGroup
                    for (description in listOf(R.string.back_to_keyboard, R.string.backspace)) {
                        val key = root.descendants.filterIsInstance<KeyView>().single {
                            it.contentDescription == it.context.getString(description)
                        }
                        assertEquals("${theme.name} / $style / $catalog utility background",
                            utilityBackground, fillColor(key.getChildAt(0).background))
                    }
                    val recycler = root.descendants.filterIsInstance<RecyclerView>()
                        .first { it.layoutManager !is GridLayoutManager }
                    val adapter = recycler.adapter!!
                    val first = adapter.createViewHolder(recycler, adapter.getItemViewType(0))
                    val second = adapter.createViewHolder(recycler, adapter.getItemViewType(1))
                    adapter.bindViewHolder(first, 0)
                    adapter.bindViewHolder(second, 1)
                    assertEquals("${theme.name} / $style / $catalog selected background",
                        selectedBackground, fillColor(first.itemView.background))
                    assertEquals(selectedForeground, (first.itemView as TextView).currentTextColor)
                    assertEquals(theme.keyTextColor.alpha(0.65f), (second.itemView as TextView).currentTextColor)

                    second.itemView.performClick()
                    adapter.bindViewHolder(first, 0)
                    adapter.bindViewHolder(second, 1)
                    assertEquals(theme.keyTextColor.alpha(0.65f), (first.itemView as TextView).currentTextColor)
                    assertEquals(selectedForeground, (second.itemView as TextView).currentTextColor)
                    assertEquals(selectedBackground, fillColor(second.itemView.background))
                    panel.detach()
                }
            }
        } finally {
            border.setValue(previousBorder)
            preference.setValue(previous)
            controller.pause().stop().destroy()
        }
    }

    @Test fun selectedGroupsUseAccentKeyColorsAcrossStylesAndCatalogs() {
        // Distinct generic, accent and utility colors catch accidental role substitutions.
        assertPanelColors(ThemePreset.PixelLight.copy(
            genericActiveBackgroundColor = Color.WHITE,
            genericActiveForegroundColor = Color.RED,
            accentKeyBackgroundColor = 0xffdceeff.toInt(),
            accentKeyTextColor = 0xff08744e.toInt(),
            altKeyBackgroundColor = 0xffe0e0e0.toInt()
        ))
        assertPanelColors(ThemePreset.PixelDark)
    }

    @Test fun watercolorPanelColorsMatchMainNumericSwitchAndReturnKeys() {
        fun asset(path: String): String {
            val relative = "src/main/assets/bundled/$path"
            return (File(relative).takeIf { it.isFile } ?: File("app/$relative")).readText()
        }
        val json = Json { ignoreUnknownKeys = true }
        val theme = json.decodeFromString<Theme.Custom>(asset("themes/水彩.json"))
        val layout = json.parseToJsonElement(asset("keyboard_layouts/TextKeyboardLayout.水彩.json"))
            .jsonObject["rime"]!!.jsonObject["default"]!!.jsonArray
            .flatMap { it.jsonArray }.map { it.jsonObject }
        val numeric = layout.single { it["iconSlot"]?.jsonPrimitive?.content == "keys.numpad" }
        val enter = layout.single { it["type"]?.jsonPrimitive?.content == "ReturnKey" }
        assertPanelColors(theme,
            utilityBackground = numeric["backgroundColor"]!!.jsonPrimitive.int,
            selectedBackground = enter["backgroundColor"]!!.jsonPrimitive.int,
            selectedForeground = enter["textColor"]!!.jsonPrimitive.int)
    }
}
