/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the real keyboard refresh and popup dispatch with layout-owned labels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class TextKeyboardLabelsTest {
    private val layout = Json.parseToJsonElement("""
        {"default":[[
          {"type":"AlphabetKey","main":"a","alt":"!"},
          {"type":"AlphabetKey","main":"b","displayText":"固定 B","alt":"@"},
          {"type":"SymbolKey","label":"。"},
          {"type":"PlaceholderKey","main":"Decorative A","alt":"▴"}
        ]]}
    """).jsonObject

    // 只有层子布局：不是"按方案细分"，中文与 ASCII 必须共用同一份行缓存。
    private val layeredLayout = Json.parseToJsonElement("""
        {"preview":{
          "default":[[{"type":"AlphabetKey","main":"a","alt":"!"}]],
          "__layer__:数字":[[{"type":"MacroKey","label":"1"}]]
        }}
    """).jsonObject

    // 真正的方案子布局：仍应进入按方案细分路径。
    private val subModeLayout = Json.parseToJsonElement("""
        {"preview":{
          "default":[[{"type":"AlphabetKey","main":"a","alt":"!"}]],
          "英文":[[{"type":"AlphabetKey","main":"q","alt":"@"}]]
        }}
    """).jsonObject

    private fun keyboard(block: (TextKeyboard) -> Unit) = TextKeyboard.withPreviewLayout(layout) {
        val context = RuntimeEnvironment.getApplication()
        SplitKeyboardStateManager.init(context)
        val ime = InputMethodEntry("preview")
        val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = ime, isPreview = true)
        keyboard.onInputMethodUpdate(ime)
        try { block(keyboard) } finally { keyboard.onDetach() }
    }

    private fun TextKeyboard.caps() {
        ReflectionHelpers.callInstanceMethod<Unit>(this, "onAction",
            ClassParameter.from(KeyAction::class.java, KeyAction.CapsAction(false)),
            ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Keyboard))
    }

    private fun usesSubMode(keyboard: TextKeyboard, ime: InputMethodEntry): Boolean =
        ReflectionHelpers.callInstanceMethod(
            keyboard, "layoutUsesSubMode",
            ClassParameter.from(InputMethodEntry::class.java, ime)
        )

    private fun TextKeyboard.popup(action: PopupAction) {
        ReflectionHelpers.callInstanceMethod<Unit>(this, "onPopupAction",
            ClassParameter.from(PopupAction::class.java, action))
    }

    @Test fun labelsSurviveCapsCompositionAndStyleRefresh() = keyboard { keyboard ->
        fun check(letter: String) {
            val keys = keyboard.allViews.filterIsInstance<TextKeyView>().toList()
            assertEquals(listOf(letter, "固定 B", "。", "Decorative A"),
                keys.map { it.mainText.text.toString() })
            assertEquals(listOf("!", "@", "▴"),
                keys.filterIsInstance<AltTextKeyView>().map { it.altText.text.toString() })
        }
        check("a")
        keyboard.caps()
        check("A")
        keyboard.onCompositionStateChanged(true)
        check("A")
        keyboard.refreshStyle()
        check("A")
        keyboard.caps()
        keyboard.onCompositionStateChanged(false)
        keyboard.refreshStyle()
        check("a")
    }

    @Test fun layerEntriesDoNotMakeAFlatLayoutSubModeSpecific() {
        TextKeyboard.withPreviewLayout(layeredLayout) {
            val context = RuntimeEnvironment.getApplication()
            SplitKeyboardStateManager.init(context)
            val previewIme = InputMethodEntry("preview")
            val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = previewIme, isPreview = true)
            keyboard.onInputMethodUpdate(previewIme)
            try {
                // __layer__:数字 是布局内的层，不是按方案细分；ASCII/中文不能再各存一份行缓存。
                assertFalse(usesSubMode(keyboard, previewIme))
            } finally {
                keyboard.onDetach()
            }
        }
        TextKeyboard.withPreviewLayout(subModeLayout) {
            val context = RuntimeEnvironment.getApplication()
            SplitKeyboardStateManager.init(context)
            val previewIme = InputMethodEntry("preview")
            val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = previewIme, isPreview = true)
            keyboard.onInputMethodUpdate(previewIme)
            try {
                // 真正的子模式布局仍要进入按方案细分路径。
                assertTrue(usesSubMode(keyboard, previewIme))
            } finally {
                keyboard.onDetach()
            }
        }
    }

    @Test fun popupPreviewsKeepLiteralSymbolsAndExistingLetterCaps() = keyboard { keyboard ->
        val previews = mutableListOf<String>()
        keyboard.popupActionListener = PopupActionListener { action ->
            when (action) {
                is PopupAction.PreviewAction -> previews.add(action.content)
                is PopupAction.PreviewUpdateAction -> previews.add(action.content)
                else -> Unit
            }
        }
        keyboard.caps()
        keyboard.popup(PopupAction.PreviewAction(1, "a", Rect(0, 0, 40, 60)))
        keyboard.popup(PopupAction.PreviewUpdateAction(1, "."))
        keyboard.popup(PopupAction.PreviewUpdateAction(1, "，"))
        keyboard.popup(PopupAction.PreviewUpdateAction(1, "👨‍💻"))
        assertEquals(listOf("A", ".", "，", "👨‍💻"), previews)
    }
}
