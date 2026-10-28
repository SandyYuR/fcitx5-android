/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.picker

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.shadowedKeyBackgroundDrawable
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
import splitties.dimensions.dp

/**
 * 钉住「分组标签高亮与面板里的 ⌨ / ⌫ 按键是同一套几何」：
 *
 * 1. 圆角直接取 [ThemeManager.prefs] 的 `keyRadius`（按键圆角），不夹半高、不另起参数；
 * 2. 高亮块从标签格子四边缩进**按键边距**（`keyHorizontalMargin*` / `keyVerticalMargin*`），
 *    这正是按键「不贴边」的来源；
 * 3. 选中底色用的 drawable 与 [shadowedKeyBackgroundDrawable] 完全同构（层数、内缩、圆角一致），
 *    也就是说它和按键背景是同一个东西，只是换了个颜色；
 * 4. 按压高亮 / 涟漪 mask 用同一个圆角 + 同一内缩，避免按下时闪出方角或溢出。
 *
 * 取标签背景用 `RecyclerView.Adapter.createViewHolder` + `bindViewHolder` 手动绑一次，
 * 不依赖 Robolectric 里 RecyclerView 是否完成过布局。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class SymbolPanelTabRadiusTest {

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

    private fun sidebarRecycler(panel: SymbolPanelUi): RecyclerView =
        (panel.root as android.view.ViewGroup).descendants
            .filterIsInstance<RecyclerView>()
            .first { it.layoutManager !is GridLayoutManager }

    /** 手动绑定第 [position] 个分组标签，取它当前的背景。 */
    /** 手动绑定第 [position] 个分组标签，取它当前的背景（已剥掉屏蔽 padding 的外壳）。 */
    private fun tabBackground(panel: SymbolPanelUi, position: Int): Drawable {
        val recycler = sidebarRecycler(panel)
        val adapter = recycler.adapter!!
        val holder = adapter.createViewHolder(recycler, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        return holder.itemView.background.unwrapped()
    }

    /** 标签背景外面包了一层屏蔽 padding 的壳，取里面真正画背景的那层。 */
    private fun Drawable.unwrapped(): Drawable =
        if (this is DrawableWrapper) (drawable ?: this).unwrapped() else this

    /** 穿透状态层与内缩层，取真正画圆角的那个 drawable。 */
    private fun android.graphics.drawable.Drawable.cornerRadiusOrNull(): Float? = when (this) {
        is GradientDrawable -> cornerRadius
        is InsetDrawable -> drawable?.cornerRadiusOrNull()
        is StateListDrawable -> getStateDrawable(0)?.cornerRadiusOrNull()
        is LayerDrawable -> (0 until numberOfLayers)
            .mapNotNull { i -> getDrawable(i)?.cornerRadiusOrNull() }
            .firstOrNull()

        is RippleDrawable -> (0 until numberOfLayers)
            .mapNotNull { i -> getDrawable(i)?.cornerRadiusOrNull() }
            .firstOrNull()

        else -> null
    }

    /** 临时改一个偏好并在结束时还原，避免污染其它用例。 */
    private fun <T : Any> withPref(
        pref: org.fcitx.fcitx5.android.data.prefs.ManagedPreference<T>,
        value: T,
        block: () -> Unit
    ) {
        val previous = pref.getValue()
        pref.setValue(value)
        try {
            block()
        } finally {
            pref.setValue(previous)
        }
    }

    @Test
    fun tabRadiusIsKeyRadiusWithoutClamping() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val prefs = ThemeManager.prefs
        withPref(prefs.keyRadius, 12) {
            assertEquals(
                "标签高亮圆角应等于按键圆角（12dp）",
                12f * density,
                panel(activity).tabCornerRadiusPx,
                0.01f
            )
        }
        withPref(prefs.keyRadius, 0) {
            assertEquals("按键直角时标签也是直角", 0f, panel(activity).tabCornerRadiusPx, 0.01f)
        }
        withPref(prefs.keyRadius, 48) {
            assertEquals(
                "按键圆角开到 48dp 时原样传下去，与按键一样交给 Skia 自然收成胶囊",
                48f * density,
                panel(activity).tabCornerRadiusPx,
                0.01f
            )
        }
    }

    @Test
    fun tabHighlightUsesKeyMargins() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val prefs = ThemeManager.prefs
        withPref(prefs.keyHorizontalMargin, 2) {
            withPref(prefs.keyVerticalMargin, 3) {
                assertEquals(
                    "高亮内缩应等于按键边距",
                    activity.dp(2) to activity.dp(3),
                    panel(activity).tabKeyMargins()
                )
            }
        }
        withPref(prefs.keyHorizontalMargin, 8) {
            withPref(prefs.keyVerticalMargin, 6) {
                assertEquals(
                    "改按键边距后标签高亮跟着改",
                    activity.dp(8) to activity.dp(6),
                    panel(activity).tabKeyMargins()
                )
            }
        }
    }

    @Test
    fun activeTabBackgroundIsBuiltLikeAKeyBackground() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val prefs = ThemeManager.prefs
        withPref(prefs.keyRadius, 10) {
          withPref(prefs.keyHorizontalMargin, 3) {
           withPref(prefs.keyVerticalMargin, 4) {
            withPref(prefs.keyBorderStroke, false) {
              val ctx = activity.get()
            val panel = panel(ctx)
            val radius = ctx.dp(10).toFloat()
            val h = ctx.dp(3)
            val v = ctx.dp(4)
            assertEquals("半径与按键一致", radius, panel.tabCornerRadiusPx, 0.01f)
            assertEquals("边距与按键一致", h to v, panel.tabKeyMargins())

            // 第 0 项是初始选中项
            val background = tabBackground(panel, 0)
            assertTrue("选中高亮应是按键同构的 LayerDrawable", background is LayerDrawable)
            background as LayerDrawable
            val reference = shadowedKeyBackgroundDrawable(
                bkgColor = 0, shadowColor = 0, radius = radius,
                shadowWidth = ctx.dp(1), hMargin = h, vMargin = v
            ) as LayerDrawable

            assertEquals("层数与按键背景一致", reference.numberOfLayers, background.numberOfLayers)
            for (i in 0 until reference.numberOfLayers) {
                assertEquals(
                    "第 $i 层左边距应等于按键边距",
                    reference.getLayerInsetLeft(i),
                    background.getLayerInsetLeft(i)
                )
                assertEquals(
                    "第 $i 层上边距应等于按键边距",
                    reference.getLayerInsetTop(i),
                    background.getLayerInsetTop(i)
                )
                assertEquals(
                    "第 $i 层圆角应等于按键圆角",
                    (reference.getDrawable(i) as GradientDrawable).cornerRadius,
                    (background.getDrawable(i) as GradientDrawable).cornerRadius,
                    0.01f
                )
            }
            }
           }
          }
        }
    }

    @Test
    fun pressHighlightAndRippleShareKeyGeometry() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val prefs = ThemeManager.prefs
        withPref(prefs.keyRadius, 9) {
         withPref(prefs.keyHorizontalMargin, 2) {
          withPref(prefs.keyVerticalMargin, 3) {
            val ctx = activity.get()
            val panel = panel(ctx)
            val expected = ctx.dp(9).toFloat()

            withPref(prefs.keyRippleEffect, false) {
                val press = tabBackground(panel, 1)
                assertTrue("未选中标签应是按压高亮", press is StateListDrawable)
                assertEquals(
                    "按压高亮 radius 应与按键圆角一致",
                    expected,
                    press.cornerRadiusOrNull()!!,
                    0.01f
                )
            }

            withPref(prefs.keyRippleEffect, true) {
                val ripple = tabBackground(panel, 1)
                assertTrue("开启涟漪后应是 RippleDrawable", ripple is RippleDrawable)
                assertEquals(
                    "涟漪 mask 的 radius 也应是按键圆角",
                    expected,
                    ripple.cornerRadiusOrNull()!!,
                    0.01f
                )
            }
          }
         }
        }
    }

    @Test
    fun bothPanelStylesUseTheSameKeyGeometry() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val stylePref = AppPrefs.getInstance().symbols.symbolPanelStyle
        val prefs = ThemeManager.prefs
        val previousStyle = stylePref.getValue()
        try {
            withPref(prefs.keyRadius, 14) {
              withPref(prefs.keyHorizontalMargin, 3) {
                withPref(prefs.keyVerticalMargin, 4) {
                val ctx = activity.get()
                val panel = panel(ctx)
                val radius = ctx.dp(14).toFloat()
                val margins = ctx.dp(3) to ctx.dp(4)

                stylePref.setValue(SymbolPanelStyle.Sidebar)
                panel.refresh()
                assertEquals("左侧分组栏圆角", radius, panel.tabCornerRadiusPx, 0.01f)
                assertEquals("左侧分组栏边距", margins, panel.tabKeyMargins())

                stylePref.setValue(SymbolPanelStyle.BottomBar)
                panel.refresh()
                assertEquals("底部栏圆角与分组栏一致", radius, panel.tabCornerRadiusPx, 0.01f)
                assertEquals("底部栏边距与分组栏一致", margins, panel.tabKeyMargins())
                }
              }
            }
        } finally {
            stylePref.setValue(previousStyle)
        }
    }

    @Test
    fun tabBackgroundsDoNotContributePadding() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val panel = panel(activity)
        val probe = Rect(1, 2, 3, 4)
        assertFalse(
            "选中态背景不能报 padding，否则会顶掉标签自己的内边距、把格子挤窄",
            panel.tabFill(true).getPadding(probe)
        )
        assertFalse(
            "未选中态背景同样不能报 padding",
            panel.tabFill(false).getPadding(probe)
        )
    }

    /**
     * 复现「切换标签后高亮范围缩小、文字超出高亮」：
     *
     * 选中态的底色块是从格子四边缩进的层叠 drawable（内缩 = 按键边距），它会把内缩量报成
     * 自己的 padding；`View.setBackground` 采用背景 padding 之后标签格子就会变窄，文字被
     * 挤出高亮。这里断言两种状态下格子宽度、内边距都不变。
     */
    @Test
    fun switchingSelectionDoesNotResizeTheTabCell() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val panel = panel(activity)
        val recycler = sidebarRecycler(panel)
        val adapter = recycler.adapter!!
        val holder = adapter.createViewHolder(recycler, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val cell = holder.itemView

        fun widthWith(background: Drawable): Int {
            cell.background = background
            cell.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return cell.measuredWidth
        }

        val unselectedWidth = widthWith(panel.tabFill(false))
        val selectedWidth = widthWith(panel.tabFill(true))

        assertEquals(
            "选中态与未选中态的格子宽度必须一致，否则文字会被挤出高亮",
            unselectedWidth,
            selectedWidth
        )
        assertEquals(
            "背景不能改写标签的左右内边距",
            activity.dp(12),
            cell.paddingLeft
        )
        assertEquals(
            "背景不能改写标签的左右内边距",
            activity.dp(12),
            cell.paddingRight
        )
    }
}
