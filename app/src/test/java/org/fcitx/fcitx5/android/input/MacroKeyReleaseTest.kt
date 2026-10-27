package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import android.view.View
import android.content.Context
import android.view.inputmethod.BaseInputConnection
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class MacroKeyReleaseTest {
    private class Connection(context: Context) : BaseInputConnection(View(context), false) {
        val events = mutableListOf<KeyEvent>()
        override fun sendKeyEvent(event: KeyEvent): Boolean { events += event; return true }
    }

    @Test fun staleModifierOnlyClearsItsCountWithoutSendingToTheNewEditor() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val editor = EditorSessionTracker.Editor("app", 1, 1, 0)
        service.editorSession.start(editor, false)
        val token = service.editorSession.capture()!!
        val replacement = Connection(service)
        ReflectionHelpers.setField(service, "mStartedInputConnection", replacement)
        ReflectionHelpers.setField(service, "simulatedCtrlPressedCount", 1)
        service.editorSession.start(editor.copy(fieldId = 2), false)
        service.releaseMacroKey(KeyEvent.KEYCODE_CTRL_LEFT, 0, token, cancelled = true)
        assertEquals(0, ReflectionHelpers.getField<Int>(service, "simulatedCtrlPressedCount"))
        assertTrue(replacement.events.isEmpty())
    }

    @Test fun interruptedCapsLockDoesNotToggleTheLock() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
        val token = service.editorSession.capture()!!
        ReflectionHelpers.setField(service, "simulatedCapsLockPressed", true)
        ReflectionHelpers.setField(service, "simulatedCapsLockPressedFromMacro", true)
        service.editorSession.end()
        service.releaseMacroKey(KeyEvent.KEYCODE_CAPS_LOCK, 0, token, cancelled = true)
        assertFalse(service.isSimulatedCapsLockOn())
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "simulatedCapsLockPressed"))
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "simulatedCapsLockPressedFromMacro"))
    }
}
