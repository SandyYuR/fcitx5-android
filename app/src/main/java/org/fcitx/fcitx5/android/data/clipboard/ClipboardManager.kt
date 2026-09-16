/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.CLIPBOARD_DATABASE_NAME
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.ClipboardSourceDeletionTarget
import org.fcitx.fcitx5.android.utils.ClipboardUriStore
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.deleteClipboardSourceFile
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.normalizeClipboardText
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.originalClipboardTextOrEmpty
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.stageForCommit
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.toClipboardUriOrNull
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao

    /**
     * Upper bound on rows returned by [searchEntries].
     *
     * The live-search UI only displays 50 entries (see `ClipboardSearchController.MAX_RESULTS`),
     * so pulling the whole history across the cursor is pure waste. 200 leaves generous headroom
     * for entries that the display side may still drop, while keeping one query's row set small
     * and bounded regardless of how large the history grows.
     */
    private const val SEARCH_LIMIT = 200

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val localLimitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimitLocal
    private val remoteLimitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimitRemote
    private val mediaLimitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimitMedia

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    private fun clearLastEntry() {
        lastEntry = null
        onUpdateListeners.forEach {
            it.onUpdate(
                ClipboardEntry(
                    text = "",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun normalizeEntry(entry: ClipboardEntry): ClipboardEntry {
        if (entry.text.startsWith("content://") || entry.text.startsWith("file://")) {
            // For URI entries (like clipboard images), try to stage the content
            // so we have a local copy with proper permissions. Staging copies the whole
            // file, so run it on IO rather than the caller's dispatcher.
            val staged = withContext(Dispatchers.IO) { normalizeClipboardText(appContext, entry.text) }
            return if (staged == entry.text) {
                // Staging failed - check if this is an ExternalStorageProvider tree URI
                // that we can handle by extracting the file path directly
                val treeUri = entry.text.toClipboardUriOrNull()
                if (treeUri != null && isExternalStorageProviderTreeUri(treeUri)) {
                    val filePath = extractFilePathFromTreeUri(treeUri)
                    if (filePath != null) {
                        val stagedFromPath = withContext(Dispatchers.IO) {
                            normalizeClipboardText(appContext, "file://$filePath")
                        }
                        if (stagedFromPath != entry.text && !stagedFromPath.startsWith("content://com.android.externalstorage")) {
                            return entry.copy(text = stagedFromPath)
                        }
                    }
                }
                // Staging failed or returned same URI, keep original
                entry
            } else {
                // Staging succeeded, use FileProvider URI
                entry.copy(text = staged)
            }
        }
        val normalizedText = withContext(Dispatchers.IO) { normalizeClipboardText(appContext, entry.text) }
        val normalizedOriginalText = when {
            entry.originalText.isNotEmpty() -> entry.originalText
            normalizedText == entry.text -> ""
            else -> originalClipboardTextOrEmpty(entry.text, normalizedText)
        }
        return if (normalizedText == entry.text && normalizedOriginalText == entry.originalText) {
            entry
        } else {
            entry.copy(text = normalizedText, originalText = normalizedOriginalText)
        }
    }

    private fun isExternalStorageProviderTreeUri(uri: Uri): Boolean {
        return uri.authority == "com.android.externalstorage.documents" &&
                uri.path?.startsWith("/tree/") == true
    }

    private fun extractFilePathFromTreeUri(treeUri: Uri): String? {
        // Tree URI format: content://com.android.externalstorage.documents/tree/primary%3ADownload%2FFcitx5-clipboard/document/primary%3ADownload%2FFcitx5-clipboard%2FImage.png
        // Document ID format: primary:Download/Fcitx5-clipboard/Image.png
        val documentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Timber.w("Failed to get document ID from tree URI: $treeUri")
            return null
        }
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volume, relativePath) = parts[0] to parts[1]
        return when {
            volume.equals("primary", ignoreCase = true) -> {
                if (relativePath.isBlank()) {
                    "/storage/emulated/0"
                } else {
                    "/storage/emulated/0/$relativePath"
                }
            }
            else -> "/storage/$volume/$relativePath"
        }
    }

    private suspend fun insertOrUpdateEntry(
        entry: ClipboardEntry,
        notifyListeners: Boolean
    ): ClipboardEntry {
        val normalizedEntry = normalizeEntry(entry)
        if (normalizedEntry.text.isBlank()) return normalizedEntry
        return try {
            clbDao.find(normalizedEntry.text, normalizedEntry.sensitive, normalizedEntry.source)?.let {
                val updated = it.copy(
                    timestamp = normalizedEntry.timestamp,
                    originalText = normalizedEntry.originalText,
                    originalRootUri = normalizedEntry.originalRootUri,
                    type = normalizedEntry.type
                )
                if (notifyListeners) {
                    updateLastEntry(updated)
                }
                clbDao.updateTime(it.id, normalizedEntry.timestamp)
                updated
            } ?: run {
                val insertedEntry = clbDb.withTransaction {
                    val rowId = clbDao.insert(normalizedEntry)
                    removeOutdated()
                    // new entry can be deleted immediately if clipboard limit == 0
                    clbDao.get(rowId) ?: normalizedEntry
                }
                if (notifyListeners) {
                    updateLastEntry(insertedEntry)
                }
                updateItemCount()
                insertedEntry
            }
        } catch (exception: Exception) {
            Timber.w("Failed to update clipboard database: $exception")
            if (notifyListeners) {
                updateLastEntry(normalizedEntry)
            }
            normalizedEntry
        }
    }

    fun init(context: Context) {
        clbDb = Room
            .databaseBuilder(context, ClipboardDatabase::class.java, CLIPBOARD_DATABASE_NAME)
            // No fallbackToDestructiveMigrationOnDowngrade: wiping the tables silently
            // discarded the user's pinned and favorite entries when a database written by a
            // newer variant was opened here. Opening it now fails loudly instead, and the
            // backup importer refuses a database with a higher user_version up front.
            .build()
        clbDao = clbDb.clipboardDao()
        // Staged clipboard files are pruned by ClipboardUriStore; drop the history entries
        // that pointed at the files it evicted, otherwise they linger with a dead
        // FileProvider URI (blank thumbnail, paste does nothing).
        ClipboardUriStore.onStagedFilesEvicted = { evicted ->
            launch { removeEntriesForEvictedFiles(evicted) }
        }
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(localLimitPref.key, localLimitPref.getValue())
        localLimitPref.registerOnChangeListener(limitListener)
        remoteLimitPref.registerOnChangeListener(limitListener)
        mediaLimitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
    }

    /**
     * Close the Room database before its files are overwritten (backup import).
     *
     * Without this, Room keeps the old database's file descriptors and page cache while the
     * importer replaces clbdb (and its -wal/-shm) underneath it, which can leave the
     * imported database inconsistent with the leftover WAL.
     */
    fun closeDatabase() {
        if (!::clbDb.isInitialized) return
        runCatching { clbDb.close() }
            .onFailure { Timber.w(it, "Failed to close clipboard database") }
    }

    /**
     * Flush the WAL into the main database file so an export contains a self-consistent
     * snapshot even though only `clbdb` is copied.
     */
    fun checkpointDatabase() {
        if (!::clbDb.isInitialized) return
        runCatching {
            clbDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }.onFailure { Timber.w(it, "Failed to checkpoint clipboard database") }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned(category: ClipboardCategory) = when (category) {
        ClipboardCategory.All -> clbDao.findUnpinnedIds().isNotEmpty()
        ClipboardCategory.Favorites -> false
        ClipboardCategory.Local -> clbDao.haveUnpinnedTextEntriesBySource(ClipboardEntry.SOURCE_LOCAL)
        ClipboardCategory.Remote -> clbDao.haveUnpinnedEntriesBySource(ClipboardEntry.SOURCE_REMOTE)
        ClipboardCategory.Media -> clbDao.haveUnpinnedMediaEntries()
    }

    fun allEntries() = clbDao.allEntries()

    /**
     * History search: newest first, filtered in SQLite and capped at [SEARCH_LIMIT] rows.
     *
     * This used to call `allEntriesForSearch()` and then filter in Kotlin, which materialized
     * every entry in the history on every keystroke — the live-search box (ClipboardSearchController)
     * re-runs this on each input change, so the cost scaled with the history size. The filtering
     * now happens in SQLite and only a bounded row set crosses the cursor.
     *
     * Two behaviours are preserved deliberately:
     *  - the search is case-insensitive and matches `text` as well as `originalText`;
     *  - the newest entries win when the limit truncates.
     *
     * Known difference from the old in-memory filter: SQLite's LIKE folds case for ASCII only,
     * while Kotlin's `contains(ignoreCase = true)` folds a wider Unicode set. The two agree for
     * ASCII, for CJK (no case), and for the accented Latin text this history holds in practice;
     * they can differ for scripts with non-ASCII case pairs (Greek, Cyrillic, Turkish dotted I).
     */
    suspend fun searchEntries(query: String): List<ClipboardEntry> {
        if (query.isEmpty()) return emptyList()
        return clbDao.searchEntries(escapeLikePattern(query), SEARCH_LIMIT)
    }

    /**
     * Escape LIKE metacharacters in a user-typed query.
     *
     * `%`, `_` and the escape character itself are pattern syntax; without escaping, typing `%`
     * would match every entry and `_` would match any single character. The DAO passes
     * `ESCAPE '\'`, so a literal backslash must be doubled.
     */
    private fun escapeLikePattern(query: String): String = query
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    fun favoriteEntries() = clbDao.favoriteEntries()

    fun localTextEntries() = clbDao.textEntriesBySource(ClipboardEntry.SOURCE_LOCAL)

    fun remoteTextEntries() = clbDao.textEntriesBySource(ClipboardEntry.SOURCE_REMOTE)

    fun remoteEntries() = clbDao.entriesBySource(ClipboardEntry.SOURCE_REMOTE)

    fun mediaEntries() = clbDao.mediaEntries()

    suspend fun pin(id: Int) = clbDao.updatePinStatus(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinStatus(id, false)

    suspend fun markUsed(id: Int, timestamp: Long = System.currentTimeMillis()) {
        clbDao.updateTime(id, timestamp)
    }

    suspend fun updateText(id: Int, text: String) {
        lastEntry?.let {
            if (id == it.id) updateLastEntry(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        val shouldClearSuggestion = lastEntry?.id == id
        clbDao.markAsDeleted(id)
        if (shouldClearSuggestion) {
            clearLastEntry()
        }
        updateItemCount()
    }

    /**
     * Drop media entries whose staged cache file was just evicted. Their FileProvider URI is
     * dead, so keeping them only produces broken thumbnails and failed pastes.
     */
    private suspend fun removeEntriesForEvictedFiles(evicted: List<java.io.File>) {
        if (evicted.isEmpty()) return
        val names = evicted.map { it.name }.toSet()
        val stale = runCatching { clbDao.getAllMediaEntries() }.getOrNull() ?: return
        val ids = stale.filter { entry ->
            val last = entry.text.substringAfterLast('/').substringBefore('?')
            last in names
        }.map { it.id }
        if (ids.isEmpty()) return
        clbDao.markAsDeleted(*ids.toIntArray())
        clbDao.realDelete()
        if (lastEntry?.id in ids) clearLastEntry()
        updateItemCount()
        Timber.d("Dropped ${ids.size} clipboard entries whose staged file was evicted")
    }

    suspend fun deleteAll(category: ClipboardCategory, skipPinned: Boolean = true): IntArray {
        val ids = when (category) {
            ClipboardCategory.All -> {
                if (skipPinned) {
                    clbDao.findUnpinnedIds()
                } else {
                    clbDao.findAllIds()
                }
            }

            ClipboardCategory.Favorites -> {
                if (skipPinned) {
                    intArrayOf()
                } else {
                    clbDao.findPinnedIds()
                }
            }

            ClipboardCategory.Local -> {
                if (skipPinned) {
                    clbDao.findUnpinnedTextEntryIdsBySource(ClipboardEntry.SOURCE_LOCAL)
                } else {
                    clbDao.findAllTextEntryIdsBySource(ClipboardEntry.SOURCE_LOCAL)
                }
            }

            ClipboardCategory.Remote -> {
                if (skipPinned) {
                    clbDao.findUnpinnedEntryIdsBySource(ClipboardEntry.SOURCE_REMOTE)
                } else {
                    clbDao.findAllEntryIdsBySource(ClipboardEntry.SOURCE_REMOTE)
                }
            }

            ClipboardCategory.Media -> {
                if (skipPinned) {
                    clbDao.findUnpinnedMediaEntryIds()
                } else {
                    clbDao.findAllMediaEntryIds()
                }
            }
        }
        if (ids.isNotEmpty()) {
            val shouldClearSuggestion = lastEntry?.id?.let { lastId -> ids.contains(lastId) } == true
            clbDao.markAsDeleted(*ids)
            if (shouldClearSuggestion) {
                clearLastEntry()
            }
            updateItemCount()
        }
        return ids
    }

    suspend fun mediaDeletionTargets(skipPinned: Boolean): Map<Int, ClipboardSourceDeletionTarget> {
        val entries = if (skipPinned) {
            clbDao.getAllUnpinnedMediaEntries()
        } else {
            clbDao.getAllMediaEntries()
        }
        return entries.mapNotNull { entry ->
            entry.remoteMediaDeletionSource()?.let { entry.id to it }
        }.toMap()
    }

    suspend fun mediaDeletionTarget(id: Int): ClipboardSourceDeletionTarget? {
        return clbDao.get(id)?.remoteMediaDeletionSource()
    }

    suspend fun mediaDeletionTargetsBySource(
        source: String,
        skipPinned: Boolean
    ): Map<Int, ClipboardSourceDeletionTarget> {
        val entries = if (skipPinned) {
            clbDao.getAllUnpinnedMediaEntries()
        } else {
            clbDao.getAllMediaEntries()
        }
        return entries
            .asSequence()
            .filter { it.source == source }
            .mapNotNull { entry -> entry.remoteMediaDeletionSource()?.let { entry.id to it } }
            .toMap()
    }

    suspend fun remoteSuppressionContent(id: Int): String? {
        return clbDao.get(id)?.remoteSuppressionContent()
    }

    suspend fun remoteSuppressionContents(
        category: ClipboardCategory,
        skipPinned: Boolean
    ): List<String> {
        val entries = when (category) {
            ClipboardCategory.All -> if (skipPinned) {
                clbDao.getAllUnpinnedEntriesBySource(ClipboardEntry.SOURCE_REMOTE)
            } else {
                clbDao.getAllEntriesBySource(ClipboardEntry.SOURCE_REMOTE)
            }

            ClipboardCategory.Favorites -> if (skipPinned) {
                emptyList()
            } else {
                clbDao.getAllEntriesBySource(ClipboardEntry.SOURCE_REMOTE)
                    .filter { it.pinned }
            }

            ClipboardCategory.Local -> emptyList()

            ClipboardCategory.Remote -> if (skipPinned) {
                clbDao.getAllUnpinnedEntriesBySource(ClipboardEntry.SOURCE_REMOTE)
            } else {
                clbDao.getAllEntriesBySource(ClipboardEntry.SOURCE_REMOTE)
            }

            ClipboardCategory.Media -> {
                val mediaEntries = if (skipPinned) {
                    clbDao.getAllUnpinnedMediaEntries()
                } else {
                    clbDao.getAllMediaEntries()
                }
                mediaEntries.filter { it.source == ClipboardEntry.SOURCE_REMOTE }
            }
        }
        return entries.mapNotNull { it.remoteSuppressionContent() }.distinct()
    }

    suspend fun undoDelete(vararg ids: Int) {
        clbDao.undoDelete(*ids)
        updateItemCount()
    }

    suspend fun realDelete() {
        clbDao.realDelete()
    }

    suspend fun nukeTable() {
        withContext(coroutineContext) {
            clbDb.clearAllTables()
            clearLastEntry()
            updateItemCount()
        }
    }

    suspend fun importRemoteEntry(
        text: String,
        originalText: String = "",
        originalRootUri: String = "",
        type: String = android.content.ClipDescription.MIMETYPE_TEXT_PLAIN,
        timestamp: Long = System.currentTimeMillis(),
        sensitive: Boolean = false,
        notifyListeners: Boolean = false
    ): ClipboardEntry? {
        if (text.isBlank()) return null
        return mutex.withLock {
            insertOrUpdateEntry(
                ClipboardEntry(
                    text = text,
                    originalText = originalText,
                    originalRootUri = originalRootUri,
                    timestamp = timestamp,
                    type = type,
                    source = ClipboardEntry.SOURCE_REMOTE,
                    sensitive = sensitive
                ),
                notifyListeners = notifyListeners
            )
        }
    }

    suspend fun importLocalEntry(
        text: String,
        type: String = android.content.ClipDescription.MIMETYPE_TEXT_PLAIN,
        timestamp: Long = System.currentTimeMillis(),
        sensitive: Boolean = false,
        notifyListeners: Boolean = true
    ): ClipboardEntry? {
        if (text.isBlank()) return null
        return mutex.withLock {
            insertOrUpdateEntry(
                ClipboardEntry(
                    text = text,
                    timestamp = timestamp,
                    type = type,
                    source = ClipboardEntry.SOURCE_LOCAL,
                    sensitive = sensitive
                ),
                notifyListeners = notifyListeners
            )
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                val entry = ClipboardEntry.fromClipData(clip, transformer) ?: return@withLock
                // For URI entries (clipboard images), try to stage the content immediately
                // while we have clipboard permission, so we have a local copy
                var finalEntry = entry
                if (entry.isUriEntry() && entry.type.startsWith("image/")) {
                    // IO: this copies the entire image while holding the clipboard lock; on
                    // the Default dispatcher it kept every other clipboard operation waiting.
                    val staged = withContext(Dispatchers.IO) {
                        stageForCommit(appContext, entry.text.toClipboardUriOrNull()!!)
                    }
                    if (staged != null) {
                        finalEntry = entry.copy(text = staged.uri.toString())
                        Timber.d("Staged clipboard image to local file: ${staged.uri}")
                    } else {
                        Timber.w("Failed to stage clipboard image URI: ${entry.text}")
                    }
                }
                insertOrUpdateEntry(finalEntry, notifyListeners = true)
            }
        }
    }

    private suspend fun removeOutdated() {
        var deletedAny = false
        // 只取 id：旧实现把三类条目的完整 ClipboardEntry 全读出来再排序求保留集，
        // 每次插入的成本都随历史规模线性增长（含长文本载荷的整对象物化）。
        // 保留规则完全不变——仍是"按 id 保留最大的 limit 条"，见 trimOutdatedEntries。
        deletedAny = trimOutdatedEntries(
            clbDao.findUnpinnedTextEntryIdsBySource(ClipboardEntry.SOURCE_LOCAL),
            localLimitPref.getValue()
        ) || deletedAny
        deletedAny = trimOutdatedEntries(
            clbDao.findUnpinnedTextEntryIdsBySource(ClipboardEntry.SOURCE_REMOTE),
            remoteLimitPref.getValue()
        ) || deletedAny
        deletedAny = trimOutdatedEntries(
            clbDao.findUnpinnedMediaEntryIds(),
            mediaLimitPref.getValue()
        ) || deletedAny
        if (deletedAny) {
            updateItemCount()
        }
    }

    /**
     * 按 id 裁剪一类条目，保留 id 最大的 [limit] 条。
     *
     * 与旧实现 `entries.sortedBy { it.id }.takeLast(limit)` 等价：DAO 的 id 查询不保证顺序，
     * 所以这里自己排序——排 IntArray 比排一堆实体对象便宜得多，也不物化任何文本载荷。
     * 数量未超限时**不做任何写操作**（与旧实现一致，避免每次插入都触发一次无害但多余的写）。
     */
    private suspend fun trimOutdatedEntries(entryIds: IntArray, limit: Int): Boolean {
        val keep = limit.coerceAtLeast(0)
        if (entryIds.size <= keep) {
            return false
        }
        val ascending = entryIds.sortedArray()
        // 升序后取前 (size - keep) 个即为待删的"较旧"部分
        val toDelete = IntArray(ascending.size - keep) { ascending[it] }
        if (toDelete.isEmpty()) {
            return false
        }
        clbDao.markAsDeleted(*toDelete)
        return true
    }

    suspend fun deleteClipboardSourceFiles(targets: Collection<ClipboardSourceDeletionTarget>) {
        withContext(Dispatchers.IO) {
            targets.forEach { target ->
                runCatching { deleteClipboardSourceFile(appContext, target) }
                    .onFailure { Timber.w(it, "Failed to delete clipboard source file: %s", target.rawUri) }
            }
        }
    }

    private fun ClipboardEntry.remoteMediaDeletionSource(): ClipboardSourceDeletionTarget? {
        if (!isUriEntry()) return null
        val rawUri = if (source == ClipboardEntry.SOURCE_REMOTE && originalText.isNotEmpty()) {
            originalText.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        } else {
            text.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        } ?: return null
        val rootUri = if (source == ClipboardEntry.SOURCE_REMOTE && originalRootUri.isNotEmpty()) {
            originalRootUri.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        } else {
            rawUri
        } ?: return null
        return ClipboardSourceDeletionTarget(rawUri = rawUri, rootUri = rootUri)
    }

    private fun ClipboardEntry.remoteSuppressionContent(): String? {
        if (source != ClipboardEntry.SOURCE_REMOTE) return null
        if (isUriEntry()) {
            return originalText
                .takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?: text.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        }
        return text.takeIf { it.isNotBlank() }
    }

}
