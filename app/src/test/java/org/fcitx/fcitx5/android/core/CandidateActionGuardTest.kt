/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exercise the real native-owner queue. Rejected requests must never reach JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CandidateActionGuardTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher
    private val expected = CandidateActionContext(7, 20)

    @Before fun setUp() {
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ReflectionHelpers.getField<AtomicLong>(backend, "epoch").set(7)
        ReflectionHelpers.setField(backend, "candidateVersion", 20L)
        ReflectionHelpers.setField(backend, "started", true)
        ReflectionHelpers.setField(backend, "schemas", listOf("fixture" to "Fixture"))
        ReflectionHelpers.setField(backend, "snapshot", Snapshot(7, 11, 0, false, 0, "", "ni", "fixture", false,
            0, true, 2, arrayOf("你"), emptyArray(), emptyArray(), "Fixture", 0, "你", arrayOf("1")))
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").apply {
            running(); invalidate(7); prepare(7, true); resolve(7, true)
        }
    }

    @After fun tearDown() {
        ReflectionHelpers.setField(backend, "started", false)
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    @Test fun candidateContextIncludesBothEditorAndCandidateVersion() {
        assertEquals(expected, backend.candidateActionContext)
    }

    @Test fun staleCandidatesAndEditorsAreRejectedBeforeNativeWork() = runBlocking {
        for (context in listOf(expected.copy(inputSession = 6), expected.copy(version = 19))) {
            assertFalse(backend.triggerCandidateActionIfCurrent(0, 0, false, context))
            assertFalse(backend.triggerCandidateActionIfCurrent(0, 0, true, context))
        }
        assertFalse(backend.triggerCandidateActionIfCurrent(-1, 0, false, expected))
    }

    private fun queuedChange(change: () -> Unit, action: suspend () -> Boolean) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(owner) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            // UNDISTPATCHED reaches withContext(owner) before returning: the request is admitted,
            // then waits behind the blocker while the editor/candidate context changes.
            val result = async(start = CoroutineStart.UNDISPATCHED) { action() }
            change()
            release.countDown()
            assertFalse(withTimeout(5000) { result.await() })
        } finally { release.countDown(); blocker.join() }
    }

    @Test fun candidateVersionIsRecheckedOnTheActualNativeOwner() = queuedChange(
        { ReflectionHelpers.setField(backend, "candidateVersion", 21L) },
        { backend.triggerCandidateActionIfCurrent(0, 0, false, expected) }
    )

    @Test fun aNewEditorCannotReceiveAnAlreadyQueuedGlobalCandidateAction() = queuedChange(
        { backend.invalidateInputSession() },
        { backend.triggerCandidateActionIfCurrent(0, 0, true, expected) }
    )

    @Test fun aQueuedSchemaSelectionIsRejectedAfterAnEditorChange() = queuedChange(
        { backend.invalidateInputSession() },
        { backend.activateSchemaActionIfCurrent(100, 7) }
    )

    @Test fun anUnknownSchemaActionNeverReachesNativeCode() = runBlocking {
        assertFalse(backend.activateSchemaActionIfCurrent(101, 7))
        assertFalse(backend.activateSchemaActionIfCurrent(100, 6))
    }
}
