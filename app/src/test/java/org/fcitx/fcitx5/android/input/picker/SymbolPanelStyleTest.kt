/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.picker

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 钉住符号面板风格的两个要点：
 *
 * 1. **默认风格是左侧分组栏**——老用户升级后外观不变；这条默认值若被改成
 *    BottomBar，所有人的符号面板会在一夜之间换样，而这在单测里是可见的。
 * 2. **设置改完后 [SymbolPanelUi.refresh] 真的换掉了结构**——面板窗口的视图是
 *    复用缓存的（`InputWindowManager` 为 EssentialWindow 保留 view），只在构造时读一次
 *    偏好就会导致「设置里换了风格，面板纹丝不动」。
 *
 * 断言用分组栏 recycler 的方向来区分两种风格：侧栏是纵向 `LinearLayoutManager`，
 * 底栏是横向；网格 recycler 两者都是 `GridLayoutManager`，不参与判定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class SymbolPanelStyleTest {

    private fun panel(activity: Activity) = SymbolPanelUi(
        ctx = activity,
        theme = ThemeManager.DefaultTheme,
        catalogType = SymbolCatalogType.Symbols,
        columns = 6,
        textSize = 14f,
        policy = DefaultPickerPolicy(),
        keyActionListener = KeyActionListener { _, _ -> },
        popupActionListener = PopupActionListener { }
    )

    /**
     * 面板内纵向/横向 `LinearLayoutManager` 的方向。
     *
     * 必须排除 `GridLayoutManager`：它继承自 `LinearLayoutManager`，且 `orientation`
     * 恒为 `VERTICAL`；不排除的话两种风格都会「含有纵向」，断言会恒真。
     */
    private fun linearOrientations(panel: SymbolPanelUi): Set<Int> =
        (panel.root as android.view.ViewGroup).descendants
            .filterIsInstance<RecyclerView>()
            .mapNotNull { it.layoutManager }
            .filterNot { it is GridLayoutManager }
            .mapNotNull { (it as? LinearLayoutManager)?.orientation }
            .toSet()

    private fun gridItemCount(panel: SymbolPanelUi): Int =
        (panel.root as android.view.ViewGroup).descendants
            .filterIsInstance<RecyclerView>()
            .first { it.layoutManager is GridLayoutManager }
            .adapter!!.itemCount

    @Test fun unchangedRefreshDoesNotFilterTheCatalogAgain() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var calls = 0
        var key = 0
        val policy = object : PickerPolicy by DefaultPickerPolicy() {
            override fun filter(raw: String): Boolean { calls++; return true }
            override fun invalidateKey(): Any = key
        }
        val panel = SymbolPanelUi(activity, ThemeManager.DefaultTheme, SymbolCatalogType.Emoji, 6, 14f,
            policy, KeyActionListener { _, _ -> }, PopupActionListener {})
        val first = calls
        assertTrue(first > 1000)
        panel.refresh()
        assertEquals(first, calls)
        key++
        panel.refresh()
        assertEquals(first * 2, calls)
        SymbolCatalogs.invalidate()
        panel.refresh()
        assertEquals(first * 3, calls)
    }

    @Test
    fun defaultStyleIsSidebar() {
        assertEquals(
            SymbolPanelStyle.Sidebar,
            AppPrefs.getInstance().symbols.symbolPanelStyle.getValue()
        )
    }

    @Test
    fun sidebarStylePutsGroupColumnOnTheLeftAndBottomBarStylePutsItAtTheBottom() {
        val pref = AppPrefs.getInstance().symbols.symbolPanelStyle
        val previous = pref.getValue()
        pref.setValue(SymbolPanelStyle.Sidebar)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val panel = panel(controller.get())

            assertTrue(
                "左侧分组栏风格应有一个纵向 LinearLayoutManager",
                linearOrientations(panel).contains(LinearLayoutManager.VERTICAL)
            )
            assertFalse(
                "左侧分组栏风格不应出现横向分组栏",
                linearOrientations(panel).contains(LinearLayoutManager.HORIZONTAL)
            )
            val gridCountBefore = gridItemCount(panel)
            assertTrue("符号面板应至少有一个分组有内容", gridCountBefore > 0)

            // 设置里切到 Gboard 式底栏，面板尚未重建
            pref.setValue(SymbolPanelStyle.BottomBar)
            panel.refresh()

            assertTrue(
                "底栏风格应有一个横向 LinearLayoutManager",
                linearOrientations(panel).contains(LinearLayoutManager.HORIZONTAL)
            )
            assertFalse(
                "底栏风格不应再出现纵向分组栏",
                linearOrientations(panel).contains(LinearLayoutManager.VERTICAL)
            )
            assertEquals(
                "换风格只改呈现，符号数据不该变",
                gridCountBefore,
                gridItemCount(panel)
            )

            // 再切回去也要能恢复
            pref.setValue(SymbolPanelStyle.Sidebar)
            panel.refresh()
            assertTrue(
                "切回左侧分组栏后应恢复纵向 LinearLayoutManager",
                linearOrientations(panel).contains(LinearLayoutManager.VERTICAL)
            )
        } finally {
            pref.setValue(previous)
        }
    }
}
