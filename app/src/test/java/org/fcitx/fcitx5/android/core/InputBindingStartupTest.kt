/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
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

/** Real owner queue, binding, ticket and delivery code; only the JNI boundary is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class InputBindingStartupTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher

    @Before fun resetNative() { BindingNativeShadow.reset(); NativeTestShadow.delegate = BindingNativeShadow() }

    private fun createBackend(directBoot: Boolean = false) {
        ReflectionHelpers.setField(FcitxApplication.getInstance(), "isDirectBootMode", directBoot)
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ReflectionHelpers.setField(backend, "started", true)
        ReflectionHelpers.setField(backend, "schemas", listOf("initial" to "Initial", "preferred" to "Preferred"))
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").running()
    }

    private fun prepare(flags: CapabilityFlags = CapabilityFlags.DefaultFlags, program: String = "editor"): Long =
        backend.invalidateInputSession().also { backend.prepareInputSession(it, program, flags, true) }

    private suspend fun bind(program: String, flags: CapabilityFlags = CapabilityFlags.DefaultFlags) =
        backend.startInputSession(prepare(flags, program), program)

    private fun currentSnapshot(): Snapshot = ReflectionHelpers.getField(backend, "snapshot")

    @After fun closeBackend() {
        if (!::backend.isInitialized) return
        ReflectionHelpers.setField(backend, "started", false)
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    @Test fun nativeBindingAndStatusReadKeepInteractivePriority() = runBlocking {
        createBackend()
        BindingNativeShadow.initialSchema = "preferred"
        BindingNativeShadow.initialAscii = true
        withContext(owner) { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }

        backend.startInputSession(prepare(), "editor")

        assertEquals(listOf("begin", "options"), BindingNativeShadow.calls.map { it.phase })
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

    @Test fun switchingEngineSlotsInvalidatesNativeRevisionScopedOptions() {
        createBackend()
        ReflectionHelpers.setField(backend, "optionRows",
            listOf(listOf("old-option", "Old option", "1", "")))
        ReflectionHelpers.setField(backend, "optionsRevision", 0L)

        ReflectionHelpers.callInstanceMethod<Unit>(backend, "resetEngineSlotPresentationCaches")

        assertTrue(ReflectionHelpers.getField<List<List<String>>>(backend, "optionRows").isEmpty())
        assertEquals(Long.MIN_VALUE, ReflectionHelpers.getField<Long>(backend, "optionsRevision"))
        assertTrue(backend.statusAreaActionsCached.isEmpty())
    }

    @Test fun statusReadFailureStillRestoresTheOwnerThreadPriority() = runBlocking {
        createBackend()
        BindingNativeShadow.failOptions = true
        withContext(owner) { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val generation = prepare()

        val failure = runCatching { backend.startInputSession(generation, "editor") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(backend.acceptsInput)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND,
            withContext(owner) { Process.getThreadPriority(Process.myTid()) })
    }

    @Test fun newApplicationUsesRimeAsciiReset() = runBlocking {
        createBackend()
        bind("editor.one")
        backend.toggleIme()
        assertTrue(currentSnapshot().ascii)
        BindingNativeShadow.calls.clear()

        bind("editor.two")

        assertFalse("Rime's new-session ascii_mode reset must remain authoritative", currentSnapshot().ascii)
        assertEquals(listOf("begin", "options"), BindingNativeShadow.calls.map { it.phase })
    }

    @Test fun newApplicationDoesNotInheritTemporaryInlineAscii() = runBlocking {
        createBackend()
        bind("editor.one")
        backend.sendKey('N', 1u, 0, false, 1)
        assertTrue(currentSnapshot().ascii)
        assertEquals("N", currentSnapshot().preedit)

        bind("editor.two")

        assertFalse("An old inline composition is not a preference for the next app", currentSnapshot().ascii)
        assertTrue(currentSnapshot().preedit.isEmpty())
    }

    @Test fun newApplicationUsesRimeSelectedSchema() = runBlocking {
        createBackend()
        bind("editor.one")
        assertTrue(backend.activateSchemaActionIfCurrent(101, backend.inputSession))
        assertEquals("preferred", currentSnapshot().schema)
        BindingNativeShadow.calls.clear()

        bind("editor.two")

        assertEquals("Rime's first-schema choice must not be replaced by an old snapshot", "initial", currentSnapshot().schema)
        assertEquals(listOf("begin", "options"), BindingNativeShadow.calls.map { it.phase })
    }

    @Test fun newApplicationKeepsRimeRestoredAscii() = runBlocking {
        createBackend()
        bind("editor.one")
        assertFalse(currentSnapshot().ascii)
        BindingNativeShadow.initialAscii = true

        bind("editor.two")

        assertTrue("A mode restored by Rime must not be replaced by the previous editor's mode", currentSnapshot().ascii)
    }

    @Test fun sameApplicationKeepsNativeReusedMode() = runBlocking {
        createBackend()
        bind("editor.one")
        backend.toggleIme()

        bind("editor.one")

        assertTrue(currentSnapshot().ascii)
        assertEquals(1L, BindingNativeShadow.created)
        assertEquals(1L, BindingNativeShadow.reused)
    }

    @Test @Config(qualifiers = "zh") fun modeMenuNamesMatchTheResultingHints() = runBlocking {
        createBackend()
        bind("editor.one")
        val context = RuntimeEnvironment.getApplication()
        val english = context.getString(R.string.direct_rime_ascii_mode)
        val chinese = context.getString(R.string.direct_rime_chinese_mode)
        assertEquals("英文模式", english)
        assertEquals("中文模式", chinese)
        fun root() = backend.statusAreaActionsCached.first { it.name == "rime" }
        fun mode() = root().menu!!.first { it.name == "ascii_mode" }

        assertFalse(mode().isChecked)
        assertEquals(english, mode().shortText)
        backend.activateAction(mode().id)
        assertTrue(currentSnapshot().ascii)
        assertTrue(mode().isChecked)
        assertEquals(chinese, mode().shortText)
        assertEquals(english, root().shortText)
        assertEquals(english, backend.inputPanelCached.auxUp.toString())

        backend.activateAction(mode().id)
        assertFalse(currentSnapshot().ascii)
        assertEquals(english, mode().shortText)
        assertEquals(currentSnapshot().schemaName, root().shortText)
        assertEquals(chinese, backend.inputPanelCached.auxUp.toString())
        assertEquals(listOf("ascii-toggle", "ascii-toggle"),
            BindingNativeShadow.calls.filter { it.phase == "ascii-toggle" }.map { it.phase })
    }

    @Test fun sensitiveBoundaryUsesNewRimeState() = runBlocking {
        createBackend()
        bind("editor.one")
        backend.toggleIme()

        bind("editor.one", CapabilityFlags(CapabilityFlag.Sensitive))

        assertFalse(currentSnapshot().ascii)
        assertTrue(BindingNativeShadow.noLearning)
        assertEquals(2L, BindingNativeShadow.created)
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

internal class BindingNativeShadow : NativeTestDelegate {
    companion object {
        data class Call(val phase: String, val priority: Int, val thread: Long)
        val calls = mutableListOf<Call>()
        var gate = 0
        var noLearning = false
        var failOptions = false
        var initialSchema = "initial"
        var initialAscii = false
        var created = 0L
        var reused = 0L
        private var generation = 0L
        private var revision = 0L
        private var schema = "initial"
        private var ascii = false
        private var preedit = ""
        private var currentProgram = ""

        fun reset() {
            calls.clear(); gate = 0; noLearning = false; failOptions = false
            initialSchema = "initial"; initialAscii = false
            created = 0; reused = 0; generation = 0; revision = 0; schema = "initial"; ascii = false
            preedit = ""; currentProgram = ""
        }

        private fun record(phase: String) {
            calls += Call(phase, Process.getThreadPriority(Process.myTid()), Process.myTid().toLong())
        }

        private fun snapshot(handled: Boolean = false) = Snapshot(generation, ++revision, revision, handled, gate,
            "", preedit, if (gate == 0) schema else "", ascii, 0, true, 0,
            emptyArray(), emptyArray(), emptyArray(), schema, if (preedit.isEmpty()) -1 else preedit.length,
            preedit, emptyArray())
    }

    override fun sessionStats() = longArrayOf(created, reused, 0)
    override fun operationTiming() = LongArray(16)
    override fun beginInput(value: Long, program: String, sensitive: Boolean): Snapshot {
        record("begin"); generation = value
        if (gate == 0) {
            // The boundary supplies librime's initialized/restored state. An ordinary
            // same-app session can retain its mode; sensitive and cross-app binds cannot.
            if (created > 0 && !sensitive && !noLearning && program.isNotEmpty() && program == currentProgram) {
                reused++
            } else {
                created++; schema = initialSchema; ascii = initialAscii
            }
        }
        noLearning = sensitive; currentProgram = program; preedit = ""
        return snapshot()
    }
    override fun action(value: Long, previous: Long, action: Int, index: Int, name: String): Snapshot {
        check(value == generation && previous == revision)
        when (action) {
            3 -> { record("schema"); schema = name; ascii = false }
            6 -> { record("ascii-toggle"); ascii = !ascii }
            8 -> { record("ascii"); ascii = index != 0 }
            else -> error("Unexpected native action $action")
        }
        return snapshot(true)
    }
    override fun options(): Array<String> {
        record("options")
        check(!failOptions) { "Injected status read failure" }
        return emptyArray()
    }
    override fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot {
        check(value == generation && sym == 'N'.code && !release) { "Unexpected native key" }
        record("inline-ascii"); ascii = true; preedit = "N"
        return snapshot(true)
    }
}
