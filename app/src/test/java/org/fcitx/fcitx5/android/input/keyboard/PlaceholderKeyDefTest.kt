/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空白占位键的结构约束。
 *
 * 这个键型唯一容易做错、又完全看不出来的地方就是**"不可点击"从哪来**：
 * `BaseKeyboard.applyBehaviorPopupBindings` 是按 `behaviors.isEmpty() && popup == null`
 * 把视图置为不可交互的。一旦有人往 [PlaceholderKey] 里加一条 Behavior（哪怕只是想让
 * "长按弹点东西"），它就会开始震动、出现按压高亮，并**吃掉**落在它上面的触摸——
 * 而键面依然是一片空白，用户只会觉得"那块区域有时按不动"。
 *
 * 纯数据断言，不依赖 Android 运行时。
 */
class PlaceholderKeyDefTest {

    @Test
    fun placeholderKeyIsNotInteractiveByConstruction() {
        val key = PlaceholderKey()
        assertEquals("没有任何行为", 0, key.behaviors.size)
        assertNull("没有 popup 才不会被长按/滑动接管", key.popup)
    }

    /** 默认形态：键底透明 + 标记为纯外观键（不被 Shift 改写、不套用 disabledAlpha）。 */
    @Test
    fun placeholderKeyDefaultsToInvisibleAndStatic() {
        val key = PlaceholderKey()
        assertTrue("默认不画键底", key.appearance.transparentBackground)
        assertTrue("文字按原样显示", key.appearance.staticDisplay)
        assertEquals(KeyDef.Appearance.Border.Off, key.appearance.border)
        assertFalse("不留外边距，否则相邻键之间会露出底色缝隙", key.appearance.margin)
    }

    /**
     * 打开自定义颜色后要变回"正常的一枚键"：画底、留间距。
     *
     * 只翻 `transparentBackground` 是不够的——[KeyView][org.fcitx.fcitx5.android.input.keyboard.KeyView]
     * 在 `Border.Off` 且未开描边时走的是 `setupPressHighlight()` 分支，键底仍然不画。
     * 而 `margin = false` 会让它紧贴邻居，看起来比旁边的键大一圈。
     */
    @Test
    fun placeholderKeyWithColorsLooksLikeARegularKeyApartFromBeingInert() {
        val key = PlaceholderKey(displayText = "·", transparentBackground = false)
        assertFalse(key.appearance.transparentBackground)
        assertEquals(
            "要画底就得用默认边框，Border.Off 在本键盘里等于不画底",
            KeyDef.Appearance.Border.Default,
            key.appearance.border
        )
        assertTrue("间距要跟着主题走，不然装饰键会显得比邻居大", key.appearance.margin)
        assertEquals("填了字符依然是纯外观键", 0, key.behaviors.size)
    }

    /** 打开自定义颜色（`transparentBackground = false`）后会画真实键底，但依然不可点击。 */
    @Test
    fun placeholderKeyWithColorsStillHasNoBehaviors() {
        val key = PlaceholderKey(
            displayText = "·",
            transparentBackground = false,
            backgroundColor = 0xFF102030.toInt()
        )
        assertFalse(key.appearance.transparentBackground)
        assertEquals(0xFF102030.toInt(), key.appearance.backgroundColor)
        assertEquals("填了字符依然是纯外观键", 0, key.behaviors.size)
    }

    /** 字符与权重原样带上，不做任何兜底。 */
    @Test
    fun placeholderKeyCarriesCharactersAndWeightVerbatim() {
        val blank = PlaceholderKey()
        val text = blank.appearance as KeyDef.Appearance.AltText
        assertEquals("", text.displayText)
        assertEquals("", text.altText)

        val decorated = PlaceholderKey(displayText = "G", altText = "5", percentWidth = 0.25f)
        val decoratedText = decorated.appearance as KeyDef.Appearance.AltText
        assertEquals("G", decoratedText.displayText)
        assertEquals("5", decoratedText.altText)
        assertEquals(0.25f, decorated.appearance.percentWidth, 1e-5f)
    }

    /**
     * 权重 0 是合法的，而且是最常用的取值之一：表示"占满剩余空间"。
     *
     * 其余键型用 `?: 0.1f` 兜底默认权重，占位键若照抄就会把 `weight: 0` 变成 0.1——
     * 表现是"设了 0 却又跳回 0.1"，见 [org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils]
     * 里专门区分 `null` 与 `0` 的写法。
     */
    @Test
    fun placeholderKeyAcceptsZeroWeight() {
        assertEquals(0f, PlaceholderKey(percentWidth = 0f).appearance.percentWidth, 0f)
    }
}
