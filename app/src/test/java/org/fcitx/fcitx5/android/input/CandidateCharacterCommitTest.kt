/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputKeyGate
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestDelegate
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class])
class CandidateCharacterCommitTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val gate = InputKeyGate().apply { running(); prepare(1L, true); resolve(1L, true) }
    private var resets = 0
    private var releases = 0
    private var afterReset: () -> Unit = {}
    private val api = Proxy.newProxyInstance(FcitxAPI::class.java.classLoader,
        arrayOf(FcitxAPI::class.java)) { _, method, _ ->
        check(method.name == "reset") { "Unexpected API call: " + method.name }
        resets++
        afterReset()
        Unit
    } as FcitxAPI
    private val connection = object : FcitxConnection {
        override val interactiveDispatcher = Dispatchers.Unconfined
        override val lifecycleScope = scope
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { block(api) }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(api)
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) = Unit
    }
    private val editor = EditorSessionTracker.Editor("example.editor", 1, 1, 0)
    private fun service() = Robolectric.buildService(FcitxInputMethodService::class.java).get().also {
        FcitxDaemonTestShadow.install(object : FcitxDaemonTestDelegate {
            override fun requireInputSession() = false
            override fun captureInputKey() = gate.capture()
            override fun canDeliverInputKey(ticket: InputKeyGate.Ticket) = gate.canDeliver(ticket)
            override fun releaseInputKey(ticket: InputKeyGate.Ticket) { gate.release(ticket); releases++ }
        })
        ReflectionHelpers.setField(it, "fcitx", connection)
        it.editorSession.start(editor, false)
    }
    private class Editor(service: FcitxInputMethodService) : BaseInputConnection(View(service), true) {
        val commits = mutableListOf<String>()
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            commits += text.toString()
            return true
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun runQueued(service: FcitxInputMethodService, job: Job) {
        val jobs = ReflectionHelpers.getField<Channel<Job>>(service, "jobs")
        assertSame(job, jobs.tryReceive().getOrThrow())
        job.start()
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { withTimeout(5_000) { job.join() } }
    }
    @After fun tearDown() { scope.cancel(); FcitxDaemonTestShadow.reset() }

    @Test fun currentEditorResetsBeforeExactlyOneCommitAndReleasesItsTicket() {
        val service = service()
        val editor = Editor(service)
        service.jsAppInputConnection = editor
        afterReset = { assertTrue(editor.commits.isEmpty()) }
        runQueued(service, service.commitCandidateCharacter("字"))
        assertEquals(1, resets)
        assertEquals(listOf("字"), editor.commits)
        assertEquals(1, releases)
    }

    @Test fun replacingTheEditorWhileQueuedDropsResetAndCommit() {
        val service = service()
        val old = Editor(service)
        val replacement = Editor(service)
        service.jsAppInputConnection = old
        val job = service.commitCandidateCharacter("旧")
        service.editorSession.start(editor.copy(fieldId = 2), false)
        service.jsAppInputConnection = replacement
        runQueued(service, job)
        assertEquals(0, resets)
        assertTrue(old.commits.isEmpty())
        assertTrue(replacement.commits.isEmpty())
        assertEquals(1, releases)
    }

    @Test fun mainCommitRechecksEditorConnectionAndTicketAfterReset() {
        for (invalidation in listOf("token", "connection", "ticket")) {
            val service = service()
            val old = Editor(service)
            val replacement = Editor(service)
            service.jsAppInputConnection = old
            afterReset = {
                when (invalidation) {
                    "token" -> service.editorSession.end()
                    "connection" -> service.jsAppInputConnection = replacement
                    "ticket" -> gate.invalidate(2L)
                }
            }
            runQueued(service, service.commitCandidateCharacter("旧"))
            assertTrue(invalidation, old.commits.isEmpty())
            assertTrue(invalidation, replacement.commits.isEmpty())
        }
        assertEquals(3, resets)
        assertEquals(3, releases)
    }

    @Test fun maintenanceBeforeQueueDeliveryDropsTheCharacter() {
        val service = service()
        val editor = Editor(service)
        service.jsAppInputConnection = editor
        val job = service.commitCandidateCharacter("旧")
        gate.block()
        runQueued(service, job)
        assertEquals(0, resets)
        assertTrue(editor.commits.isEmpty())
        assertEquals(1, releases)
    }
}
