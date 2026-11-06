/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.fcitx.directrime.RawInputState
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class ReadOnlyPresentationTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher
    private var generation = 0L

    @Before fun prepare() = runBlocking {
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ReflectionHelpers.setField(backend, "started", true)
        ReflectionHelpers.setField(backend, "schemas", listOf("fixture" to "Fixture"))
        generation = backend.invalidateInputSession()
        backend.prepareInputSession(generation, "editor", CapabilityFlags.DefaultFlags, true)
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").apply { running(); resolve(generation, true) }
        PresentationNativeShadow.reset(generation)
        NativeTestShadow.delegate = PresentationNativeShadow()
        backend.setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "No"))))
        backend.sendKey('n', 0u, 0, false, 0)
        backend.setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "ComposingText"))))
        PresentationNativeShadow.actions.clear()
    }

    @After fun close() {
        ReflectionHelpers.setField(backend, "started", false)
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        backend.lifeCycleScope.cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    @Test fun presentationRefreshNeverMovesCaretOrAddsRawReadsToTheHotPath() = runBlocking {
        val before = backend.candidateActionContext
        backend.setCandidatePagingMode(1)
        assertEquals(before, backend.candidateActionContext)
        backend.setCandidatePagingMode(0)
        assertEquals(listOf(13, 13), PresentationNativeShadow.actions)
        assertEquals(0, PresentationNativeShadow.rawReads)
        assertTrue(PresentationNativeShadow.caretWrites.isEmpty())
        assertEquals(3, PresentationNativeShadow.rawCaret)
    }

    @Test fun cursorUsesRawCoordinatesAndRechecksQueuedEditorAndPreedit() = runBlocking {
        assertTrue(backend.moveCursorIfCurrent(3, generation, "ni hao"))
        assertEquals(listOf(2), PresentationNativeShadow.caretWrites)
        assertTrue(backend.moveCursorIfCurrent(3, generation, "ni hao"))
        assertEquals("Same raw position must not recompose", listOf(2), PresentationNativeShadow.caretWrites)
        val reads = PresentationNativeShadow.rawReads
        assertFalse(backend.moveCursorIfCurrent(4, generation, "old preedit"))
        backend.invalidateInputSession()
        assertFalse(backend.moveCursorIfCurrent(4, generation, "ni hao"))
        assertEquals(reads, PresentationNativeShadow.rawReads)
    }

    @Test fun ambiguousTransformRestoresTheClientCursorWithoutChangingRawInput() = runBlocking {
        val events = Channel<FormattedText>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            backend.eventFlow.collect { if (it is FcitxEvent.ClientPreeditEvent && it.data.toString() == "你好") events.send(it.data) }
        }
        try {
            PresentationNativeShadow.preedit = "你好"
            PresentationNativeShadow.cursorBytes = 6
            PresentationNativeShadow.rawCaret = 5
            backend.sendKey('x', 0u, 0, false, 0)
            withTimeout(5_000) { events.receive() }
            assertFalse(backend.moveCursorIfCurrent(1, generation, "你好"))
            val restored = withTimeout(5_000) { events.receive() }
            assertEquals(2, restored.cursor)
            assertEquals(5, PresentationNativeShadow.rawCaret)
            assertTrue(PresentationNativeShadow.caretWrites.isEmpty())
        } finally { collector.cancelAndJoin(); events.cancel() }
    }

    @Test fun commitPreviewIsNotMistakenForEditableRawInput() = runBlocking {
        backend.setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "CommitPreview"))))
        val reads = PresentationNativeShadow.rawReads
        assertFalse(backend.moveCursorIfCurrent(1, generation, backend.clientPreeditCached.toString()))
        assertEquals(reads, PresentationNativeShadow.rawReads)
        assertTrue(PresentationNativeShadow.caretWrites.isEmpty())
    }
}

internal class PresentationNativeShadow : NativeTestDelegate {
    companion object {
        var generation = 0L
        var revision = 0L
        var preedit = "ni hao"
        var cursorBytes = 4
        var rawCaret = 3
        var rawReads = 0
        val actions = mutableListOf<Int>()
        val caretWrites = mutableListOf<Int>()
        fun reset(value: Long) {
            generation = value; revision = 0; preedit = "ni hao"; cursorBytes = 4
            rawCaret = 3; rawReads = 0; actions.clear(); caretWrites.clear()
        }
        private fun snapshot(handled: Boolean) = Snapshot(generation, ++revision, 0, handled, 0, "", preedit,
            "fixture", false, 0, true, cursorBytes, arrayOf("你好"), emptyArray(), emptyArray(),
            "Fixture", 0, "你好", arrayOf("1"))
    }
    override fun options(): Array<String> = emptyArray()
    override fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot = snapshot(true)
    override fun rawInput(value: Long, previous: Long): RawInputState? {
        check(value == generation && previous == revision)
        rawReads++
        return RawInputState("nihao", rawCaret)
    }
    override fun action(value: Long, previous: Long, action: Int, index: Int, name: String): Snapshot {
        check(value == generation && previous == revision)
        actions += action
        when (action) {
            9 -> { caretWrites += index; rawCaret = index; cursorBytes = if (index <= 2) index else index + 1 }
            13 -> Unit
            else -> error("Unexpected action $action")
        }
        return snapshot(action != 13)
    }
}
