/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * 字母键主题图标：图标作底层、主字符隐藏、划动提示叠在上层；
 * 没有图标时键面保持原样，副标签开关只影响提示文字。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35], qualifiers = "w393dp-h851dp-xhdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class LetterIconKeyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private val monochromeSvg =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#000000" d="M4 4h16v16H4z"/></svg>"""
    private val multicolorSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">""" +
            """<path fill="#ff0000" d="M0 0h12v24H0z"/><path fill="#0000ff" d="M12 0h12v24H12z"/></svg>"""

    private fun withIconTheme(icons: Map<String, String>, block: () -> Unit) {
        val previousTheme = IconThemeManager.activeTheme
        val previousLabels = ThemeManager.prefs.showKeyAltLabels.getValue()
        try {
            IconThemeManager.activeTheme = IconTheme(name = "Letter icon test", icons = icons)
            block()
        } finally {
            ThemeManager.prefs.showKeyAltLabels.setValue(previousLabels)
            IconThemeManager.activeTheme = previousTheme
        }
    }

    private fun letterKey(character: String): AltTextKeyView {
        val def = AlphabetKey(character, "1")
        return AltTextKeyView(
            context, ThemePreset.PixelLight,
            def.appearance as KeyDef.Appearance.AltText, 1f, def.iconSlot
        )
    }

    private fun layout(key: KeyView, widthDp: Int = 60) {
        key.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(56), View.MeasureSpec.EXACTLY)
        )
        key.layout(0, 0, key.measuredWidth, key.measuredHeight)
    }

    @Test fun letterSlotsAreLowercaseAndOnlyCoverSingleLetters() {
        assertEquals("keys.letter.a", IconTheme.letterSlotOf("A"))
        assertEquals("keys.letter.a", IconTheme.letterSlotOf("a"))
        assertEquals("keys.letter.z", IconTheme.letterSlotOf("Z"))
        assertNull(IconTheme.letterSlotOf("AB"))
        assertNull(IconTheme.letterSlotOf("1"))
        assertNull(IconTheme.letterSlotOf(""))
        assertNull(IconTheme.letterSlotOf("@"))
        assertEquals(26, IconTheme.LETTER_SLOTS.size)
        assertTrue(IconTheme.ALL_SLOTS.containsAll(IconTheme.LETTER_SLOTS))
        assertEquals("keys.letter.a", AlphabetKey("A", "1").iconSlot)
    }

    @Test fun iconReplacesTheLetterAndKeepsTheSwipeHintOnTop() = withIconTheme(
        mapOf("keys.letter.a" to monochromeSvg)
    ) {
        val key = letterKey("A")
        layout(key)
        assertTrue(key.img.drawable is BitmapDrawable)
        assertEquals(View.VISIBLE, key.img.visibility)
        assertEquals(
            "icon layer sits under the labels", 0,
            (key.getChildAt(0) as ViewGroup).indexOfChild(key.img)
        )
        assertEquals(View.GONE, key.mainText.visibility)
        assertEquals(View.VISIBLE, key.altText.visibility)
        assertEquals("1", key.altText.text.toString())
        assertEquals(
            "monochrome icons follow the key text color",
            ThemePreset.PixelLight.keyTextColor, key.img.imageTintList!!.defaultColor
        )
        assertEquals(key.img.layoutParams.width, key.img.layoutParams.height)
        assertEquals(
            "图标用固定 24dp 的方框，不再按键宽缩放",
            dp(24), key.img.layoutParams.width
        )
    }

    @Test fun iconBoxIsTheSameOnNarrowAndWideKeys() = withIconTheme(
        mapOf("keys.letter.a" to monochromeSvg)
    ) {
        // 同一排里 98px 的字母键和 152px 的 ?123 键必须画出一样大的图标框。
        val narrow = letterKey("A")
        layout(narrow, widthDp = 60)
        val wide = letterKey("A")
        layout(wide, widthDp = 150)
        assertEquals(
            "宽键与窄键上的图标框必须一样大",
            narrow.img.layoutParams.width, wide.img.layoutParams.width
        )
        assertEquals(dp(24), wide.img.layoutParams.width)
    }

    @Test fun multicolorIconsKeepTheirOwnColors() = withIconTheme(
        mapOf("keys.letter.a" to multicolorSvg)
    ) {
        val key = letterKey("A")
        layout(key)
        assertTrue(key.img.drawable is BitmapDrawable)
        assertNull(key.img.imageTintList)
    }

    @Test fun macroKeysThatOnlyTypeALetterUseTheLetterIcon() {
        // 自定义布局里的 26 键常写成 MacroKey：键面是 q、点一下也只发 Q，就该和 AlphabetKey 同样有图标。
        fun macro(label: String, code: String) =
            MacroKey(label = label, tap = MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx(code))))))

        assertEquals("keys.letter.q", macro("q", "Q").iconSlot)
        assertEquals("keys.letter.q", macro("Q", "Q").iconSlot)
        assertEquals("keys.letter.a", macro("a", "a").iconSlot)
        // 组合键、文本宏、多步宏、非字母键面都不算字母键。
        assertNull(
            MacroKey(
                label = "a",
                tap = MacroAction(
                    listOf(
                        MacroStep.Shortcut(listOf(KeyRef.Fcitx("Control")), KeyRef.Fcitx("A"))
                    )
                )
            ).iconSlot
        )
        assertNull(MacroKey(label = "a", tap = MacroAction(listOf(MacroStep.Text("a")))).iconSlot)
        assertNull(
            MacroKey(
                label = "a",
                tap = MacroAction(
                    listOf(
                        MacroStep.Tap(listOf(KeyRef.Fcitx("A"))),
                        MacroStep.Tap(listOf(KeyRef.Fcitx("B")))
                    )
                )
            ).iconSlot
        )
        assertNull(
            MacroKey(
                label = "全选",
                tap = MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("A")))))
            ).iconSlot
        )
        assertNull(
            MacroKey(
                label = "1",
                tap = MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("1")))))
            ).iconSlot
        )
    }

    @Test fun macroLetterKeyRendersTheThemeIcon() = withIconTheme(
        mapOf("keys.letter.q" to monochromeSvg)
    ) {
        val def = MacroKey(
            label = "q",
            tap = MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("Q")))))
        )
        val key = AltTextKeyView(
            context, ThemePreset.PixelLight,
            def.appearance as KeyDef.Appearance.AltText, 1f, def.iconSlot
        )
        layout(key)
        assertTrue(key.img.drawable is BitmapDrawable)
        assertEquals(View.GONE, key.mainText.visibility)
    }

    @Test fun themedIconSlotReportsTheSlotBoundAtConstruction() {
        val letter = letterKey("q")
        assertEquals("keys.letter.q", letter.themedIconSlot)

        // 合成态复用的是同一套视图创建路径：普通文字键没有图标槽位，重建判断依赖 null。
        val plain = TextKeyView(
            context, ThemePreset.PixelLight,
            KeyDef.Appearance.Text(displayText = "空格", textSize = 16f), 1f
        )
        assertNull(plain.themedIconSlot)
    }

    @Test fun missingIconKeepsTheLetterLabel() = withIconTheme(emptyMap()) {
        val key = letterKey("A")
        layout(key)
        assertEquals(View.GONE, key.img.visibility)
        assertEquals(View.VISIBLE, key.mainText.visibility)
        assertEquals("A", key.mainText.text.toString())
        assertEquals(View.VISIBLE, key.altText.visibility)
    }

    @Test fun altLabelSwitchHidesOnlyTheSwipeHint() {
        withIconTheme(mapOf("keys.letter.a" to monochromeSvg)) {
            ThemeManager.prefs.showKeyAltLabels.setValue(false)
            val key = letterKey("A")
            layout(key)
            assertEquals(View.GONE, key.altText.visibility)
            assertEquals(View.VISIBLE, key.img.visibility)
            assertEquals(View.GONE, key.mainText.visibility)
        }
        withIconTheme(emptyMap()) {
            ThemeManager.prefs.showKeyAltLabels.setValue(false)
            val key = letterKey("A")
            layout(key)
            assertEquals(View.GONE, key.altText.visibility)
            assertEquals(View.VISIBLE, key.mainText.visibility)
        }
    }

    @Test fun iconLayerFollowsTheMoveMainTextPreference() = withIconTheme(
        mapOf("keys.letter.a" to monochromeSvg)
    ) {
        val previousMove = ThemeManager.prefs.moveMainTextForAltLabel.getValue()
        val previousPunctuation = ThemeManager.prefs.punctuationPosition.getValue()
        try {
            ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
            ThemeManager.prefs.moveMainTextForAltLabel.setValue(false)
            val key = letterKey("A")
            layout(key)
            assertEquals(View.VISIBLE, key.altText.visibility)
            val centeredTop = key.img.top

            ThemeManager.prefs.moveMainTextForAltLabel.setValue(true)
            key.refreshAltTextLayout()
            layout(key)
            assertTrue("键帽图标要跟主字符一起让出底部副标签的位置", key.img.top < centeredTop)
            assertTrue("让位后图标不能压住副标签", key.img.bottom <= key.altText.top)

            ThemeManager.prefs.moveMainTextForAltLabel.setValue(false)
            key.refreshAltTextLayout()
            layout(key)
            assertEquals("关掉开关后图标要回到垂直居中", centeredTop, key.img.top)
        } finally {
            ThemeManager.prefs.moveMainTextForAltLabel.setValue(previousMove)
            ThemeManager.prefs.punctuationPosition.setValue(previousPunctuation)
        }
    }

    @Test fun switchingTheAltLabelPreferenceRelayoutsTheSameView() = withIconTheme(
        mapOf("keys.letter.a" to monochromeSvg)
    ) {
        val key = letterKey("A")
        layout(key)
        assertEquals(View.VISIBLE, key.altText.visibility)
        ThemeManager.prefs.showKeyAltLabels.setValue(false)
        key.refreshAltTextLayout()
        layout(key)
        assertEquals(View.GONE, key.altText.visibility)
        assertEquals(View.VISIBLE, key.img.visibility)
        ThemeManager.prefs.showKeyAltLabels.setValue(true)
        key.refreshAltTextLayout()
        layout(key)
        assertEquals(View.VISIBLE, key.altText.visibility)
        assertEquals("1", key.altText.text.toString())
    }
}
