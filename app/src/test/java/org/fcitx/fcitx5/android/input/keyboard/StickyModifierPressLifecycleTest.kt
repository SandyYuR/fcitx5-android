/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.activity.ComponentActivity
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 粘滞修饰键的按压生命周期（对应行为矩阵「表 1 / 表 3」）。
 *
 * 规则：
 * - Down 只表示「按到了」：按下反馈走通用按压高亮，真实状态与键面激活色都不变；
 * - Up 且可点击才 toggle；
 * - 取消、滑出键外都保持原状态；
 * - 修饰键之间是叠加，不互相消费。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class StickyModifierPressLifecycleTest {
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
        block: (TextKeyboard, FcitxInputMethodService) -> Unit
    ) = TextKeyboard.withPreviewLayout(layout) {
        SplitKeyboardStateManager.init(RuntimeEnvironment.getApplication())
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val entry = ime("first")
        val keyboard = TextKeyboard(service, ThemePreset.PixelLight, entry, isPreview = true)
        keyboard.onInputMethodUpdate(entry)
        try {
            activity.get().setContentView(keyboard)
            keyboard.onAttach()
            assertTrue(keyboard.isAttachedToWindow)
            block(keyboard, service)
        } finally {
            service.stickyModifiers.cancelAll()
            keyboard.onDetach()
            activity.get().finish()
            activity.pause().stop().destroy()
        }
    }

    private fun TextKeyboard.stickyKey(modifier: StickyModifier): KeyView =
        allViews.filterIsInstance<KeyView>().single { it.stickyModifier == modifier }

    private fun dispatch(
        view: KeyView,
        type: CustomGestureView.GestureType,
        clickEligible: Boolean = false,
        cancelled: Boolean = false,
        totalY: Int = 0
    ) {
        view.onGestureListener?.onGesture(
            view,
            CustomGestureView.Event(
                type = type,
                consumed = false,
                x = 0f, y = 0f,
                countX = 0, countY = 0,
                totalX = 0, totalY = totalY,
                cancelled = cancelled,
                clickEligible = clickEligible
            )
        )
    }

    /** 模拟一次完整物理按压：先置按下态（触发通用按压高亮），再派发 Down / Up。 */
    private fun press(
        view: KeyView,
        clickEligible: Boolean,
        cancelled: Boolean = false
    ) {
        view.isPressed = true
        dispatch(view, CustomGestureView.GestureType.Down)
        view.isPressed = false
        dispatch(view, CustomGestureView.GestureType.Up, clickEligible, cancelled)
    }

    private val stickyLayout = Json.parseToJsonElement(
        """
        {"default":[[
          {"type":"StickyModifierKey","modifier":"ctrl"},
          {"type":"StickyModifierKey","modifier":"shift"},
          {"type":"AlphabetKey","main":"a","alt":"!"}
        ]]}
        """.trimIndent()
    ).jsonObject

    @Test
    fun pressShowsFeedbackButDoesNotLatchUntilUp() {
        withKeyboard(stickyLayout) { keyboard, service ->
            val key = keyboard.stickyKey(StickyModifier.Ctrl)

            key.isPressed = true
            dispatch(key, CustomGestureView.GestureType.Down)
            assertFalse("按下未抬手时真实状态不变", service.stickyModifiers.isLatched(StickyModifier.Ctrl))
            assertFalse("按下不改变点亮状态，只有抬手才切换", key.isActivated)
            key.isPressed = false
            dispatch(key, CustomGestureView.GestureType.Up, clickEligible = true)
            assertTrue(service.stickyModifiers.isLatched(StickyModifier.Ctrl))
            assertTrue(key.isActivated)
        }
    }

    @Test
    fun secondTapTurnsLatchOff() {
        withKeyboard(stickyLayout) { keyboard, service ->
            val key = keyboard.stickyKey(StickyModifier.Ctrl)
            press(key, clickEligible = true)
            assertTrue(service.stickyModifiers.isLatched(StickyModifier.Ctrl))

            key.isPressed = true
            dispatch(key, CustomGestureView.GestureType.Down)
            assertTrue("已点亮时按下不提前熄灭，抬手后才切换", key.isActivated)

            key.isPressed = false
            dispatch(key, CustomGestureView.GestureType.Up, clickEligible = true)
            assertFalse(service.stickyModifiers.isLatched(StickyModifier.Ctrl))
            assertFalse(key.isActivated)
        }
    }

    @Test
    fun cancelDuringPressRestoresPreviousState() {
        withKeyboard(stickyLayout) { keyboard, service ->
            val key = keyboard.stickyKey(StickyModifier.Ctrl)
            press(key, clickEligible = true)

            key.isPressed = true
            dispatch(key, CustomGestureView.GestureType.Down)
            key.isPressed = false
            dispatch(key, CustomGestureView.GestureType.Up, clickEligible = false, cancelled = true)

            assertTrue("取消不改动已点亮状态", service.stickyModifiers.isLatched(StickyModifier.Ctrl))
            assertTrue(key.isActivated)
        }
    }

    @Test
    fun ineligibleUpOnUnlatchedKeyKeepsItOff() {
        withKeyboard(stickyLayout) { keyboard, service ->
            val key = keyboard.stickyKey(StickyModifier.Shift)

            // 非单击（长按/滑动/滑出）不点亮，也不残留待定手势。
            press(key, clickEligible = false)
            assertFalse(service.stickyModifiers.isLatched(StickyModifier.Shift))
            assertFalse(key.isActivated)

            // 下一次正常点击仍然有效。
            press(key, clickEligible = true)
            assertTrue(service.stickyModifiers.isLatched(StickyModifier.Shift))
        }
    }

    @Test
    fun tappingAnotherModifierStacksInsteadOfConsuming() {
        withKeyboard(stickyLayout) { keyboard, service ->
            press(keyboard.stickyKey(StickyModifier.Ctrl), clickEligible = true)
            press(keyboard.stickyKey(StickyModifier.Shift), clickEligible = true)

            assertTrue(service.stickyModifiers.isLatched(StickyModifier.Ctrl))
            assertTrue(service.stickyModifiers.isLatched(StickyModifier.Shift))
            assertEquals(
                listOf(StickyModifier.Ctrl, StickyModifier.Shift),
                service.stickyModifiers.consume()?.modifiers
            )
        }
    }

    @Test
    fun consecutiveTapsCycleLatchOffOnThenOff() {
        withKeyboard(stickyLayout) { keyboard, service ->
            val key = keyboard.stickyKey(StickyModifier.Ctrl)

            press(key, clickEligible = true)
            assertTrue(key.isActivated)
            press(key, clickEligible = true)
            assertFalse(key.isActivated)
            press(key, clickEligible = true)
            assertTrue(key.isActivated)
        }
    }

    /**
     * 上滑被滑动动作消费时，粘滞状态机也必须收到同一次 Up。
     *
     * 否则 `stickyModifierGesture` 会一直挂着，下一次按下会被当成重复手势忽略，
     * 整个粘滞键从此「点不动」。
     */
    @Test
    fun swipeConsumedUpStillFinishesTheStickyGesture() {
        val layout = Json.parseToJsonElement(
            """
            {"default":[[
              {"type":"StickyModifierKey","modifier":"ctrl","swipeUpLabel":"Up",
               "swipeUp":{"macro":[{"type":"text","text":"u"}]}}
            ]]}
            """.trimIndent()
        ).jsonObject
        withKeyboard(layout) { keyboard, service ->
            val key = keyboard.stickyKey(StickyModifier.Ctrl)

            dispatch(key, CustomGestureView.GestureType.Down)
            dispatch(key, CustomGestureView.GestureType.Up, clickEligible = false, totalY = -1)
            assertFalse(service.stickyModifiers.isLatched(StickyModifier.Ctrl))
            assertNull("被滑动消费的 Up 也必须收尾待定手势", key.stickyModifierGesture)

            press(key, clickEligible = true)
            assertTrue(
                "滑动之后的下一次点击仍须能用",
                service.stickyModifiers.isLatched(StickyModifier.Ctrl)
            )
        }
    }
}
