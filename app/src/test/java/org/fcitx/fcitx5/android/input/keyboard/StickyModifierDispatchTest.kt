/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.util.LruCache
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class StickyModifierDispatchTest {
    private fun withKeyboard(
        keys: String,
        block: (TextKeyboard, FcitxInputMethodService, MutableList<KeyAction>) -> Unit
    ) {
        val layout = Json.parseToJsonElement("""{"default":[[$keys]]}""").jsonObject
        TextKeyboard.withPreviewLayout(layout) {
            SplitKeyboardStateManager.init(RuntimeEnvironment.getApplication())
            val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val ime = InputMethodEntry("sticky-dispatch")
            val keyboard = TextKeyboard(service, ThemePreset.PixelLight, ime, isPreview = true)
            keyboard.onInputMethodUpdate(ime)
            val recorded = mutableListOf<KeyAction>()
            try {
                activity.get().setContentView(keyboard)
                keyboard.onAttach()
                assertTrue(keyboard.isAttachedToWindow)
                service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
                keyboard.keyActionListener = KeyActionListener { action, _ -> recorded += action }
                block(keyboard, service, recorded)
            } finally {
                service.stickyModifiers.cancelAll()
                keyboard.onDetach()
                activity.get().finish()
                activity.pause().stop().destroy()
            }
        }
    }

    private fun macroKey(code: String): String =
        """{"type":"MacroKey","label":"key","tap":{"macro":[{"type":"tap","keys":[{"fcitx":${JsonPrimitive(code)}}]}]}}"""

    private fun TextKeyboard.keys(): List<KeyView> = allViews.filterIsInstance<KeyView>().toList()

    private fun latch(service: FcitxInputMethodService, vararg modifiers: StickyModifier) {
        modifiers.forEach { modifier ->
            val gesture = service.stickyModifiers.onDown(modifier)!!
            service.stickyModifiers.onUp(modifier, gesture, clickEligible = true, cancelled = false)
        }
    }

    // Observe the real simulated KeyEvents queued for the backend, without loading native Rime.
    private fun events(service: FcitxInputMethodService): List<KeyEvent> =
        ReflectionHelpers.getField<LruCache<Int, KeyEvent>>(service, "cachedKeyEvents")
            .snapshot().values.toList()

    private fun assertShortcut(
        service: FcitxInputMethodService,
        keyCode: Int,
        scanCode: Int,
        shift: Boolean = false
    ) {
        val actual = events(service)
        val downKeys = if (shift) {
            listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, keyCode)
        } else listOf(KeyEvent.KEYCODE_CTRL_LEFT, keyCode)
        assertEquals(downKeys + downKeys.asReversed(), actual.map { it.keyCode })
        assertEquals(
            List(downKeys.size) { KeyEvent.ACTION_DOWN } + List(downKeys.size) { KeyEvent.ACTION_UP },
            actual.map { it.action }
        )
        val target = actual.single { it.action == KeyEvent.ACTION_DOWN && it.keyCode == keyCode }
        assertEquals(scanCode, target.scanCode)
        assertTrue(target.toString(), target.isCtrlPressed)
        assertEquals(target.toString(), shift, target.isShiftPressed)
        assertFalse(service.stickyModifiers.hasActiveModifiers)
        assertTrue(service.stickyModifiers.onDown(StickyModifier.Ctrl) != null)
        service.stickyModifiers.cancelAll()

        service.sendSimulatedKeyEvent(KeyEvent.KEYCODE_B, 48, KeyEvent.ACTION_DOWN)
        val next = events(service).last()
        assertFalse("Ctrl must be released: $next", next.isCtrlPressed)
        assertFalse("temporary Shift must be released: $next", next.isShiftPressed)
        service.sendSimulatedKeyEvent(KeyEvent.KEYCODE_B, 48, KeyEvent.ACTION_UP)
    }

    private fun setCaps(keyboard: TextKeyboard, lock: Boolean = false) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            keyboard, "onAction",
            ClassParameter.from(KeyAction::class.java, KeyAction.CapsAction(lock)),
            ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Keyboard)
        )
    }

    private fun caps(keyboard: TextKeyboard): TextKeyboard.CapsState =
        ReflectionHelpers.getField(keyboard, "capsState")

    @Test
    fun ctrlCommaEmitsAPairedShortcutFromTheBuiltinKey() {
        withKeyboard("""{"type":"CommaKey"}""") { keyboard, service, recorded ->
            latch(service, StickyModifier.Ctrl)
            assertTrue(keyboard.keys().single().performClick())
            assertShortcut(service, KeyEvent.KEYCODE_COMMA, 51)
            assertTrue("must not commit text or emit an unmodified virtual key", recorded.isEmpty())
        }
    }

    @Test
    fun ctrlPeriodEmitsAPairedShortcutFromALiteralMacro() {
        withKeyboard(macroKey(".")) { keyboard, service, recorded ->
            latch(service, StickyModifier.Ctrl)
            assertTrue(keyboard.keys().single().performClick())
            assertShortcut(service, KeyEvent.KEYCODE_PERIOD, 52)
            assertTrue(recorded.isEmpty())
        }
    }

    @Test
    fun ctrlUnderscoreKeepsTheSymbolsRequiredShift() {
        withKeyboard(macroKey("underscore")) { keyboard, service, recorded ->
            latch(service, StickyModifier.Ctrl)
            assertTrue(keyboard.keys().single().performClick())
            assertShortcut(service, KeyEvent.KEYCODE_MINUS, 12, shift = true)
            assertTrue(recorded.isEmpty())
        }
    }

    @Test
    fun literalShiftedSymbolsKeepTheirPhysicalKeyAndShift() {
        val symbols = listOf(
            Triple("_", KeyEvent.KEYCODE_MINUS, 12),
            Triple("?", KeyEvent.KEYCODE_SLASH, 53),
            Triple("!", KeyEvent.KEYCODE_1, 2),
            Triple("<", KeyEvent.KEYCODE_COMMA, 51)
        )
        symbols.forEach { (symbol, keyCode, scanCode) ->
            withKeyboard(macroKey(symbol)) { keyboard, service, recorded ->
                latch(service, StickyModifier.Ctrl)
                assertTrue(keyboard.keys().single().performClick())
                assertShortcut(service, keyCode, scanCode, shift = true)
                assertTrue(recorded.isEmpty())
            }
        }
    }

    @Test
    fun requiredShiftIsNotPressedTwiceWhenAlreadySticky() {
        withKeyboard(macroKey("underscore")) { keyboard, service, _ ->
            latch(service, StickyModifier.Ctrl, StickyModifier.Shift)
            assertTrue(keyboard.keys().single().performClick())
            assertShortcut(service, KeyEvent.KEYCODE_MINUS, 12, shift = true)
        }
    }

    @Test
    fun shiftedKeysymAlsoKeepsTheSymbolsRequiredShift() {
        withKeyboard("""{"type":"CommaKey"}""") { keyboard, service, recorded ->
            latch(service, StickyModifier.Ctrl)
            ReflectionHelpers.callInstanceMethod<Unit>(
                keyboard, "onStickyConsumableAction",
                ClassParameter.from(KeyAction::class.java, KeyAction.SymAction(KeySym('_'.code)))
            )
            assertShortcut(service, KeyEvent.KEYCODE_MINUS, 12, shift = true)
            assertTrue(recorded.isEmpty())
        }
    }

    @Test
    fun uppercaseLayoutLetterRemainsCtrlAWithoutAnImplicitShift() {
        withKeyboard(macroKey("A")) { keyboard, service, recorded ->
            latch(service, StickyModifier.Ctrl)
            assertTrue(keyboard.keys().single().performClick())
            assertShortcut(service, KeyEvent.KEYCODE_A, 30)
            assertTrue(recorded.isEmpty())
        }
    }

    private fun assertShiftLetterConsumesOnce(firstKey: String) {
        withKeyboard("""$firstKey,{"type":"AlphabetKey","main":"b","alt":"!"}""") { keyboard, service, recorded ->
            setCaps(keyboard)
            assertEquals(TextKeyboard.CapsState.Once, caps(keyboard))
            recorded.clear()
            latch(service, StickyModifier.Shift)
            assertTrue(keyboard.keys().first().performClick())
            assertEquals(TextKeyboard.CapsState.None, caps(keyboard))
            assertFalse(service.stickyModifiers.hasActiveModifiers)
            val pair = recorded.filterIsInstance<KeyAction.FcitxKeyAction>()
            assertEquals(listOf("A", "A"), pair.map { it.act })
            assertEquals(listOf(false, true), pair.map { it.up })
            pair.forEach { assertEquals(KeyStates(KeyState.Virtual, KeyState.Shift), it.states) }
            recorded.clear()
            assertTrue(keyboard.keys().last().performClick())
            val next = recorded.filterIsInstance<KeyAction.FcitxKeyAction>().first()
            assertEquals("b", next.act)
            assertFalse(next.states.shift)
        }
    }

    @Test
    fun stickyShiftAlphabetConsumesOnceAndTheNextLetterIsLowercase() {
        assertShiftLetterConsumesOnce("""{"type":"AlphabetKey","main":"a","alt":"!"}""")
    }

    @Test
    fun stickyShiftSingleTapMacroConsumesOnceAndTheNextLetterIsLowercase() {
        assertShiftLetterConsumesOnce(macroKey("A"))
    }

    @Test
    fun stickyShiftDoesNotArmCapsOnceWhenCapsWasOff() {
        withKeyboard("""{"type":"AlphabetKey","main":"a","alt":"!"}""") { keyboard, service, _ ->
            assertEquals(TextKeyboard.CapsState.None, caps(keyboard))
            latch(service, StickyModifier.Shift)
            assertTrue(keyboard.keys().single().performClick())
            assertEquals(TextKeyboard.CapsState.None, caps(keyboard))
        }
    }

    @Test
    fun stickyShiftPreservesCapsLock() {
        withKeyboard("""{"type":"AlphabetKey","main":"a","alt":"!"}""") { keyboard, service, _ ->
            setCaps(keyboard, lock = true)
            assertEquals(TextKeyboard.CapsState.Lock, caps(keyboard))
            latch(service, StickyModifier.Shift)
            assertTrue(keyboard.keys().single().performClick())
            assertEquals(TextKeyboard.CapsState.Lock, caps(keyboard))
        }
    }

    @Test
    fun editorEndingAfterVirtualDownDoesNotReceiveAStaleUp() {
        withKeyboard("""{"type":"AlphabetKey","main":"a","alt":"!"}""") { keyboard, service, recorded ->
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                recorded += action
                service.editorSession.end()
            }
            latch(service, StickyModifier.Shift)
            assertTrue(keyboard.keys().single().performClick())
            val letter = recorded.single() as KeyAction.FcitxKeyAction
            assertEquals("A", letter.act)
            assertFalse(letter.up)
            assertFalse(service.stickyModifiers.hasActiveModifiers)
            assertTrue(service.stickyModifiers.onDown(StickyModifier.Shift) != null)
        }
    }
}
