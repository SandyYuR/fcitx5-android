/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import org.fcitx.directrime.RawInputState
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.stats.InputStats
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
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class InputStatisticsBackendTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var native: StatisticsNative
    private lateinit var owner: ExecutorCoroutineDispatcher
    private var generation = 0L

    @Before fun prepare() {
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ReflectionHelpers.setField(backend, "started", true)
        ReflectionHelpers.setField(backend, "schemas", listOf("wanxiang" to "万象测试"))
        generation = backend.invalidateInputSession()
        backend.prepareInputSession(generation, "editor", CapabilityFlags.DefaultFlags, true)
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").apply { running(); resolve(generation, true) }
        native = StatisticsNative(generation)
        NativeTestShadow.delegate = native
        ReflectionHelpers.setField(backend, "snapshot", native.snapshot())
    }

    @After fun close() {
        ReflectionHelpers.setField(backend, "started", false)
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        backend.lifeCycleScope.cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    @Test fun enabledRealDownCarriesPreOperationCodeAndExactSchemaBeforeCommit() = runBlocking {
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) {
            backend.eventFlow.filter { it is FcitxEvent.StatisticsKeyEvent || it is FcitxEvent.CommitStringEvent }.take(2).toList()
        } }
        withContext(InputStatisticsSource(generation, 7) { true }) { backend.sendKey(' ', 0u, 0, false, 0) }
        val events = received.await()
        assertTrue(events[0] is FcitxEvent.StatisticsKeyEvent)
        val commit = events[1] as FcitxEvent.CommitStringEvent
        assertEquals("你好", commit.data.text)
        assertEquals(3, commit.statistics!!.codeLength)
        assertEquals("wanxiang", commit.statistics!!.schemaId)
        assertEquals("万象测试", commit.statistics!!.schemaName)
        assertEquals(7L, commit.statistics!!.epoch)
        assertFalse(commit.statistics!!.command)
        assertEquals(1, native.statisticsReads)
        assertEquals("No separate raw-input JNI call", 0, native.rawReads)
    }

    @Test fun noSourceDisabledSourceAndStaleSourceNeverReadRawInput() = runBlocking {
        backend.sendKey('a', 0u, 0, false, 0)
        withContext(InputStatisticsSource(generation, 2) { false }) { backend.sendKey('b', 0u, 0, false, 0) }
        withContext(InputStatisticsSource(generation - 1, 2) { true }) { backend.sendKey('c', 0u, 0, false, 0) }
        withContext(InputStatisticsSource(generation, 2) { false }) { backend.sendKey('d', 0u, 0, true, 0) }
        assertEquals(4, native.keys)
        assertEquals(0, native.rawReads)
        assertEquals(0, native.statisticsReads)
    }

    @Test fun releaseCommitCarriesStatisticsWithoutAddingAnotherKeyCount() = runBlocking {
        native.commitOnRelease = true
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) {
            backend.eventFlow.filter { it is FcitxEvent.StatisticsKeyEvent || it is FcitxEvent.CommitStringEvent }.first()
        } }
        withContext(InputStatisticsSource(generation, 8) { true }) { backend.sendKey(' ', 0u, 0, true, 0) }
        val event = received.await() as FcitxEvent.CommitStringEvent
        assertEquals("你好", event.data.text)
        assertEquals(3, event.statistics!!.codeLength)
        assertEquals(8L, event.statistics!!.epoch)
        assertEquals(1, native.statisticsReads)
        assertEquals(0, native.rawReads)
    }

    @Test fun failedRawReadSkipsStatisticsWithoutDroppingTheCommit() = runBlocking {
        native.failRead = true
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) {
            backend.eventFlow.filter { it is FcitxEvent.CommitStringEvent }.first() as FcitxEvent.CommitStringEvent
        } }
        withContext(InputStatisticsSource(generation, 2) { true }) { backend.sendKey(' ', 0u, 0, false, 0) }
        assertEquals("你好", received.await().data.text)
        assertNull(received.await().statistics)
        assertEquals(1, native.keys)
    }

    @Test fun candidateSelectionCapturesCommandMarkerAndDoesNotInventAKeyPress() = runBlocking {
        native.raw = "/time"
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) {
            backend.eventFlow.filter { it is FcitxEvent.StatisticsKeyEvent || it is FcitxEvent.CommitStringEvent }.first()
        } }
        withContext(InputStatisticsSource(generation, 4) { true }) { assertTrue(backend.selectGlobal(0)) }
        val event = received.await() as FcitxEvent.CommitStringEvent
        assertTrue(event.statistics!!.command)
        assertEquals(6, event.statistics!!.codeLength)
        assertEquals(0, native.keys)
        assertEquals(0, native.rawReads)
    }

    @Test fun firstKeyCompositionSurvivesPauseThroughProductionBackendEvents() = runBlocking {
        val stats = InputStats(RuntimeEnvironment.getApplication(), "core-stats.db")
        try {
            withTimeout(10_000) { while (!stats.settings.value.loaded) delay(5) }
            assertNull(stats.settings.value.error)
            stats.setEnabled(true)
            native.raw = ""
            val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) {
                backend.eventFlow.transform<FcitxEvent<*>, FcitxEvent<*>> { event ->
                    when (event) {
                        is FcitxEvent.StatisticsKeyEvent -> emit(event)
                        is FcitxEvent.StatisticsKeysEvent -> event.keys.forEach { emit(it) }
                        is FcitxEvent.CommitStringEvent -> emit(event)
                        else -> Unit
                    }
                }.take(4).toList()
            } }
            withContext(InputStatisticsSource(generation, stats.recordingEpoch) { stats.isEnabled }) {
                backend.sendKey('n', 0u, 0, false, 0)
                backend.sendKey('i', 0u, 0, false, 0)
                backend.sendKey(' ', 0u, 0, false, 0)
            }
            val events = received.await()
            val keys = events.filterIsInstance<FcitxEvent.StatisticsKeyEvent>()
            assertEquals(listOf(true, true, false), keys.map { it.statistics.composing })
            assertEquals(listOf(1, 2, 3), keys.map { it.statistics.codeLength })
            // Replay backend metadata at deterministic operation times: the seven-second
            // gap after the first key is part of its still-unfinished composition.
            val times = listOf(1000L, 8000L, 11000L)
            keys.forEachIndexed { index, event ->
                val data = event.statistics
                assertTrue(stats.recordKey(data.schemaId, data.schemaName, "rime", composing = data.composing,
                    wallTimeMs = data.wallTimeMs, monotonicTimeMs = times[index], expectedEpoch = data.epoch))
            }
            val commit = events.filterIsInstance<FcitxEvent.CommitStringEvent>().single()
            val data = commit.statistics!!
            assertTrue(stats.recordCommit(data.schemaId, data.schemaName, commit.data.text, data.codeLength,
                wallTimeMs = data.wallTimeMs, monotonicTimeMs = times.last(), expectedEpoch = data.epoch))
            val result = stats.snapshot()
            assertEquals(10000L, result.totals.activeMillis)
            assertEquals(12.0, result.totals.averageCpm, 0.001)
            assertEquals(3, native.statisticsReads)
            assertEquals("No extra raw-input JNI calls", 0, native.rawReads)
        } finally { stats.close() }
    }

    @Test fun deletingTheFinalCodeReportsAnEndedComposition() = runBlocking {
        native.raw = "n"
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) {
            backend.eventFlow.filter { it is FcitxEvent.StatisticsKeyEvent || it is FcitxEvent.StatisticsKeysEvent }.first()
        } }
        withContext(InputStatisticsSource(generation, 3) { true }) {
            backend.sendKey(FcitxKeyMapping.FcitxKey_BackSpace, 0u, 0, false, 0)
        }
        val event = received.await().let {
            when (it) {
                is FcitxEvent.StatisticsKeyEvent -> it
                is FcitxEvent.StatisticsKeysEvent -> it.keys.single()
                else -> error("Expected key statistics")
            }
        }
        assertFalse(event.statistics.composing)
        assertEquals(2, event.statistics.codeLength)
        assertEquals("", native.raw)
        assertEquals(1, native.statisticsReads)
        assertEquals(0, native.rawReads)
    }

    @Test fun automaticCommitKeepsTheFollowingCompositionInItsMetadata() = runBlocking {
        native.autoCommitKey = 'h'.code
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) {
            backend.eventFlow.filter { it is FcitxEvent.StatisticsKeyEvent || it is FcitxEvent.CommitStringEvent }.take(2).toList()
        } }
        withContext(InputStatisticsSource(generation, 4) { true }) { backend.sendKey('h', 0u, 0, false, 0) }
        val events = received.await()
        val key = events[0] as FcitxEvent.StatisticsKeyEvent
        val commit = events[1] as FcitxEvent.CommitStringEvent
        assertEquals("你好", commit.data.text)
        assertEquals("h", native.raw)
        assertTrue(key.statistics.composing)
        assertTrue(commit.statistics!!.composing)
        assertEquals(3, commit.statistics!!.codeLength)
        assertEquals(1, native.statisticsReads)
        assertEquals(0, native.rawReads)
    }

    @Test fun sensitiveAndDirectBindingsNeverReadRawForStatistics() = runBlocking {
        for (flag in listOf(CapabilityFlag.Password, CapabilityFlag.Sensitive, CapabilityFlag.Disable)) {
            ReflectionHelpers.setField(backend, "flags", CapabilityFlags(flag).flags.toLong())
            withContext(InputStatisticsSource(generation, 4) { true }) { backend.sendKey('a', 0u, 0, false, 0) }
        }
        assertEquals(0, native.rawReads)
        assertEquals(0, native.statisticsReads)
    }
}

private class StatisticsNative(private val generation: Long) : NativeTestDelegate {
    var raw = "ni"
    var revision = 1L
    var statisticsReads = 0
    var rawReads = 0
    var keys = 0
    var failRead = false
    var commitOnRelease = false
    var autoCommitKey: Int? = null
    fun snapshot(commit: String = "", statistics: Long = -1) = Snapshot(generation, revision, 0, true, 0, commit, raw,
        "wanxiang", false, 0, true, raw.length, arrayOf("你好"), emptyArray(), emptyArray(),
        "万象测试", 0, "你好", arrayOf("1"), statistics)
    override fun options() = emptyArray<String>()
    override fun rawInput(value: Long, previous: Long): RawInputState {
        assertEquals(generation, value); assertEquals(revision, previous)
        rawReads++
        check(!failRead) { "fixture read failure" }
        return RawInputState(raw, raw.length)
    }
    private fun inputStatistics(requested: Boolean): Long {
        if (!requested) return -1
        statisticsReads++
        return if (failRead) -1 else (raw.length.toLong() shl 1) or if (raw.startsWith('/')) 1L else 0L
    }
    override fun key(value: Long, sym: Int, mask: Int, release: Boolean, statistics: Boolean): Snapshot {
        val captured = inputStatistics(statistics)
        keys++; revision++
        val startsNextComposition = !release && sym == autoCommitKey
        val commit = if (startsNextComposition || (!release || commitOnRelease) && sym == ' '.code) "你好" else ""
        if (startsNextComposition) raw = sym.toChar().toString()
        else if (commit.isNotEmpty()) raw = ""
        else if (!release) {
            if (sym == FcitxKeyMapping.FcitxKey_BackSpace) raw = raw.dropLast(1)
            else if (sym in 'a'.code..'z'.code) raw += sym.toChar()
        }
        return snapshot(commit, captured)
    }
    override fun action(value: Long, previous: Long, action: Int, index: Int, name: String, statistics: Boolean): Snapshot {
        val captured = inputStatistics(statistics)
        revision++
        val commit = if (action == 1 || action == 11) "你好" else ""
        if (commit.isNotEmpty()) raw = ""
        return snapshot(commit, captured)
    }
}
