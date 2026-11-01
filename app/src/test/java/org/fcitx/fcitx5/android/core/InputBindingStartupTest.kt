/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.fcitx.directrime.Native
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/** Real owner queue, binding, ticket and delivery code; only the JNI boundary is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, BindingNativeShadow::class])
class InputBindingStartupTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher

    @Before fun resetNative() { BindingNativeShadow.reset() }

    private fun createBackend(directBoot: Boolean = false) {
        ReflectionHelpers.setField(FcitxApplication.getInstance(), "isDirectBootMode", directBoot)
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ReflectionHelpers.setField(backend, "started", true)
        ReflectionHelpers.setField(backend, "schemas", listOf("initial" to "Initial", "preferred" to "Preferred"))
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").running()
    }

    private fun prepare(flags: CapabilityFlags = CapabilityFlags.DefaultFlags): Long =
        backend.invalidateInputSession().also { backend.prepareInputSession(it, "editor", flags, true) }

    @After fun closeBackend() {
        if (!::backend.isInitialized) return
        ReflectionHelpers.setField(backend, "started", false)
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    @Test fun schemaAndAsciiRestorationAndStatusReadKeepInteractivePriority() = runBlocking {
        createBackend()
        ReflectionHelpers.setField(backend, "preferredSchema", "preferred")
        ReflectionHelpers.setField(backend, "preferredAscii", true)
        withContext(owner) { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }

        backend.startInputSession(prepare(), "editor")

        assertEquals(listOf("begin", "schema", "ascii", "options"), BindingNativeShadow.calls.map { it.phase })
        assertTrue(BindingNativeShadow.calls.all { it.priority <= Process.THREAD_PRIORITY_DISPLAY })
        assertEquals(1, BindingNativeShadow.calls.map { it.thread }.distinct().size)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND,
            withContext(owner) { Process.getThreadPriority(Process.myTid()) })
        assertTrue(backend.acceptsInput)
        assertEquals("Latin Mode", backend.inputMethodEntryCached.subMode.name)
        val result = ReflectionHelpers.getField<Snapshot>(backend, "snapshot")
        assertEquals("preferred", result.schema)
        assertTrue(result.ascii)
    }

    @Test fun restorationFailureStillRestoresTheOwnerThreadPriority() = runBlocking {
        createBackend()
        ReflectionHelpers.setField(backend, "preferredSchema", "preferred")
        BindingNativeShadow.failAction = true
        withContext(owner) { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val generation = prepare()

        val failure = runCatching { backend.startInputSession(generation, "editor") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(backend.acceptsInput)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND,
            withContext(owner) { Process.getThreadPriority(Process.myTid()) })
    }

    @Test fun passwordWithoutASchemaStillDeliversOrderedDownAndUp() = runBlocking {
        createBackend()
        BindingNativeShadow.gate = 2
        val generation = prepare(CapabilityFlags(CapabilityFlag.Password))
        val waiting = requireNotNull(backend.captureInputKey())
        backend.startInputSession(generation, "editor")
        backend.focus(true)
        backend.startInputSession(generation, "editor")
        assertEquals("Repeated focus must not rebind an already usable direct editor", 1,
            BindingNativeShadow.calls.count { it.phase == "begin" })
        assertTrue(BindingNativeShadow.noLearning)
        assertTrue(backend.canDeliverInputKey(waiting))
        assertTrue(backend.inputPanelCached.auxUp.toString().isEmpty())

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { backend.eventFlow.filterIsInstance<FcitxEvent.KeyEvent>().take(4).toList() }
        }
        backend.sendKey('a', 0u, 0, false, 1)
        backend.sendKey('a', 0u, 0, true, 2)
        backend.sendKey(0xff08, 0u, 0, false, 3) // Backspace follows the letter pair.
        backend.sendKey(0xff08, 0u, 0, true, 4)
        val keys = received.await()
        assertEquals(listOf('a'.code, 'a'.code, 0xff08, 0xff08), keys.map { it.data.sym.sym })
        assertEquals(listOf(false, true, false, true), keys.map { it.data.up })
        assertTrue(keys.all { it.inputSession == generation })
        backend.releaseInputKey(waiting)
        val old = requireNotNull(backend.captureInputKey())
        backend.invalidateInputSession()
        assertFalse(backend.canDeliverInputKey(old))
    }

    @Test fun directBootAlsoForwardsOrdinaryLockScreenEditorsWithoutASchema() = runBlocking {
        createBackend(directBoot = true)
        BindingNativeShadow.gate = 2
        backend.startInputSession(prepare(), "editor")
        backend.setCapFlags(CapabilityFlags.DefaultFlags)
        assertTrue(backend.acceptsInput)
        assertTrue(BindingNativeShadow.noLearning)
        assertEquals("Latin Mode", backend.inputMethodEntryCached.subMode.name)
    }

    @Test fun ordinaryAndSensitiveRimeEditorsStillRequireASchema() = runBlocking {
        createBackend()
        BindingNativeShadow.gate = 2
        for (flags in listOf(CapabilityFlags.DefaultFlags, CapabilityFlags(CapabilityFlag.Sensitive))) {
            backend.startInputSession(prepare(flags), "editor")
            assertFalse(backend.acceptsInput)
            assertNull(backend.captureInputKey())
        }
    }

    @Test fun directInputCannotBypassMaintenanceOrAStaleBinding() = runBlocking {
        createBackend()
        for (gate in listOf(1, 3, 4)) {
            BindingNativeShadow.gate = gate
            backend.startInputSession(prepare(CapabilityFlags(CapabilityFlag.Password)), "editor")
            assertFalse(backend.acceptsInput)
            assertNull(backend.captureInputKey())
        }
    }
}

@Implements(Native::class, isInAndroidSdk = false)
class BindingNativeShadow {
    companion object {
        data class Call(val phase: String, val priority: Int, val thread: Long)
        val calls = mutableListOf<Call>()
        var gate = 0
        var noLearning = false
        var failAction = false
        private var created = 0L
        private var generation = 0L
        private var revision = 0L
        private var schema = "initial"
        private var ascii = false

        fun reset() {
            calls.clear(); gate = 0; noLearning = false; failAction = false
            created = 0; generation = 0; revision = 0; schema = "initial"; ascii = false
        }

        @JvmStatic @Implementation fun __staticInitializer__() {
            // Retain the Kotlin singleton while excluding System.loadLibrary from this JVM test.
            ReflectionHelpers.setStaticField(Native::class.java, "INSTANCE",
                ReflectionHelpers.callConstructor(Native::class.java))
        }

        private fun record(phase: String) {
            calls += Call(phase, Process.getThreadPriority(Process.myTid()), Process.myTid().toLong())
        }

        private fun snapshot() = Snapshot(generation, ++revision, revision, false, gate,
            "", "", if (gate == 0) schema else "", ascii, 0, true, 0,
            emptyArray(), emptyArray(), emptyArray(), schema, -1, "", emptyArray())
    }

    @Implementation fun sessionStats() = longArrayOf(created, 0, 0)
    @Implementation fun operationTiming() = LongArray(16)
    @Implementation fun beginInput(value: Long, program: String, sensitive: Boolean): Snapshot {
        record("begin"); generation = value; noLearning = sensitive
        if (gate == 0) created++
        return snapshot()
    }
    @Implementation fun action(value: Long, previous: Long, action: Int, index: Int, name: String): Snapshot {
        check(value == generation && previous == revision)
        check(!failAction) { "Injected restore failure" }
        when (action) {
            3 -> { record("schema"); schema = name }
            8 -> { record("ascii"); ascii = index != 0 }
            else -> error("Unexpected native action $action")
        }
        return snapshot()
    }
    @Implementation fun options(): Array<String> { record("options"); return emptyArray() }
    @Implementation fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot =
        error("Direct input must not send keys to librime")
}
