/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.picker

import android.graphics.Color
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
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
    @Test fun selectedGroupsUseTheirOwnForegroundAcrossStylesAndCatalogs() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val preference = AppPrefs.getInstance().symbols.symbolPanelStyle
        val previous = preference.getValue()
        // Accent keys deliberately use white text, independent of the green selected text.
        val theme = ThemePreset.PixelLight.copy(
            genericActiveBackgroundColor = Color.WHITE,
            genericActiveForegroundColor = 0xff08744e.toInt(),
            accentKeyTextColor = Color.WHITE
        )
        try {
            for (style in SymbolPanelStyle.entries) {
                preference.setValue(style)
                for (catalog in SymbolCatalogType.entries) {
                    val panel = SymbolPanelUi(controller.get(), theme, catalog, 6, 18f,
                        DefaultPickerPolicy(), KeyActionListener { _, _ -> }, PopupActionListener { })
                    val recycler = (panel.root as android.view.ViewGroup).descendants
                        .filterIsInstance<RecyclerView>().first { it.layoutManager !is GridLayoutManager }
                    val adapter = recycler.adapter!!
                    val first = adapter.createViewHolder(recycler, adapter.getItemViewType(0))
                    val second = adapter.createViewHolder(recycler, adapter.getItemViewType(1))
                    adapter.bindViewHolder(first, 0)
                    adapter.bindViewHolder(second, 1)
                    assertEquals("$style / $catalog selected foreground", theme.genericActiveForegroundColor,
                        (first.itemView as TextView).currentTextColor)
                    assertEquals(theme.keyTextColor.alpha(0.65f), (second.itemView as TextView).currentTextColor)

                    second.itemView.performClick()
                    adapter.bindViewHolder(first, 0)
                    adapter.bindViewHolder(second, 1)
                    assertEquals(theme.keyTextColor.alpha(0.65f), (first.itemView as TextView).currentTextColor)
                    assertEquals(theme.genericActiveForegroundColor, (second.itemView as TextView).currentTextColor)
                }
            }
        } finally {
            preference.setValue(previous)
            controller.pause().stop().destroy()
        }
    }
}
