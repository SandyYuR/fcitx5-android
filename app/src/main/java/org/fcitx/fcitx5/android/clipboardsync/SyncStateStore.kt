/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.clipboardsync

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-backed storage for the clipboard-sync service's own state.
 *
 * Previously these values lived in the app's **default** SharedPreferences, whose XML is parsed
 * into memory in full by every process that touches `PreferenceManager` — including the IME
 * process. The values are serialized clipboard payloads, so a few large copies inflated the
 * settings file and, with it, the IME's resident memory, permanently.
 *
 * Each key is a plain file under `filesDir/clipboardsync_state/`, so a large value costs disk
 * rather than the shared-preferences cache, and [migrateFromPreferences] moves any legacy values
 * out of the default preferences on first use.
 */
class SyncStateStore(context: Context) {

    private val dir = File(context.filesDir, DIR_NAME)

    private fun fileFor(key: String) = File(dir, "$key.json")

    /**
     * Values already handed to [write] but possibly not yet on disk.
     *
     * [write] is asynchronous, so this keeps [read] consistent with what the caller last wrote
     * without having to wait for the file. A deletion is represented by [DELETED] rather than by
     * removing the entry: `read` must see the deletion immediately, not fall through to the old
     * file that the queued delete has not reached yet.
     */
    private val pending = ConcurrentHashMap<String, Any>()

    /**
     * Keys with a write queued but not yet performed, mapped to their latest requested value.
     *
     * A `ConcurrentHashMap` rejects null values, so a pending deletion uses the [DELETED] sentinel.
     * Multiple writes for one key collapse into the last value, so a burst of updates costs one
     * pass over the disk instead of one per call.
     */
    private val dirty = ConcurrentHashMap<String, Any>()

    /**
     * True while a drain task is queued or running.
     *
     * Keeps at most one drain in flight; a write that arrives during a drain only updates [dirty]
     * and lets that drain pick the value up (see [drain] for the release/reschedule handshake).
     * Only touched while holding [handoff].
     */
    private val drainScheduled = AtomicBoolean(false)

    /**
     * Orders the hand-off between [write] and [drain]; **never** held across file IO.
     *
     * Two bare atomics would leave a visibility gap: a drain that resets [drainScheduled] and then
     * looks at an (empty-looking) [dirty] is not ordered against a writer whose claim attempt had
     * already failed, so the writer's entry could in principle stay invisible to that final check.
     * Guarding the "publish value + claim/release slot" steps with one monitor removes the gap:
     * every entry a drain fails to see was published under the monitor, so its writer either got
     * the slot itself or is ordered before the drain's own monitor section.
     */
    private val handoff = Any()

    fun read(key: String): String? {
        when (val overlay = pending[key]) {
            // 删除在落盘前也必须对 read 可见，否则会读到排队删除尚未覆盖的旧文件
            DELETED -> return null
            is String -> return overlay
            else -> Unit // 没有待落盘的写入，去读文件
        }
        return runCatching {
            val file = fileFor(key)
            if (!file.isFile) null else file.readText().takeIf { it.isNotBlank() }
        }.onFailure { Timber.w(it, "Failed to read sync state: $key") }.getOrNull()
    }

    /**
     * Persist [value] for [key], or delete the entry when [value] is null or blank.
     *
     * The file work is handed to [writeExecutor] rather than done inline: callers include
     * [MainService.onStartCommand] and the clipboard listener, i.e. the main thread, and a
     * suppressed-item set can serialize to megabytes. Writes for one key stay ordered because
     * the executor is single-threaded.
     *
     * Writes are coalesced per key (latest wins) before they reach the executor, so repeated
     * updates of the same key cannot queue up a full write each.
     *
     * The write itself goes to a temporary file and renames, so a process death mid-write
     * cannot leave a truncated JSON document that fails to parse on the next start.
     */
    fun write(key: String, value: String?) {
        // 空白值与 null 一样表示删除，与 read 的过滤规则保持一致
        val overlay: Any = value?.takeIf { it.isNotBlank() } ?: DELETED
        pending[key] = overlay
        val claimed = synchronized(handoff) {
            // latest-wins：同一 key 的多次写只在 dirty 里保留最后一个值
            dirty[key] = overlay
            drainScheduled.compareAndSet(false, true)
        }
        // 提交任务放在锁外：抢到槽位就一定提交，因此在跑的 drain 只可能是本线程安排的
        if (claimed) writeExecutor.execute { drain() }
    }

    /**
     * Persist every currently dirty key, then hand the slot over safely.
     *
     * The loop re-snapshots until it observes an empty [dirty]: writes that land while a batch is
     * being written only replace entries in [dirty] (their claim sees the flag held), so they are
     * picked up by the next round instead of being dropped.
     */
    private fun drain() {
        try {
            while (true) {
                val batch: Map<String, Any>
                synchronized(handoff) {
                    if (dirty.isEmpty()) return
                    // 一次性取走全部待落盘项：落盘期间到达的写入留给下一轮
                    batch = HashMap(dirty)
                    dirty.clear()
                }
                batch.forEach { (key, value) -> persist(key, value) }
            }
        } finally {
            // 异常时也必须释放占位，否则后续所有 write 都会因为标志永远为 true 而不再落盘。
            val reschedule = synchronized(handoff) {
                drainScheduled.set(false)
                // 释放之后再复查一次：在释放之前到达的写入看到 true 不会自行调度，只能由这里接住
                dirty.isNotEmpty() && drainScheduled.compareAndSet(false, true)
            }
            if (reschedule) writeExecutor.execute { drain() }
        }
    }

    /** 落盘单个 key（[DELETED] 表示删除），失败只记日志，不中断 drain。 */
    private fun persist(key: String, value: Any) {
        runCatching {
            if (value == DELETED) {
                fileFor(key).delete()
                return@runCatching
            }
            val content = value as String
            dir.mkdirs()
            val target = fileFor(key)
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }.onFailure { Timber.w(it, "Failed to write sync state: $key") }
        // Drop the overlay only while it still holds exactly the value just written; a newer write
        // that replaced it in the meantime must stay visible to read.
        pending.remove(key, value)
    }

    /**
     * Move legacy values out of the default SharedPreferences into this store, once.
     *
     * A value already present here wins, so this is safe to call on every service start.
     */
    fun migrateFromPreferences(prefs: SharedPreferences, keys: List<String>) {
        val editor = prefs.edit()
        var migrated = false
        keys.forEach { key ->
            val legacy = runCatching { prefs.getString(key, null) }
            if (legacy.isFailure) {
                // A value of another type under this key (an older build wrote a different
                // shape). It cannot be migrated, but it still has to go, or getString keeps
                // throwing here on every start and the key never leaves the preferences file.
                Timber.w(legacy.exceptionOrNull(), "Dropping unreadable legacy sync state: $key")
                editor.remove(key)
                migrated = true
                return@forEach
            }
            val value = legacy.getOrNull()
            if (value.isNullOrBlank()) return@forEach
            if (read(key) == null) write(key, value)
            editor.remove(key)
            migrated = true
        }
        if (migrated) {
            editor.apply()
            Timber.i("Migrated clipboard-sync state out of default preferences")
        }
    }

    companion object {
        private const val DIR_NAME = "clipboardsync_state"

        /**
         * 哨兵：表示某个 key 待落盘的操作是删除。
         *
         * `ConcurrentHashMap` 不接受 null 值，而 `write(key, null)` 语义是删除，因此用这个
         * 单例对象占位，避免用第二个集合同步维护「删除」这一状态。
         */
        private val DELETED = Any()

        /**
         * Single background thread for all state writes.
         *
         * Single-threaded on purpose: writes for the same key must not overtake each other, and
         * the state files are small enough that one thread is never a bottleneck.
         */
        private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clipboardsync-state").apply { isDaemon = true }
        }

        /**
         * Upper bound on a single persisted clipboard payload, in characters.
         *
         * Anything larger is kept in memory for the current session but not written to disk:
         * restoring a multi-megabyte clipboard entry after a restart is not worth an unbounded
         * state file. Roughly 64 KB of UTF-16, which covers any realistic text clipboard.
         */
        const val MAX_PERSISTED_CONTENT_LENGTH = 32 * 1024

        /** True when [content] is small enough to be worth persisting. */
        fun isPersistable(content: String): Boolean =
            content.length <= MAX_PERSISTED_CONTENT_LENGTH
    }
}
