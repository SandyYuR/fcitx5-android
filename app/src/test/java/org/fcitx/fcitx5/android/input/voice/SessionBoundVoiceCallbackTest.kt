package org.fcitx.fcitx5.android.input.voice

import android.app.Application
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SessionBoundVoiceCallbackTest {
    private val pending = ArrayDeque<() -> Unit>()
    private val received = mutableListOf<String>()
    private val delegate = object : IVoiceInputCallback.Stub() {
        override fun onReady() { received += "ready" }
        override fun onVolumeLevel(rms: Int) { received += "volume:$rms" }
        override fun onPartialResult(text: String?) { received += "partial:$text" }
        override fun onSegmentFinal(text: String?) { received += "final:$text" }
        override fun onSessionEnded() { received += "ended" }
        override fun onError(code: Int, message: String?) { received += "error:$code" }
    }
    private fun drain() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
    private fun sendAll(callback: IVoiceInputCallback) {
        callback.onReady(); callback.onVolumeLevel(7); callback.onPartialResult("partial")
        callback.onSegmentFinal("final"); callback.onSessionEnded(); callback.onError(3, "error")
    }
    @Test fun everyEventIsDeliveredInOrderOnTheDispatcher() {
        sendAll(SessionBoundVoiceCallback({ pending.addLast(it) }, { true }, delegate))
        assertTrue(received.isEmpty())
        drain()
        assertEquals(listOf("ready", "volume:7", "partial:partial", "final:final", "ended", "error:3"), received)
    }
    @Test fun connectionAndSessionGuardsShareOneDispatch() {
        val base = SessionBoundVoiceCallback({ pending.addLast(it) }, { true }, delegate)
        base.guardedBy { true }.onReady()
        assertEquals(1, pending.size)
        pending.removeFirst().invoke()
        assertTrue(pending.isEmpty())
        assertEquals(listOf("ready"), received)
    }
    @Test fun theConnectionGuardIsAlsoCheckedAtDeliveryTime() {
        var connectionCurrent = true
        val base = SessionBoundVoiceCallback({ pending.addLast(it) }, { true }, delegate)
        base.guardedBy { connectionCurrent }.onSegmentFinal("old attempt")
        connectionCurrent = false
        drain()
        assertTrue(received.isEmpty())
    }
    @Test fun queuedCallbacksAreRejectedWhenTheProviderSessionChanges() {
        var generation = 1
        sendAll(SessionBoundVoiceCallback({ pending.addLast(it) }, { generation == 1 }, delegate))
        generation = 2
        drain()
        assertTrue(received.isEmpty())
    }
    @Test fun editorChangeRejectsEveryTypeOfQueuedResult() {
        val tracker = EditorSessionTracker()
        val editor = EditorSessionTracker.Editor("app", 1, 1, 0)
        tracker.start(editor, false)
        val token = tracker.capture()!!
        sendAll(SessionBoundVoiceCallback({ pending.addLast(it) }, token::isCurrent, delegate))
        tracker.start(editor.copy(fieldId = 2), false)
        drain()
        assertTrue(received.isEmpty())
    }
    @Test fun sameEditorRestartKeepsStreamingResultsValid() {
        val tracker = EditorSessionTracker()
        val editor = EditorSessionTracker.Editor("app", 1, 1, 0)
        tracker.start(editor, false)
        val token = tracker.capture()!!
        val callback = SessionBoundVoiceCallback({ pending.addLast(it) }, token::isCurrent, delegate)
        callback.onPartialResult("before restart")
        tracker.start(editor, true)
        callback.onSegmentFinal("after restart")
        drain()
        assertEquals(listOf("partial:before restart", "final:after restart"), received)
    }
}
