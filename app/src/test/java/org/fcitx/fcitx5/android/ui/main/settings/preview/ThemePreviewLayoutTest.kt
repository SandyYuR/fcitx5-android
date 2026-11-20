/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.preview

import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 主题页、分体键盘校准页的预览键盘必须落在真实输入法的布局分节上。
 *
 * 布局文件按输入法名分节（本应用的输入法是 "rime"），每节的键可以带自己的
 * `backgroundColor`。这些预览曾用裸的 "Preview" 名去查布局：文件里没有这个分节，
 * 于是回退到内置 QWERTY，逐键颜色全部丢失，预览只剩主题统一底色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class ThemePreviewLayoutTest {

    /** 只有 rime 分节、且第一个键带显式颜色的布局。 */
    private val coloredRimeLayout = Json.parseToJsonElement("""
        {"rime":{"default":[[
          {"type":"AlphabetKey","main":"a","alt":"1","backgroundColor":-65536},
          {"type":"AlphabetKey","main":"b","alt":"2"}
        ]]}}
    """).jsonObject

    private fun rimeIme() = InputMethodEntry(
        uniqueName = "rime",
        name = "Rime",
        icon = "",
        nativeName = "",
        label = "R",
        languageCode = "en",
        addon = "rime",
        isConfigurable = false
    )

    private fun keyboardOf(ime: InputMethodEntry): TextKeyboard {
        val context = RuntimeEnvironment.getApplication()
        SplitKeyboardStateManager.init(context)
        return TextKeyboard(context, ThemePreset.PixelLight, initialIme = ime, isPreview = true).apply {
            onInputMethodUpdate(ime)
        }
    }

    @Test fun themePreviewLayoutNameFollowsRealInputMethod() {
        assertEquals("rime", PreviewInputMethodEntry.resolveRealLayoutName(rimeIme()))
        assertEquals("rime", PreviewInputMethodEntry.resolveRealLayoutName(null))
    }

    @Test fun themePreviewKeepsRealInputMethodDisplayName() {
        val preview = PreviewInputMethodEntry.createRealLayoutPreview(rimeIme())
        // 布局分节名只管取布局，空格键上仍应显示真实输入法名。
        assertEquals("Rime", preview.displayName)
        assertEquals("rime", preview.uniqueName)
        assertEquals("", preview.subMode.name)
    }

    @Test fun unknownInputMethodFallsBackToReadableName() {
        // 真实输入法还没上报时，空格键不能直接暴露布局分节名 "rime"。
        val preview = PreviewInputMethodEntry.createRealLayoutPreview(null)
        assertEquals("rime", preview.uniqueName)
        assertEquals("Rime", preview.displayName)
        // 查布局仍要落在真实分节上，否则冷启动时又会退回内置 QWERTY。
        assertEquals("rime", PreviewInputMethodEntry.resolveRealLayoutName(preview))
    }

    @Test fun themePreviewRendersRealLayoutColors() =
        TextKeyboard.withPreviewLayout(coloredRimeLayout) {
            val keyboard = keyboardOf(PreviewInputMethodEntry.createRealLayoutPreview(rimeIme()))
            try {
                val keys = keyboard.allViews.filterIsInstance<TextKeyView>().toList()
                assertEquals(listOf("a", "b"), keys.map { it.mainText.text.toString() })
                assertEquals(-65536, keys.first().def.backgroundColor)
            } finally {
                keyboard.onDetach()
            }
        }

    @Test fun barePreviewNameFallsBackToBuiltInLayoutWithoutColors() =
        TextKeyboard.withPreviewLayout(coloredRimeLayout) {
            // 旧写法：不带布局名的 Preview IME 在布局文件里查不到分节。
            val keyboard = keyboardOf(PreviewInputMethodEntry.create())
            try {
                val keys = keyboard.allViews.filterIsInstance<TextKeyView>().toList()
                assertEquals("q", keys.first().mainText.text.toString())
                assertNull(keys.first().def.backgroundColor)
            } finally {
                keyboard.onDetach()
            }
        }
}
