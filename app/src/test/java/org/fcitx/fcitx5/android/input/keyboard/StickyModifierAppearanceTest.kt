/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.activity.ComponentActivity
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class StickyModifierAppearanceTest {
    private fun ime(name: String) = InputMethodEntry(
        uniqueName = name,
        name = name,
        icon = "",
        nativeName = "",
        label = "",
        languageCode = "",
        addon = "",
        isConfigurable = false
    )

    private fun withKeyboard(
        layout: JsonObject,
        initialIme: InputMethodEntry = ime("first"),
        block: (TextKeyboard, FcitxInputMethodService) -> Unit
    ) = TextKeyboard.withPreviewLayout(layout) {
        SplitKeyboardStateManager.init(RuntimeEnvironment.getApplication())
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val keyboard = TextKeyboard(service, ThemePreset.PixelLight, initialIme, isPreview = true)
        keyboard.onInputMethodUpdate(initialIme)
        try {
            activity.get().setContentView(keyboard)
            keyboard.onAttach()
            assertTrue("the keyboard must observe the service sticky controller", keyboard.isAttachedToWindow)
            block(keyboard, service)
        } finally {
            service.stickyModifiers.cancelAll()
            keyboard.onDetach()
            activity.get().finish()
            activity.pause().stop().destroy()
        }
    }

    private fun TextKeyboard.stickyKey(): KeyView = allViews.filterIsInstance<KeyView>()
        .single { it.stickyModifier != null }

    private fun TextKeyboard.stickyKey(modifier: StickyModifier): KeyView =
        allViews.filterIsInstance<KeyView>().single { it.stickyModifier == modifier }

    private fun TextKeyboard.ordinaryKey(): KeyView = allViews.filterIsInstance<KeyView>()
        .single { it.stickyModifier == null }

    private fun latch(service: FcitxInputMethodService, modifier: StickyModifier) {
        val gesture = service.stickyModifiers.onDown(modifier)!!
        service.stickyModifiers.onUp(modifier, gesture, clickEligible = true, cancelled = false)
    }

    @Test
    fun latchedStateRefreshesCurrentAndCachedRowsAndClearsOnConsumption() {
        val layout = Json.parseToJsonElement(
            """
            {
              "first": [[
                {"type":"StickyModifierKey","modifier":"ctrl"},
                {"type":"AlphabetKey","main":"a","alt":"!"}
              ]],
              "second": [[
                {"type":"StickyModifierKey","modifier":"ctrl"},
                {"type":"AlphabetKey","main":"b","alt":"@"}
              ]]
            }
            """.trimIndent()
        ).jsonObject
        withKeyboard(layout) { keyboard, service ->
            val firstKey = keyboard.stickyKey()
            val firstOrdinary = keyboard.ordinaryKey()
            assertFalse(firstKey.isActivated)
            assertFalse(firstOrdinary.isActivated)

            val held = service.stickyModifiers.onDown(StickyModifier.Ctrl)!!
            assertFalse("按下未抬手不点亮", firstKey.isActivated)
            service.stickyModifiers.onUp(
                StickyModifier.Ctrl, held, clickEligible = true, cancelled = false
            )
            assertTrue(firstKey.isActivated)
            assertFalse(firstOrdinary.isActivated)

            keyboard.onInputMethodUpdate(ime("second"))
            val secondKey = keyboard.stickyKey()
            assertNotSame(firstKey, secondKey)
            assertTrue("the cached first row must retain its latch highlight", firstKey.isActivated)
            assertTrue("the current second row must read the same latch", secondKey.isActivated)

            keyboard.onInputMethodUpdate(ime("first"))
            assertSame("the original row should be reused from cache", firstKey, keyboard.stickyKey())
            assertTrue(firstKey.isActivated)

            val consumption = service.stickyModifiers.consume()!!
            assertFalse(firstKey.isActivated)
            assertFalse(secondKey.isActivated)
            assertFalse(firstOrdinary.isActivated)
            service.stickyModifiers.finish(consumption)
        }
    }

    @Test
    fun litStickyKeyUsesActiveTextColorWithThemeFallbackAndPerKeyOverride() {
        val layout = Json.parseToJsonElement(
            """
            {"default":[[
              {"type":"StickyModifierKey","modifier":"ctrl"},
              {"type":"StickyModifierKey","modifier":"shift","activeTextColor":-65536}
            ]]}
            """.trimIndent()
        ).jsonObject
        withKeyboard(layout) { keyboard, service ->
            val ctrl = keyboard.stickyKey(StickyModifier.Ctrl) as TextKeyView
            val shift = keyboard.stickyKey(StickyModifier.Shift) as TextKeyView
            val ctrlNormalColor = ctrl.mainText.currentTextColor
            assertEquals(ctrlNormalColor, shift.mainText.currentTextColor)

            latch(service, StickyModifier.Ctrl)
            assertEquals(
                "未配置激活色时回落到主题的 genericActiveForegroundColor",
                ThemePreset.PixelLight.genericActiveForegroundColor,
                ctrl.mainText.currentTextColor
            )
            assertEquals(
                "未点亮的粘滞键不受影响",
                ctrlNormalColor,
                shift.mainText.currentTextColor
            )

            latch(service, StickyModifier.Shift)
            assertEquals(
                "按键上配置的激活文字色优先于主题回退",
                -65536,
                shift.mainText.currentTextColor
            )
        }
    }

    @Test
    fun singleKeyTapMacroKeyConsumesLatchedModifierAsShortcutWithoutCommittingText() {
        val layout = Json.parseToJsonElement(
            """
            {"default":[[
              {"type":"StickyModifierKey","modifier":"ctrl"},
              {"type":"MacroKey","label":"a","tap":{"macro":[{"type":"tap","keys":[{"fcitx":"A"}]}]}}
            ]]}
            """.trimIndent()
        ).jsonObject
        withKeyboard(layout) { keyboard, service ->
            service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
            val recorded = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> recorded += action }

            latch(service, StickyModifier.Ctrl)
            val macroKey = keyboard.ordinaryKey()
            assertTrue(macroKey.performClick())

            assertFalse(
                "单键 Tap 宏必须消费粘滞组合",
                service.stickyModifiers.hasActiveModifiers
            )
            assertTrue(
                "快捷键必须走模拟物理键，不得产生虚拟键文本提交: $recorded",
                recorded.isEmpty()
            )
        }
    }

    @Test
    fun shiftOnlyLetterTapEntersUpperCasedVirtualInput() {
        val layout = Json.parseToJsonElement(
            """
            {"default":[[
              {"type":"StickyModifierKey","modifier":"shift"},
              {"type":"MacroKey","label":"a","tap":{"macro":[{"type":"tap","keys":[{"fcitx":"A"}]}]}}
            ]]}
            """.trimIndent()
        ).jsonObject
        withKeyboard(layout) { keyboard, service ->
            service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
            val recorded = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> recorded += action }

            latch(service, StickyModifier.Shift)
            assertTrue(keyboard.ordinaryKey().performClick())

            assertFalse(service.stickyModifiers.hasActiveModifiers)
            assertEquals(
                listOf("A", "A"),
                recorded.map { (it as KeyAction.FcitxKeyAction).act }
            )
            assertEquals(
                listOf(false, true),
                recorded.map { (it as KeyAction.FcitxKeyAction).up }
            )
            recorded.forEach {
                assertEquals(
                    KeyStates(KeyState.Virtual, KeyState.Shift),
                    (it as KeyAction.FcitxKeyAction).states
                )
            }
        }
    }

    @Test
    fun longPressDoesNotConsumeLatchedModifier() {
        val layout = Json.parseToJsonElement(
            """
            {"default":[[
              {"type":"StickyModifierKey","modifier":"ctrl"},
              {"type":"MacroKey","label":"a",
                "tap":{"macro":[{"type":"tap","keys":[{"fcitx":"A"}]}]},
                "longPress":{"macro":[{"type":"tap","keys":[{"fcitx":"A"}]}]}}
            ]]}
            """.trimIndent()
        ).jsonObject
        withKeyboard(layout) { keyboard, service ->
            service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
            latch(service, StickyModifier.Ctrl)

            // MacroKey 配置了 longPress 时长按弹出候选菜单，不执行 tap，也不消费组合。
            assertFalse(keyboard.ordinaryKey().performLongClick())

            assertTrue(
                "长按不参与粘滞组合",
                service.stickyModifiers.isLatched(StickyModifier.Ctrl)
            )
        }
    }

    private fun swipe(key: KeyView, totalY: Int): Boolean = key.onGestureListener!!.onGesture(
        key,
        CustomGestureView.Event(
            type = CustomGestureView.GestureType.Up,
            consumed = false, x = 0f, y = 0f, countX = 0, countY = 0,
            totalX = 0, totalY = totalY
        )
    )

    private fun swipeLayout(swipeDirection: String, macro: String) = Json.parseToJsonElement(
        """
        {"default":[[
          {"type":"StickyModifierKey","modifier":"ctrl"},
          {"type":"MacroKey","label":"a",
            "tap":{"macro":[{"type":"tap","keys":[{"fcitx":"A"}]}]},
            "$swipeDirection":{"macro":[$macro]}}
        ]]}
        """.trimIndent()
    ).jsonObject

    @Test
    fun swipeUpSingleKeyMacroConsumesLatchedModifierAsShortcut() {
        withKeyboard(swipeLayout("swipeUp", """{"type":"tap","keys":[{"fcitx":"1"}]}""")) { keyboard, service ->
            service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
            val recorded = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> recorded += action }

            latch(service, StickyModifier.Ctrl)
            assertTrue("上滑手势必须被处理", swipe(keyboard.ordinaryKey(), totalY = -80))

            assertFalse(
                "上滑的单键行为必须消费粘滞组合",
                service.stickyModifiers.hasActiveModifiers
            )
            assertTrue(
                "快捷键必须走模拟物理键，不得产生虚拟键文本提交: $recorded",
                recorded.isEmpty()
            )
        }
    }

    @Test
    fun swipeDownSingleKeyMacroConsumesLatchedModifierAsShortcut() {
        withKeyboard(swipeLayout("swipeDown", """{"type":"tap","keys":[{"fcitx":"1"}]}""")) { keyboard, service ->
            service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
            val recorded = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> recorded += action }

            latch(service, StickyModifier.Ctrl)
            assertTrue("下滑手势必须被处理", swipe(keyboard.ordinaryKey(), totalY = 80))

            assertFalse(
                "下滑的单键行为必须消费粘滞组合",
                service.stickyModifiers.hasActiveModifiers
            )
            assertTrue(recorded.isEmpty())
        }
    }

    @Test
    fun swipeUpMultiStepMacroDoesNotConsumeLatchedModifier() {
        val multiStep = """{"type":"tap","keys":[{"fcitx":"1"}]},{"type":"tap","keys":[{"fcitx":"2"}]}"""
        withKeyboard(swipeLayout("swipeUp", multiStep)) { keyboard, service ->
            service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)

            latch(service, StickyModifier.Ctrl)
            swipe(keyboard.ordinaryKey(), totalY = -80)

            assertTrue(
                "多步宏不是单键行为，滑动不消费粘滞组合",
                service.stickyModifiers.isLatched(StickyModifier.Ctrl)
            )
        }
    }

    @Test
    fun composeRebindingAndRecreationReadTheExistingLatch() {        val layout = Json.parseToJsonElement(
            """
            {"default":[[
              {"type":"StickyModifierKey","modifier":"ctrl","label":"Ctrl",
                "composeOverride":{"modifier":"ctrl","label":"Composing","textColor":-65536,"independentColor":true}},
              {"type":"AlphabetKey","main":"a","alt":"!"}
            ]]}
            """.trimIndent()
        ).jsonObject
        withKeyboard(layout) { keyboard, service ->
            latch(service, StickyModifier.Ctrl)
            val original = keyboard.stickyKey()
            assertTrue(original.isActivated)

            keyboard.onCompositionStateChanged(true)
            val composing = keyboard.stickyKey()
            assertNotSame("the color override recreates the key view", original, composing)
            assertSame(StickyModifier.Ctrl, composing.stickyModifier)
            assertTrue(composing.isActivated)

            keyboard.onCompositionStateChanged(false)
            val restored = keyboard.stickyKey()
            assertSame(StickyModifier.Ctrl, restored.stickyModifier)
            assertTrue(restored.isActivated)

            val consumption = service.stickyModifiers.consume()!!
            assertFalse(restored.isActivated)
            service.stickyModifiers.finish(consumption)
        }
    }
}
