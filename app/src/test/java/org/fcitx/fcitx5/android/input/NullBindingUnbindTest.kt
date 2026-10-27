/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import android.util.LruCache
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.DirectRimeBackend
import org.fcitx.fcitx5.android.core.InputKeyGate
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class NullBindingUnbindTest {
    @Test fun nullBindingStillInvalidatesBothEditorAndEngineAndClearsPendingKeyEvents() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        assertNull("Exercise the OEM null-binding branch", service.currentInputBinding)
        service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
        val editor = service.editorSession.capture()!!
        val before = FcitxDaemon.invalidateInputSession() // Runtime events have a nonzero generation.
        val cache: LruCache<Int, KeyEvent> = ReflectionHelpers.getField(service, "cachedKeyEvents")
        cache.put(1, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        assertTrue(FcitxDaemon.isCurrentInputSession(before))
        service.onUnbindInput()
        assertFalse(editor.isCurrent())
        assertTrue(FcitxDaemon.currentInputSession > before)
        assertFalse("Old commit/candidate/key events fail the service's consumption fence",
            FcitxDaemon.isCurrentInputSession(before))
        assertEquals(0, cache.size())
    }

    @Test fun oldKeyTicketsStayInvalidWhenAnotherEditorBindsAfterANullBindingUnbind() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        assertNull(service.currentInputBinding)
        val backend: DirectRimeBackend = ReflectionHelpers.callInstanceMethod(FcitxDaemon, "getEngine")
        val gate: InputKeyGate = ReflectionHelpers.getField(backend, "keyGate")
        try {
            val before = FcitxDaemon.invalidateInputSession()
            FcitxDaemon.prepareInputSession(before, "app", CapabilityFlags.DefaultFlags, true)
            gate.running(); gate.resolve(before, true)
            val old = requireNotNull(FcitxDaemon.captureInputKey())
            assertTrue(FcitxDaemon.canDeliverInputKey(old))
            service.onUnbindInput()
            assertFalse(FcitxDaemon.canDeliverInputKey(old))
            val next = FcitxDaemon.currentInputSession
            FcitxDaemon.prepareInputSession(next, "next", CapabilityFlags.DefaultFlags, true)
            gate.resolve(next, true)
            val current = requireNotNull(FcitxDaemon.captureInputKey())
            assertTrue(FcitxDaemon.canDeliverInputKey(current))
            assertFalse(FcitxDaemon.canDeliverInputKey(old))
            FcitxDaemon.releaseInputKey(current)
        } finally { FcitxDaemon.invalidateInputSession(); gate.stop() }
    }
}
