/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.status.activateStatusAreaAction
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class MaintenanceSaveTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher
    private lateinit var before: Snapshot

    @Before fun prepare() {
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ReflectionHelpers.setField(backend, "started", true)
        val generation = backend.invalidateInputSession()
        backend.prepareInputSession(generation, "editor", CapabilityFlags.DefaultFlags, true)
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").apply { running(); resolve(generation, true) }
        before = Snapshot(generation, 1, 0, false, 0, "", "ni", "fixture", false,
            0, true, 2, arrayOf("你"), emptyArray(), emptyArray(), "Fixture", 0, "你", arrayOf("1"))
        ReflectionHelpers.setField(backend, "snapshot", before)
        NativeTestShadow.delegate = object : NativeTestDelegate {}
    }

    @After fun close() {
        ReflectionHelpers.setField(backend, "started", false)
        backend.lifeCycleScope.cancel()
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    @Test fun backupSaveBlocksInputAndReportsSyncFailure() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeTestShadow.delegate = object : NativeTestDelegate {
            override fun end(value: Long) = before
            override fun sync(): Boolean {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return false
            }
        }
        val ticket = requireNotNull(backend.captureInputKey())
        val saving = async(Dispatchers.Default) { runCatching { backend.save() } }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            assertFalse(backend.canDeliverInputKey(ticket))
            assertNull(backend.captureInputKey())
            backend.invalidateInputSession() // A focus change during sync must not rebind the old editor.
            release.countDown()
            val error = withTimeout(5_000) { saving.await() }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("用户词典同步失败", error?.message)
            assertFalse(backend.acceptsInput)
        } finally { release.countDown(); saving.cancelAndJoin(); backend.releaseInputKey(ticket) }
    }

    @Test fun unavailableEngineDoesNotBlockExportingExistingFiles() = runBlocking {
        ReflectionHelpers.setField(backend, "started", false)
        backend.save() // The default delegate throws if any native API is reached.
    }

    @Test fun failedSyncFromTheStatusMenuReportsTheErrorWithoutEscapingItsCoroutine() = runBlocking {
        var restoredGeneration = -1L
        NativeTestShadow.delegate = object : NativeTestDelegate {
            override fun end(value: Long) = before
            override fun sync() = false
            override fun sessionStats() = longArrayOf(0, 0)
            override fun beginInput(value: Long, program: String, sensitive: Boolean): Snapshot {
                restoredGeneration = value
                return Snapshot(value, 2, 0, false, 4, "", "", "", false,
                    0, true, 0, emptyArray(), emptyArray(), emptyArray(), "", 0, "", emptyArray())
            }
        }
        var error: Exception? = null
        backend.activateStatusAreaAction(12) { error = it }
        assertEquals("用户词典同步失败", error?.message)
        assertTrue("Failure must restore the current generation without reviving the old session", restoredGeneration > before.generation)
        assertFalse(ReflectionHelpers.getField<Boolean>(backend, "maintenance"))
        assertFalse("A failed native maintenance must not accept ordinary Rime input", backend.acceptsInput)
    }

    @Test fun cancellingStatusMenuSyncIsNotTurnedIntoAnErrorToast() = runBlocking {
        NativeTestShadow.delegate = object : NativeTestDelegate {
            override fun end(value: Long) = before
            override fun sync(): Boolean {
                backend.invalidateInputSession()
                throw CancellationException("sync cancelled")
            }
        }
        var reported = false
        val result = runCatching { backend.activateStatusAreaAction(12) { reported = true } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(reported)
        assertFalse(ReflectionHelpers.getField<Boolean>(backend, "maintenance"))
    }
}
