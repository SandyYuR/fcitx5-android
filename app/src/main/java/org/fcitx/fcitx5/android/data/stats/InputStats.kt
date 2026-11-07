/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File

/** Optional local statistics. Input callers only enqueue bounded values; one IO worker owns storage. */
class InputStats internal constructor(private val context: Context, databaseName: String = "input-statistics.db") {
    private sealed interface Command {
        data class KeyBatch(val entries: List<StatsKeySample>, val epoch: Long) : Command
        data class Commit(val id: String, val name: String, val count: Int, val codeLength: Int, val words: String?, val wall: Long, val mono: Long, val epoch: Long, val composing: Boolean) : Command
        data class Heat(val id: String, val name: String, val label: String, val wall: Long, val epoch: Long) : Command
        data object EndSession : Command
        class Task(val epoch: Long, val run: suspend () -> Unit) : Command
    }
    private val mutableSettings = MutableStateFlow(StatsSettings())
    val settings = mutableSettings.asStateFlow()
    private val enqueueLock = Any()
    @Volatile private var accepting = false
    @Volatile private var acceptingWords = false
    val isEnabled: Boolean get() = accepting
    @Volatile var recordingEpoch: Long = 0
        private set
    private var appliedEpoch = 0L
    private var enabledRevision = 0L
    private var wordsRevision = 0L
    private var pendingEvents = 0
    private var endAtQueueTail = false
    private var rejected = 0L
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { StatsDatabase(context, databaseName) }
    private val schemaCache = linkedMapOf<String, StatsSchema>()
    private val buckets = linkedMapOf<Pair<String, String>, StatsBucket>()
    private val dirty = linkedSetOf<Pair<String, String>>()
    private var schemaDirty = false
    private var dirtySince: Long? = null
    private var available = false
    private var session: SpeedSession? = null
    private val windows = linkedMapOf<Pair<String, String>, StatsSpeedWindow>()
    private var lastArrival = 0L

    private data class SpeedSession(val id: String, val day: String, val first: Long, var lastKey: Long,
        var composing: Boolean, var keys: Long = 0, var committedKeys: Long = 0, var characters: Long = 0,
        var codes: Long = 0, var lastCommit: Long = first)

    init {
        scope.launch {
            try {
                // Credential-protected statistics must never be copied or opened during Direct Boot.
                if (Build.VERSION.SDK_INT >= 24 && context.isDeviceProtectedStorage) {
                    mutableSettings.value = StatsSettings(loaded = true)
                } else {
                    val stored = database.settings()
                    database.schemas().forEach { schemaCache[it.id] = it }
                    available = true
                    synchronized(enqueueLock) {
                        if (enabledRevision == 0L) accepting = stored.first
                        if (wordsRevision == 0L) acceptingWords = stored.second
                    }
                    mutableSettings.value = StatsSettings(true, stored.first, stored.second)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
            try {
                var sinceFlush = 0
                while (isActive) {
                    val queued = commands.tryReceive()
                    if (queued.isClosed) break
                    val now = SystemClock.elapsedRealtime()
                    dirtySince = if (dirty.isEmpty() && !schemaDirty) null else dirtySince ?: now
                    val wakeup = StatsActorSchedule.next(now, mutableSettings.value.enabled, dirtySince,
                        if (session?.composing == false) lastArrival else null, commandReady = queued.isSuccess)
                    if (wakeup.flush || wakeup.finishSession) {
                        try {
                            if (wakeup.finishSession) finishSession()
                            // A completed idle session is persisted at this wake-up too.
                            flush()
                        } catch (error: Exception) { fail(error) }
                        sinceFlush = 0
                        if (!queued.isSuccess) continue
                    }
                    val received = if (queued.isSuccess) queued
                        else if (wakeup.delayMillis == null) commands.receiveCatching()
                        else withTimeoutOrNull(wakeup.delayMillis) { commands.receiveCatching() }
                    if (received == null) continue
                    if (received.isClosed) break
                    val command = received.getOrThrow()
                    val weight = when (command) { is Command.KeyBatch -> command.entries.size; is Command.Commit, is Command.Heat -> 1; else -> 0 }
                    if (weight > 0) synchronized(enqueueLock) { pendingEvents -= weight }
                    val epoch = when (command) { is Command.KeyBatch -> command.epoch; is Command.Commit -> command.epoch; is Command.Heat -> command.epoch; else -> null }
                    // The actor advances epochs at the same ordered barrier as clear/disable.
                    // Already accepted records before that barrier still belong to the old state.
                    if (epoch != null && epoch != appliedEpoch) continue
                    try {
                        when (command) {
                            is Command.KeyBatch -> command.entries.forEach(::key)
                            is Command.Commit -> commit(command)
                            is Command.Heat -> bucket(command.id, command.name, command.wall).heat(command.label)
                            Command.EndSession -> finishSession()
                            is Command.Task -> { appliedEpoch = maxOf(appliedEpoch, command.epoch); command.run() }
                        }
                        sinceFlush = if (dirty.isEmpty() && !schemaDirty) 0 else sinceFlush + weight
                        if (sinceFlush >= 64) { flush(); sinceFlush = 0 }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { fail(error) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
        }
    }

    fun recordKey(schemaId: String, schemaName: String, keyId: String, isDeletion: Boolean = false,
        composing: Boolean = false, sensitive: Boolean = false, wallTimeMs: Long = System.currentTimeMillis(),
        monotonicTimeMs: Long = SystemClock.elapsedRealtime(), expectedEpoch: Long = recordingEpoch): Boolean {
        if (!accepting || sensitive) return false
        return recordKeys(listOf(StatsKeySample(schemaId, schemaName, keyId, isDeletion, composing, wallTimeMs, monotonicTimeMs)), expectedEpoch, sensitive)
    }

    fun recordKeys(entries: List<StatsKeySample>, expectedEpoch: Long = recordingEpoch, sensitive: Boolean = false): Boolean =
        record(sensitive, expectedEpoch, entries.size) {
            if (entries.any { !valid(it.schemaId, it.schemaName, it.wallTimeMs) || it.monotonicTimeMs < 0 || !StatsLimits.label(it.keyId) }) null
            else Command.KeyBatch(entries.toList(), expectedEpoch)
        }

    fun recordDropped(count: Long, expectedEpoch: Long = recordingEpoch): Boolean = synchronized(enqueueLock) {
        if (!accepting || expectedEpoch != recordingEpoch || count <= 0) return false
        dropped(count)
        true
    }

    /** codeLength already includes the commit-trigger key. Mixed or non-Han commits are not counted. */
    fun recordCommit(schemaId: String, schemaName: String, text: String, codeLength: Int,
        sensitive: Boolean = false, wallTimeMs: Long = System.currentTimeMillis(),
        monotonicTimeMs: Long = SystemClock.elapsedRealtime(), expectedEpoch: Long = recordingEpoch,
        composing: Boolean = false): Boolean = record(sensitive, expectedEpoch) {
        val count = StatsText.characters(text)
        if (!valid(schemaId, schemaName, wallTimeMs) || monotonicTimeMs < 0 || count == 0 || codeLength !in 0..StatsLimits.MAX_CODE_LENGTH) null
        else Command.Commit(schemaId, schemaName, count, codeLength, text.takeIf { acceptingWords }, wallTimeMs, monotonicTimeMs, expectedEpoch, composing)
    }

    /** Heat counts actual soft-key presses; it is separate from admitted Rime DOWN events. */
    fun recordHeat(schemaId: String, schemaName: String, label: String, sensitive: Boolean = false,
        wallTimeMs: Long = System.currentTimeMillis(), expectedEpoch: Long = recordingEpoch): Boolean = record(sensitive, expectedEpoch) {
        if (!valid(schemaId, schemaName, wallTimeMs) || !StatsLimits.label(label)) null
        else Command.Heat(schemaId, schemaName, label, wallTimeMs, expectedEpoch)
    }

    fun endSession() {
        synchronized(enqueueLock) {
            // Only adjacent boundaries are redundant; input queued between two boundaries
            // must keep its own ending even while the actor is still handling earlier work.
            if (!endAtQueueTail && commands.trySend(Command.EndSession).isSuccess) endAtQueueTail = true
        }
    }

    private fun valid(id: String, name: String, wall: Long) = StatsLimits.schema(id) && name.length <= 128 && wall in 0..253402214400000L
    private inline fun record(sensitive: Boolean, expectedEpoch: Long, weight: Int = 1, make: () -> Command?): Boolean = synchronized(enqueueLock) {
        if (!accepting || sensitive || expectedEpoch != recordingEpoch) return false
        if (weight <= 0) return false
        if (weight > StatsLimits.MAX_PENDING || pendingEvents > StatsLimits.MAX_PENDING - weight) {
            dropped(weight.toLong())
            return false
        }
        val event = make() ?: return false
        if (commands.trySend(event).isSuccess) { pendingEvents += weight; endAtQueueTail = false; true } else false
    }
    private fun dropped(count: Long) {
        rejected = StatsLimits.sum(rejected, count)
        mutableSettings.value = mutableSettings.value.copy(rejectedEvents = rejected)
    }

    suspend fun setEnabled(enabled: Boolean) {
        var revision = 0L
        var wordRevision = 0L
        command(before = { accepting = false; recordingEpoch++; revision = ++enabledRevision; wordRevision = wordsRevision }) {
            requireAvailable(); finishSession(); flush()
            windows.clear()
            database.settings(enabled, mutableSettings.value.wordFrequencyEnabled)
            mutableSettings.value = mutableSettings.value.copy(enabled = enabled, error = null)
            synchronized(enqueueLock) {
                if (revision == enabledRevision) {
                    accepting = enabled
                    // Failure closes both gates. Restore the saved vocabulary preference too,
                    // unless a later word-frequency request already closed that gate again.
                    if (wordRevision == wordsRevision) acceptingWords = enabled && mutableSettings.value.wordFrequencyEnabled
                }
            }
        }
    }
    suspend fun setWordFrequencyEnabled(enabled: Boolean) {
        var revision = 0L
        command(before = { acceptingWords = false; recordingEpoch++; revision = ++wordsRevision }) {
            requireAvailable(); finishSession(); flush(); windows.clear()
            database.settings(mutableSettings.value.enabled, enabled)
            mutableSettings.value = mutableSettings.value.copy(wordFrequencyEnabled = enabled, error = null)
            synchronized(enqueueLock) { if (revision == wordsRevision) acceptingWords = enabled }
        }
    }

    suspend fun snapshot(range: StatsRange = StatsRange.all(), schemaId: String? = null): StatsSnapshot = command {
        requireAvailable(); finishSession(); flush()
        val rows = database.buckets(range, schemaId)
        fun <K : Comparable<K>> counts(select: (StatsBucket) -> Map<K, Long>): Map<K, Long> {
            val result = linkedMapOf<K, Long>()
            rows.forEach { bucket -> select(bucket).forEach { (key, count) -> StatsBucket.increment(result, key, count) } }
            return result.toSortedMap()
        }
        val schemas = database.schemas()
        val career = schemas.filter { schemaId == null || it.id == schemaId }.fold(0L) { total, it -> StatsLimits.sum(total, it.careerCharacters) }
        StatsSnapshot(range, schemaId, schemas, combineTotals(rows.map { it.totals }),
            rows.groupBy { it.day }.toSortedMap().map { (day, buckets) -> StatsDay(day, combineTotals(buckets.map { it.totals })) },
            counts { it.codeLengths }, counts { it.wordLengths }, counts { it.heat }, counts { it.words }, career,
            database.layouts().let { if (schemaId != null) it[schemaId] else if (it.size == 1) it.values.single() else null },
            recordingEpoch = appliedEpoch)
    }

    /** Removes selected daily records; the lifetime character counter is intentionally retained. */
    suspend fun clear(range: StatsRange = StatsRange.all(), schemaId: String? = null) = command(before = { recordingEpoch++ }) {
        requireAvailable(); finishSession(); flush()
        database.clear(range, schemaId)
        buckets.clear(); dirty.clear(); windows.clear()
    }
    suspend fun clearPeaks(range: StatsRange = StatsRange.all(), schemaId: String? = null) = command(before = { recordingEpoch++ }) {
        requireAvailable(); finishSession(); flush()
        val rows = database.buckets(range, schemaId).onEach { it.clearPeaks() }
        database.save(rows, emptyList())
        buckets.clear(); dirty.clear(); windows.clear()
    }
    suspend fun setLayout(schemaId: String, layout: StatsLayout, expectedEpoch: Long = recordingEpoch) = command {
        requireAvailable()
        if (!mutableSettings.value.enabled || expectedEpoch != appliedEpoch || expectedEpoch != recordingEpoch) return@command
        require(StatsLimits.schema(schemaId))
        StatsArchive.validateLayout(layout)
        if (schemaId !in schemaCache) {
            require(schemaCache.size < StatsLimits.MAX_SCHEMAS)
            schemaCache[schemaId] = StatsSchema(schemaId, schemaId); schemaDirty = true
        }
        flush(); database.layout(schemaId, layout)
    }

    /** Privacy reset: all schemes, historical counters, layout labels, and the import backup. */
    suspend fun clearAllData() = command(before = {
        accepting = false; acceptingWords = false; recordingEpoch++; enabledRevision++; wordsRevision++
    }) {
        requireAvailable()
        val backup = File(context.noBackupFilesDir, "input-stats-before-import.json")
        // Atomic writes can leave their private temporary file behind after process death.
        FileUtil.removeFile(File(backup.parentFile, "${backup.name}.tmp")).getOrThrow()
        FileUtil.removeFile(backup).getOrThrow()
        FileUtil.removeFile(File(context.cacheDir, "shared/input-statistics")).getOrThrow()
        database.clearAll()
        session = null; windows.clear(); buckets.clear(); dirty.clear(); schemaCache.clear(); schemaDirty = false
        mutableSettings.value = StatsSettings(loaded = true)
        rejected = 0
    }

    suspend fun exportJson(): String = command { requireAvailable(); finishSession(); flush(); archiveText() }
    /** Replace, never add totals twice. Invalid imports leave all existing records intact. */
    suspend fun importJson(text: String) = command(before = { recordingEpoch++ }) {
        requireAvailable()
        val replacement = StatsArchive.decode(text)
        finishSession(); flush()
        FileUtil.writeAtomically(File(context.noBackupFilesDir, "input-stats-before-import.json"), archiveText())
        database.replace(replacement)
        buckets.clear(); dirty.clear(); windows.clear(); schemaCache.clear()
        replacement.schemas.forEach { schemaCache[it.id] = it }
        schemaDirty = false
    }
    private fun archiveText(): String = StatsArchive.json.encodeToString(
        StatsArchive(schemas = database.schemas(), buckets = database.buckets(StatsRange.all(), null), layouts = database.layouts())
    ).also { require(it.toByteArray(Charsets.UTF_8).size <= StatsLimits.MAX_ARCHIVE_BYTES) { "Input statistics export exceeds 32 MiB" } }

    private suspend fun <T> command(before: () -> Unit = {}, action: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        synchronized(enqueueLock) {
            before()
            check(commands.trySend(Command.Task(recordingEpoch) {
                try { result.complete(action()) }
                catch (error: Throwable) { result.completeExceptionally(error) }
            }).isSuccess) { "Input statistics are closed" }
            endAtQueueTail = false
        }
        return result.await()
    }

    private fun bucket(id: String, name: String, wall: Long): StatsBucket {
        requireAvailable()
        val known = schemaCache[id]
        if (known == null) {
            require(schemaCache.size < StatsLimits.MAX_SCHEMAS) { "Too many input schemes in statistics" }
            schemaCache[id] = StatsSchema(id, name.ifBlank { id }); schemaDirty = true
        } else if (known.name != name && name.isNotBlank()) { schemaCache[id] = known.copy(name = name); schemaDirty = true }
        val day = StatsRange.day(wall)
        val key = day to id
        if (buckets.size >= 512 && key !in buckets) { flush(); buckets.clear() }
        val result = buckets.getOrPut(key) { database.bucket(day, id) ?: StatsBucket(day, id) }
        dirty += key
        return result
    }
    private fun key(event: StatsKeySample) {
        val bucket = bucket(event.schemaId, event.schemaName, event.wallTimeMs)
        val current = session
        if (current != null && (current.id != event.schemaId || current.day != bucket.day || event.monotonicTimeMs < current.lastKey ||
                !current.composing && event.monotonicTimeMs - current.lastKey > 5000)) finishSession()
        val active = session ?: SpeedSession(event.schemaId, bucket.day, event.monotonicTimeMs, event.monotonicTimeMs, event.composing).also { session = it }
        active.keys = StatsLimits.sum(active.keys, 1); active.lastKey = event.monotonicTimeMs; active.composing = event.composing
        lastArrival = SystemClock.elapsedRealtime()
        bucket.key(event.isDeletion)
    }
    private fun commit(event: Command.Commit) {
        val bucket = bucket(event.id, event.name, event.wall)
        if (session?.let { it.id != event.id || it.day != bucket.day || event.mono < it.first } == true) finishSession()
        val active = session ?: SpeedSession(event.id, bucket.day, event.mono, event.mono, false).also { session = it }
        active.characters = StatsLimits.sum(active.characters, event.count.toLong())
        active.codes = StatsLimits.sum(active.codes, event.codeLength.toLong())
        active.lastCommit = event.mono; active.committedKeys = active.keys; active.composing = event.composing
        lastArrival = SystemClock.elapsedRealtime()
        bucket.commit(event.count, event.codeLength, event.words)
        val schema = schemaCache.getValue(event.id)
        schemaCache[event.id] = schema.copy(careerCharacters = StatsLimits.sum(schema.careerCharacters, event.count.toLong()))
        schemaDirty = true
    }
    private fun finishSession() {
        val current = session ?: return
        session = null
        val duration = current.lastCommit - current.first
        if (duration < 1000 || current.characters == 0L) return
        val key = current.day to current.id
        val bucket = buckets.getOrPut(key) { database.bucket(current.day, current.id) ?: StatsBucket(current.day, current.id) }
        val sample = StatsSpeedSample(duration, current.characters, current.committedKeys, current.codes)
        val window = windows.getOrPut(key) { StatsSpeedWindow(bucket.speedTail) }
        val peak = window.add(sample)
        if (windows.size > 512) windows.remove(windows.keys.first())
        bucket.speed(sample, peak)
        bucket.speedTail.clear(); bucket.speedTail.addAll(window.snapshot())
        dirty += key
    }
    private fun flush() {
        if (!available || dirty.isEmpty() && !schemaDirty) return
        database.save(dirty.mapNotNull(buckets::get), if (schemaDirty) schemaCache.values else emptyList())
        dirty.clear(); schemaDirty = false; dirtySince = null
    }
    private fun requireAvailable() = check(available) { mutableSettings.value.error ?: "Input statistics storage is unavailable" }
    private fun fail(error: Exception) {
        synchronized(enqueueLock) { accepting = false; acceptingWords = false; recordingEpoch++ }
        appliedEpoch = recordingEpoch
        mutableSettings.value = mutableSettings.value.copy(loaded = true, enabled = false,
            error = error.message ?: error.javaClass.simpleName)
    }
    internal suspend fun close() {
        synchronized(enqueueLock) { accepting = false; acceptingWords = false; recordingEpoch++ }
        command { finishSession(); flush(); database.close() }
        commands.close(); scope.cancel()
    }

    companion object {
        @Volatile private var instance: InputStats? = null
        fun get(context: Context): InputStats = instance ?: synchronized(this) {
            instance ?: InputStats(context.applicationContext).also { instance = it }
        }
    }
}
