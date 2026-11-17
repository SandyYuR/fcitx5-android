/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 空格键的自定义标签要跟着键定义走，而不是只在首次创建时写一次。
 *
 * 键面文字由 `applyAppearance` 从外观自带的 `displayText`（空格）重置，所以进出合成态、
 * 重建视图之后都得重新落一次标签；合成态用的是 `composeOverride` 里的键定义，标签也要
 * 跟着换。没有自定义标签的空格键则要回到按方案名算出的文案，不能停在空格上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class SpaceKeyLabelTest {
    private val schemeName = "万象九键"

    private fun keyboard(space: String, block: (TextKeyboard) -> Unit) {
        val layout = Json.parseToJsonElement("""
            {"default":[[
              {"type":"AlphabetKey","main":"a","alt":"!"},
              $space
            ]]}
        """).jsonObject
        TextKeyboard.withPreviewLayout(layout) {
            val context = RuntimeEnvironment.getApplication()
            SplitKeyboardStateManager.init(context)
            val ime = InputMethodEntry(schemeName)
            val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = ime, isPreview = true)
            keyboard.onInputMethodUpdate(ime)
            try {
                block(keyboard)
            } finally {
                keyboard.onDetach()
            }
        }
    }

    private fun TextKeyboard.spaceText(): String = allViews
        .filterIsInstance<TextKeyView>()
        .first { it.tag == R.id.button_space }
        .mainText.text.toString()

    @Test
    fun labelFollowsComposeOverride() = keyboard(
        """{"type":"SpaceKey","label":"SPACE","composeOverride":{"label":"合成"}}"""
    ) { keyboard ->
        assertEquals("SPACE", keyboard.spaceText())
        keyboard.onCompositionStateChanged(true)
        assertEquals("合成", keyboard.spaceText())
        keyboard.onCompositionStateChanged(false)
        assertEquals("SPACE", keyboard.spaceText())
    }

    @Test
    fun schemeNameComesBackAfterComposition() = keyboard(
        """{"type":"SpaceKey","composeOverride":{"type":"SpaceKey"}}"""
    ) { keyboard ->
        assertEquals(schemeName, keyboard.spaceText())
        keyboard.onCompositionStateChanged(true)
        assertEquals(schemeName, keyboard.spaceText())
        keyboard.onCompositionStateChanged(false)
        assertEquals(schemeName, keyboard.spaceText())
    }
}
