/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class InputStatsTest {
    private val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("2026-11-07 12:00:00")!!.time
    private suspend fun ready(stats: InputStats) { withTimeout(10_000) { while (!stats.settings.value.loaded) delay(5) }; assertNull(stats.settings.value.error) }
    private suspend fun useStats(block: suspend (InputStats) -> Unit) {
        // Robolectric already gives each method a private directory; keep the basename short
        // enough for native SQLite on Windows even when the method name is descriptive.
        val stats = InputStats(RuntimeEnvironment.getApplication(), "stats.db")
        try { ready(stats); block(stats) } finally { stats.close() }
    }

    @Test fun explicitOptInAndSensitiveGateProtectBothCountersAndStoredVocabulary() = runBlocking {
        useStats { stats ->
            assertFalse(stats.isEnabled)
            assertFalse(stats.recordKey("wx", "万象", "a", wallTimeMs = wall))
            stats.setEnabled(true)
            assertFalse(stats.recordCommit("wx", "万象", "秘密", 4, sensitive = true, wallTimeMs = wall))
            assertFalse(stats.recordHeat("wx", "万象", "secret", sensitive = true, wallTimeMs = wall))
            assertTrue(stats.recordKey("wx", "万象", "a", wallTimeMs = wall, monotonicTimeMs = 1000))
            assertTrue(stats.recordCommit("wx", "万象", "中文", 5, wallTimeMs = wall, monotonicTimeMs = 3000))
            var result = stats.snapshot()
            assertEquals(2L, result.totals.characters)
            assertTrue(result.words.isEmpty())
            assertTrue(result.keyHeatmap.isEmpty())
            stats.setWordFrequencyEnabled(true)
            assertTrue(stats.recordCommit("wx", "万象", "输入", 5, wallTimeMs = wall, monotonicTimeMs = 5000))
            assertTrue(stats.recordHeat("wx", "万象", "a", wallTimeMs = wall))
            assertFalse(stats.recordCommit("wx", "万象", "abc中文", 5, wallTimeMs = wall))
            result = stats.snapshot()
            assertEquals(4L, result.totals.characters)
            assertEquals(mapOf("a" to 1L), result.keyHeatmap)
            assertEquals(mapOf("输" to 1L, "入" to 1L, "输入" to 1L), result.words)
            assertFalse(stats.exportJson().contains("秘密"))
        }
    }

    @Test fun disabledClearsPersistAndEarlierQueuedWritesCannotRestoreRemovedCounters() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "stats.db"
        var stats = InputStats(context, name)
        try {
            ready(stats); stats.setEnabled(true)
            repeat(30) { stats.recordKey("wx", "万象", "a", wallTimeMs = wall, monotonicTimeMs = it * 100L) }
            stats.recordCommit("wx", "万象", "中文", 4, wallTimeMs = wall, monotonicTimeMs = 4000)
            stats.setEnabled(false)
            assertFalse(stats.recordKey("wx", "万象", "b", wallTimeMs = wall))
            stats.clear()
            assertEquals(0L, stats.snapshot().totals.characters)
            stats.close()
            stats = InputStats(context, name); ready(stats)
            assertFalse(stats.isEnabled)
            val result = stats.snapshot()
            assertEquals(0L, result.totals.keys)
            assertEquals(0L, result.totals.characters)
            assertEquals(2L, result.careerCharacters)
        } finally { stats.close() }
    }

    @Test fun selectedSchemeAndDateClearDoesNotRemoveOtherDatesOrSchemes() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            stats.recordCommit("wx", "万象", "第一", 3, wallTimeMs = wall)
            stats.recordCommit("wx", "万象", "第二", 3, wallTimeMs = wall + 86_400_000)
            stats.recordCommit("other", "其他", "保留", 3, wallTimeMs = wall)
            val day = StatsRange.today(wall)
            assertEquals(2L, stats.snapshot(day, "wx").totals.characters)
            stats.clear(day, "wx")
            assertEquals(0L, stats.snapshot(day, "wx").totals.characters)
            assertEquals(2L, stats.snapshot(StatsRange.all(), "wx").totals.characters)
            assertEquals(2L, stats.snapshot(day, "other").totals.characters)
            assertEquals(6L, stats.snapshot().careerCharacters)
        }
    }

    @Test fun activeCompositionSurvivesPauseAndPeakClearDoesNotReappearOnRead() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            stats.recordKey("wx", "万象", "a", composing = true, wallTimeMs = wall, monotonicTimeMs = 1000)
            stats.recordKey("wx", "万象", "b", composing = true, wallTimeMs = wall, monotonicTimeMs = 8000)
            stats.recordCommit("wx", "万象", "中文", 3, wallTimeMs = wall, monotonicTimeMs = 11000)
            val result = stats.snapshot()
            assertEquals(10000L, result.totals.activeMillis)
            assertEquals(12.0, result.totals.peakCpm, 0.001)
            assertEquals(12.0, result.totals.averageCpm, 0.001)
            stats.setEnabled(false)
            stats.clearPeaks()
            assertEquals(0.0, stats.snapshot().totals.peakCpm, 0.001)
            assertEquals(10000L, stats.snapshot().totals.activeMillis)
        }
    }

    @Test fun commitThatStartsAnotherCompositionKeepsItsSessionAcrossALongPause() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            assertTrue(stats.recordKey("wx", "万象", "a", composing = true, wallTimeMs = wall, monotonicTimeMs = 1000))
            assertTrue(stats.recordCommit("wx", "万象", "中", 2, wallTimeMs = wall, monotonicTimeMs = 3000, composing = true))
            assertTrue(stats.recordKey("wx", "万象", "b", composing = true, wallTimeMs = wall, monotonicTimeMs = 10000))
            assertTrue(stats.recordCommit("wx", "万象", "文", 2, wallTimeMs = wall, monotonicTimeMs = 11000))
            val result = stats.snapshot()
            assertEquals(2L, result.totals.commits)
            assertEquals(10000L, result.totals.activeMillis)
            assertEquals(12.0, result.totals.averageCpm, 0.001)
            assertEquals(12.0, result.totals.peakCpm, 0.001)
        }
    }

    @Test fun importReplacesInsteadOfAddingAndInvalidInputKeepsTheExistingDatabase() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true); stats.setWordFrequencyEnabled(true)
            stats.recordCommit("wx", "万象", "中文", 3, wallTimeMs = wall)
            stats.setLayout("wx", StatsLayout(rows = listOf(listOf("a", "b"))))
            val exported = stats.exportJson()
            stats.recordCommit("wx", "万象", "增加", 3, wallTimeMs = wall)
            assertEquals(4L, stats.snapshot().totals.characters)
            stats.setEnabled(false)
            stats.importJson(exported)
            assertFalse(stats.isEnabled)
            assertEquals(2L, stats.snapshot().totals.characters)
            assertEquals(listOf(listOf("a", "b")), stats.snapshot().layout!!.rows)
            assertTrue(File(RuntimeEnvironment.getApplication().noBackupFilesDir, "input-stats-before-import.json").isFile)
            assertTrue(runCatching { stats.importJson("{\"version\":999}") }.isFailure)
            assertEquals(2L, stats.snapshot().totals.characters)
        }
    }

    @Test fun oldInputTicketsCannotWriteAfterDisableEnableOrClear() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            val beforeDisable = stats.recordingEpoch
            assertTrue(stats.recordKey("wx", "万象", "a", wallTimeMs = wall, expectedEpoch = beforeDisable))
            stats.setEnabled(false)
            stats.setEnabled(true)
            assertTrue(stats.recordingEpoch > beforeDisable)
            assertFalse(stats.recordCommit("wx", "万象", "旧词", 3, wallTimeMs = wall, expectedEpoch = beforeDisable))
            assertFalse(stats.recordHeat("wx", "万象", "a", wallTimeMs = wall, expectedEpoch = beforeDisable))
            assertEquals(1L, stats.snapshot().totals.keys)
            val beforeClear = stats.recordingEpoch
            stats.clear()
            assertFalse(stats.recordCommit("wx", "万象", "旧词", 3, wallTimeMs = wall, expectedEpoch = beforeClear))
            assertTrue(stats.recordCommit("wx", "万象", "新词", 3, wallTimeMs = wall))
            assertEquals(2L, stats.snapshot().totals.characters)
        }
    }

    @Test fun commitsAcrossLocalMidnightDoNotChargeOldCompositionTimeToTheNewDay() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            val firstDay = StatsRange.today(wall)
            stats.recordKey("wx", "万象", "a", composing = true, wallTimeMs = wall, monotonicTimeMs = 1000)
            stats.recordCommit("wx", "万象", "跨日", 3, wallTimeMs = wall + 86_400_000, monotonicTimeMs = 86_401_000)
            val old = stats.snapshot(firstDay)
            val next = stats.snapshot(StatsRange.today(wall + 86_400_000))
            assertEquals(0L, old.totals.characters)
            assertEquals(2L, next.totals.characters)
            assertEquals(0L, next.totals.activeMillis)
        }
    }

    @Test @Config(sdk = [35]) fun directBootStorageNeverOpensAStatisticsDatabase() = runBlocking {
        val context = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
        val name = "locked.db"
        val stats = InputStats(context, name)
        try {
            ready(stats)
            assertFalse(stats.isEnabled)
            assertTrue(runCatching { stats.setEnabled(true) }.isFailure)
            assertFalse(context.getDatabasePath(name).exists())
            assertFalse(File(context.noBackupFilesDir, "input-stats/$name").exists())
        } finally { stats.close() }
    }

    @Test fun privacyResetRemovesLifetimeLayoutVocabularyAndImportBackupAndRejectsLateLayout() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true); stats.setWordFrequencyEnabled(true)
            stats.recordCommit("wx", "万象", "中文", 3, wallTimeMs = wall)
            stats.setLayout("wx", StatsLayout(rows = listOf(listOf("a"))))
            stats.importJson(stats.exportJson())
            val context = RuntimeEnvironment.getApplication()
            val interruptedBackup = File(context.noBackupFilesDir, "input-stats-before-import.json.tmp")
            interruptedBackup.writeText(stats.exportJson())
            val unrelatedTemporaryFile = File(context.noBackupFilesDir, "unrelated.tmp").apply { writeText("keep") }
            val sharedImage = File(context.cacheDir, "shared/input-statistics/history.png").apply {
                parentFile!!.mkdirs(); writeText("private vocabulary")
            }
            val unrelatedCachedImage = File(context.cacheDir, "shared/other-sharing/keep.png").apply {
                parentFile!!.mkdirs(); writeText("keep")
            }
            val oldEpoch = stats.recordingEpoch
            stats.clearAllData()
            stats.setLayout("wx", StatsLayout(rows = listOf(listOf("late"))), expectedEpoch = oldEpoch)
            val result = stats.snapshot()
            assertFalse(stats.isEnabled)
            assertFalse(stats.settings.value.wordFrequencyEnabled)
            assertEquals(0L, result.careerCharacters)
            assertTrue(result.schemas.isEmpty())
            assertTrue(result.words.isEmpty())
            assertNull(result.layout)
            assertFalse(File(context.noBackupFilesDir, "input-stats-before-import.json").exists())
            assertFalse(interruptedBackup.exists())
            assertEquals("keep", unrelatedTemporaryFile.readText())
            assertFalse(sharedImage.parentFile!!.exists())
            assertEquals("keep", unrelatedCachedImage.readText())
            assertEquals(stats.recordingEpoch, result.recordingEpoch)
            assertTrue(result.recordingEpoch > oldEpoch)
        }
    }

    @Test fun snapshotBeforeAQueuedPrivacyResetKeepsTheInvalidatedEpoch() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            val oldEpoch = stats.recordingEpoch
            val enqueueLock = InputStats::class.java.getDeclaredField("enqueueLock").apply { isAccessible = true }.get(stats)
            lateinit var oldSnapshot: Deferred<StatsSnapshot>
            lateinit var reset: Deferred<Unit>
            synchronized(enqueueLock) {
                assertTrue(stats.recordCommit("wx", "万象", "中文", 3, wallTimeMs = wall))
                oldSnapshot = async(start = CoroutineStart.UNDISPATCHED) { stats.snapshot() }
                reset = async(start = CoroutineStart.UNDISPATCHED) { stats.clearAllData() }
            }
            val previous = oldSnapshot.await()
            reset.await()
            assertEquals(2L, previous.totals.characters)
            assertEquals(oldEpoch, previous.recordingEpoch)
            assertTrue(previous.recordingEpoch < stats.recordingEpoch)
            val current = stats.snapshot()
            assertEquals(0L, current.totals.characters)
            assertEquals(stats.recordingEpoch, current.recordingEpoch)
        }
    }

    @Test fun reEnablingAfterAnActorFailureRestoresTheSavedWordFrequencyPreference() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true); stats.setWordFrequencyEnabled(true)
            stats.importJson(StatsArchive.json.encodeToString(StatsArchive(
                schemas = List(StatsLimits.MAX_SCHEMAS) { StatsSchema("schema$it", "Schema $it") },
                buckets = emptyList())))
            assertTrue(stats.recordKey("overflow", "Overflow", "a", wallTimeMs = wall))
            // A snapshot is an ordered barrier after the built-in schema limit fails.
            stats.snapshot()
            assertFalse(stats.isEnabled)
            assertNotNull(stats.settings.value.error)
            assertTrue(stats.settings.value.wordFrequencyEnabled)
            stats.setEnabled(true)
            assertNull(stats.settings.value.error)
            assertTrue(stats.recordCommit("schema0", "Schema 0", "恢复", 3, wallTimeMs = wall))
            assertEquals(1L, stats.snapshot().words["恢复"])
        }
    }

    @Test fun queuedSessionBoundariesRemainSeparateWhenInputIsQueuedBetweenThem() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            val enqueueLock = InputStats::class.java.getDeclaredField("enqueueLock").apply { isAccessible = true }.get(stats)
            // Holding the producer lock keeps the actor at its first key's accounting step,
            // making the lifecycle sequence deterministic without relying on slow storage.
            synchronized(enqueueLock) {
                assertTrue(stats.recordKey("wx", "万象", "a", composing = true, wallTimeMs = wall, monotonicTimeMs = 0))
                stats.endSession()
                assertTrue(stats.recordKey("wx", "万象", "b", composing = true, wallTimeMs = wall, monotonicTimeMs = 1000))
                stats.endSession()
                assertTrue(stats.recordKey("wx", "万象", "c", composing = true, wallTimeMs = wall, monotonicTimeMs = 11000))
                assertTrue(stats.recordCommit("wx", "万象", "中文", 3, wallTimeMs = wall, monotonicTimeMs = 13000))
            }
            val result = stats.snapshot()
            assertEquals(3L, result.totals.keys)
            assertEquals(2000L, result.totals.activeMillis)
            assertEquals(60.0, result.totals.averageCpm, 0.001)
            assertEquals(0.0, result.totals.peakCpm, 0.001)
        }
    }

    @Test fun incompletePeakWindowContinuesAfterDatabaseReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "stats.db"
        var stats = InputStats(context, name)
        try {
            ready(stats); stats.setEnabled(true)
            stats.recordKey("wx", "万象", "a", composing = true, wallTimeMs = wall, monotonicTimeMs = 1000)
            stats.recordCommit("wx", "万象", "中文", 3, wallTimeMs = wall, monotonicTimeMs = 6000)
            assertEquals(0.0, stats.snapshot().totals.peakCpm, 0.001)
            stats.close(); stats = InputStats(context, name); ready(stats)
            stats.recordKey("wx", "万象", "a", composing = true, wallTimeMs = wall, monotonicTimeMs = 8000)
            stats.recordCommit("wx", "万象", "输入", 3, wallTimeMs = wall, monotonicTimeMs = 13000)
            assertEquals(24.0, stats.snapshot().totals.peakCpm, 0.001)
            assertFalse(context.getDatabasePath(name).exists())
            assertTrue(File(context.noBackupFilesDir, "input-stats/$name").isFile)
        } finally { stats.close() }
    }

    @Test fun orderedKeyBatchesKeepDeletionCountsAndTheWholeSpeedSegment() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            val entries = mutableListOf(
                StatsKeySample("wx", "万象", "a", false, true, wall, 1000),
                StatsKeySample("wx", "万象", "Backspace", true, true, wall, 6000))
            assertTrue(stats.recordKeys(entries, stats.recordingEpoch))
            entries.clear()
            assertTrue(stats.recordCommit("wx", "万象", "中文", 3, wallTimeMs = wall, monotonicTimeMs = 11000))
            val result = stats.snapshot()
            assertEquals(2L, result.totals.keys)
            assertEquals(1L, result.totals.deletions)
            assertEquals(10000L, result.totals.activeMillis)
            assertEquals(0.2, result.totals.peakKps, 0.001)
        }
    }

    @Test fun batchLimitsAndDroppedSidecarsReportCountsWithoutAddingKeys() = runBlocking {
        useStats { stats ->
            stats.setEnabled(true)
            val entry = StatsKeySample("wx", "万象", "a", false, false, wall, 1000)
            assertFalse(stats.recordKeys(List(StatsLimits.MAX_PENDING + 1) { entry }))
            assertEquals(1025L, stats.settings.value.rejectedEvents)
            assertTrue(stats.recordDropped(3))
            assertEquals(1028L, stats.settings.value.rejectedEvents)
            assertFalse(stats.recordKeys(listOf(entry), sensitive = true))
            assertTrue(stats.snapshot().schemas.isEmpty())
            val oldEpoch = stats.recordingEpoch
            stats.setEnabled(false); stats.setEnabled(true)
            assertFalse(stats.recordDropped(10, oldEpoch))
            assertFalse(stats.recordKeys(listOf(entry), oldEpoch))
            assertEquals(1028L, stats.settings.value.rejectedEvents)
            stats.clearAllData()
            assertEquals(0L, stats.settings.value.rejectedEvents)
        }
    }
}
