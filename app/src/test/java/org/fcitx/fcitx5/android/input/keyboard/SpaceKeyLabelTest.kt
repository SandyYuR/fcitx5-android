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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

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

    private fun TextKeyboard.alphabetText(): String = allViews
        .filterIsInstance<AltTextKeyView>()
        .first()
        .mainText.text.toString()

    private fun TextKeyboard.flushHeldCompositionChange(): TextKeyView {
        val oldView = allViews.filterIsInstance<TextKeyView>()
            .single { it.tag == R.id.button_space }
        assertTrue("start with plain text appearance", oldView !is AltTextKeyView)
        oldView.isPressed = true
        onCompositionStateChanged(true)
        assertSame("the held view must be retained until release", oldView,
            allViews.filterIsInstance<TextKeyView>().single { it.tag == R.id.button_space })
        oldView.isPressed = false
        // Production touch dispatch runs this flush after UP, POINTER_UP, or CANCEL.
        ReflectionHelpers.callInstanceMethod<Unit>(this, "flushDeferredComposeRecreations")
        val newView = allViews.filterIsInstance<TextKeyView>()
            .single { it.tag == R.id.button_space }
        assertNotSame("release must recreate the incompatible text appearance", oldView, newView)
        assertTrue("the active override must now have its alternate label", newView is AltTextKeyView)
        return newView
    }

    private fun TextKeyboard.caps() {
        ReflectionHelpers.callInstanceMethod<Unit>(this, "onAction",
            ClassParameter.from(KeyAction::class.java, KeyAction.CapsAction(false)),
            ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Keyboard))
    }

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

    /** 标签是单个字母时也不能被 Shift/Caps 改写：空格键不是字母键。 */
    @Test
    fun singleLetterLabelIsNotRewrittenByCaps() = keyboard(
        """{"type":"SpaceKey","label":"a"}"""
    ) { keyboard ->
        assertEquals("a", keyboard.spaceText())
        keyboard.caps()
        assertEquals("a", keyboard.spaceText())
        // 字母键照常跟着 Caps 变大写，说明只是空格键被排除在外。
        assertEquals("A", keyboard.alphabetText())
        keyboard.caps()
        assertEquals("a", keyboard.spaceText())
        assertEquals("a", keyboard.alphabetText())
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

    @Test
    fun schemeNameSurvivesDeferredTextToAltTextRecreation() = keyboard(
        """{"type":"SpaceKey","composeOverride":{"type":"SpaceKey","swipeLabel":"选字"}}"""
    ) { keyboard ->
        assertEquals(schemeName, keyboard.spaceText())
        val newView = keyboard.flushHeldCompositionChange()
        assertEquals(schemeName, newView.mainText.text.toString())
    }

    @Test
    fun blankOverrideLabelFallsBackAfterDeferredRecreation() = keyboard(
        """{"type":"SpaceKey","label":"SPACE","composeOverride":{"type":"SpaceKey","label":"","swipeLabel":"选字"}}"""
    ) { keyboard ->
        assertEquals("SPACE", keyboard.spaceText())
        val newView = keyboard.flushHeldCompositionChange()
        assertEquals(schemeName, newView.mainText.text.toString())
    }

    @Test
    fun explicitOverrideLabelSurvivesDeferredRecreation() = keyboard(
        """{"type":"SpaceKey","label":"SPACE","composeOverride":{"type":"SpaceKey","label":"合成","swipeLabel":"选字"}}"""
    ) { keyboard ->
        assertEquals("SPACE", keyboard.spaceText())
        val newView = keyboard.flushHeldCompositionChange()
        assertEquals("合成", newView.mainText.text.toString())
    }
}
