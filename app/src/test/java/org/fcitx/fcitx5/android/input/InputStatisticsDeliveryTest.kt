/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputOperationStatistics
import org.fcitx.fcitx5.android.core.InputStatisticsSource
import org.fcitx.fcitx5.android.data.stats.InputStats
import org.fcitx.fcitx5.android.input.voice.js.JsAppInputConnection
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestDelegate
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Production service delivery with only the Android connection and native-session lookup supplied. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, StatisticsInputConnectionShadow::class, FcitxDaemonTestShadow::class])
class InputStatisticsDeliveryTest {
    private class Connection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), false) {
        var accepted = true
        val requests = mutableListOf<String>()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            requests += text.toString()
            return accepted
        }
    }

    private suspend fun fixture(block: suspend (FcitxInputMethodService, InputStats, Connection) -> Unit) {
        FcitxDaemonTestShadow.install(object : FcitxDaemonTestDelegate {
            override fun getCurrentInputSession() = 1L
        })
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        // Robolectric isolates each test directory; a short basename also stays below
        // Windows SQLite's path limit for long test method names.
        val stats = InputStats(RuntimeEnvironment.getApplication(), "stats.db")
        val connection = Connection()
        try {
            withTimeout(10_000) { while (!stats.settings.value.loaded) delay(5) }
            assertNull(stats.settings.value.error)
            stats.setEnabled(true)
            ReflectionHelpers.setField(service, "statisticsStore", stats)
            ReflectionHelpers.getField<CursorTracker>(service, "selection").resetTo(0, 0)
            service.editorSession.start(EditorSessionTracker.Editor("test.editor", 1, InputType.TYPE_CLASS_TEXT, 0), false)
            StatisticsInputConnectionShadow.connection = connection
            StatisticsInputConnectionShadow.editorInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
            block(service, stats, connection)
        } finally {
            service.jsAppInputConnection = null
            service.editorSession.end()
            StatisticsInputConnectionShadow.connection = null
            StatisticsInputConnectionShadow.editorInfo = null
            ReflectionHelpers.setField(FcitxApplication.getInstance(), "isDirectBootMode", false)
            try { stats.close() } finally { FcitxDaemonTestShadow.reset() }
        }
    }

    private fun source(service: FcitxInputMethodService) = ReflectionHelpers.callInstanceMethod<InputStatisticsSource?>(service,
        "captureStatisticsSource", ClassParameter.from(Long::class.javaPrimitiveType!!, 1L))

    private fun info(stats: InputStats, command: Boolean = false, epoch: Long = stats.recordingEpoch) =
        InputOperationStatistics(epoch, "wanxiang", "万象测试", 3, command, true, 1_799_000_000_000, 1000)

    private fun commit(service: FcitxInputMethodService, statistics: InputOperationStatistics?, generation: Long = 1) {
        val event = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("中文", -1)).apply {
            inputSession = generation
            this.statistics = statistics
        }
        deliver(service, event)
    }

    private fun deliver(service: FcitxInputMethodService, event: FcitxEvent<*>) {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleFcitxEvent", ClassParameter.from(FcitxEvent::class.java, event))
    }

    @Test fun onlyAcceptedMainEditorRimeCommitsWithKnownSourceAreRecorded() = runBlocking {
        fixture { service, stats, connection ->
            assertNotNull(source(service))
            connection.accepted = false
            commit(service, info(stats))
            connection.accepted = true
            commit(service, null)
            commit(service, info(stats, command = true))
            commit(service, info(stats), generation = 2)
            assertEquals(0L, stats.snapshot().totals.characters)
            commit(service, info(stats))
            assertEquals(2L, stats.snapshot().totals.characters)
            assertEquals(5, connection.requests.size)
        }
    }

    @Test fun passwordPinNoLearningNullTypeDirectBootAndMissingConnectionsCannotCreateAStatsSource() = runBlocking {
        fixture { service, stats, _ ->
            val editors = listOf(
                EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD },
                EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD },
                EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD },
                EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING },
                EditorInfo().apply { inputType = InputType.TYPE_NULL }
            )
            for (editor in editors) {
                StatisticsInputConnectionShadow.editorInfo = editor
                ReflectionHelpers.setField(service, "capabilityFlags", CapabilityFlags.fromEditorInfo(editor).flags.toLong())
                assertNull(source(service))
                commit(service, info(stats))
            }
            ReflectionHelpers.setField(service, "capabilityFlags", CapabilityFlags.DefaultFlags.flags.toLong())
            StatisticsInputConnectionShadow.editorInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
            ReflectionHelpers.setField(FcitxApplication.getInstance(), "isDirectBootMode", true)
            assertNull(source(service))
            commit(service, info(stats))
            ReflectionHelpers.setField(FcitxApplication.getInstance(), "isDirectBootMode", false)
            StatisticsInputConnectionShadow.connection = null
            assertNull(source(service))
            commit(service, info(stats))
            assertEquals(0L, stats.snapshot().totals.characters)
        }
    }

    @Test fun localEditorsAndClipboardSearchNeverRecordTheirAcceptedText() = runBlocking {
        fixture { service, stats, original ->
            val local = Connection()
            service.jsAppInputConnection = local
            assertNull(source(service))
            commit(service, info(stats))
            service.jsAppInputConnection = JsAppInputConnection.blocked(service)
            assertNull(source(service))
            commit(service, info(stats))
            service.jsAppInputConnection = null
            assertEquals(listOf("中文"), local.requests)
            assertTrue(original.requests.isEmpty())
            assertEquals(0L, stats.snapshot().totals.characters)
        }
    }

    @Test fun queuedOldEpochMetadataCannotReturnAfterTogglingOrClearingStatistics() = runBlocking {
        fixture { service, stats, connection ->
            val old = info(stats)
            val captured = source(service)!!
            stats.setEnabled(false)
            stats.setEnabled(true)
            assertFalse(captured.stillEnabled())
            commit(service, old)
            deliver(service, FcitxEvent.StatisticsKeyEvent(old, 'a'.code).apply { inputSession = 1 })
            assertEquals(0L, stats.snapshot().totals.characters)
            assertEquals(0L, stats.snapshot().totals.keys)
            val beforeClear = info(stats)
            stats.clear()
            commit(service, beforeClear)
            assertEquals(0L, stats.snapshot().totals.characters)
            commit(service, info(stats))
            assertEquals(2L, stats.snapshot().totals.characters)
            assertEquals(3, connection.requests.size)
        }
    }

    @Test fun mergedKeyMetricsKeepCountsBeforeTheAcceptedCommitAndReportOnlyCurrentEpochDrops() = runBlocking {
        fixture { service, stats, _ ->
            val sample = info(stats)
            val keys = listOf('n'.code, 'i'.code, ' '.code).map { sym ->
                FcitxEvent.StatisticsKeyEvent(sample, sym).apply { inputSession = 1 }
            }
            deliver(service, FcitxEvent.StatisticsKeysEvent(keys, stats.recordingEpoch, 7).apply { inputSession = 1 })
            commit(service, sample)
            assertEquals(3L, stats.snapshot().totals.keys)
            assertEquals(2L, stats.snapshot().totals.characters)
            assertEquals(7L, stats.settings.value.rejectedEvents)
            val oldEpoch = stats.recordingEpoch
            stats.clear()
            deliver(service, FcitxEvent.StatisticsKeysEvent(keys, oldEpoch, 8).apply { inputSession = 1 })
            assertEquals(0L, stats.snapshot().totals.keys)
            assertEquals(7L, stats.settings.value.rejectedEvents)
        }
    }

    @Test fun acceptedCommitThatLeavesCompositionKeepsItsPauseInTheSpeedSession() = runBlocking {
        fixture { service, stats, connection ->
            fun metadata(time: Long, composing: Boolean) = info(stats).copy(monotonicTimeMs = time, composing = composing)
            fun key(time: Long) = deliver(service,
                FcitxEvent.StatisticsKeyEvent(metadata(time, true), 'n'.code).apply { inputSession = 1 })
            key(1000)
            // An automatic commit has already started the following code in Rime.
            commit(service, metadata(2000, true))
            key(9000)
            commit(service, metadata(10000, false))
            val result = stats.snapshot()
            assertEquals(4L, result.totals.characters)
            assertEquals(9000L, result.totals.activeMillis)
            assertEquals(4 * 60000.0 / 9000, result.totals.averageCpm, 0.001)
            assertEquals(listOf("中文", "中文"), connection.requests)
        }
    }
}

@Implements(InputMethodService::class)
class StatisticsInputConnectionShadow {
    companion object {
        var connection: InputConnection? = null
        var editorInfo: EditorInfo? = null
    }
    @Implementation fun getCurrentInputConnection(): InputConnection? = connection
    @Implementation fun getCurrentInputEditorInfo(): EditorInfo? = editorInfo
}
