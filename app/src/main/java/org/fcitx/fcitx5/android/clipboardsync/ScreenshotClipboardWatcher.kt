package org.fcitx.fcitx5.android.clipboardsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager as FcitxClipboardManager

class ScreenshotClipboardWatcher(
    private val context: Context,
    private val clipboardManager: ClipboardManager,
    private val onScreenshotClipboardUri: (String) -> Unit
) {
    companion object {
        private const val TAG = "FcitxScreenshotWatcher"
        private const val QUERY_LIMIT = 5
        private const val RETRY_DELAY_MS = 1200L
        private const val POLL_INTERVAL_MS = 5000L
        private const val PREF_HANDLED_SCREENSHOTS = "screenshot_sync_handled_items"
        private const val MAX_HANDLED_SCREENSHOTS = 256
        private const val SCREENSHOT_RECENT_WINDOW_SECONDS = 120L
        private val SCREENSHOT_MARKERS = listOf(
            "screenshots",
            "screenshot",
            "screen_shot",
            "screen-shot",
            "截屏",
            "截图"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var registered = false
    private var startedAtSeconds = 0L
    private val handledScreenshotKeys = linkedSetOf<String>()
    private var handledKeysLoaded = false
    private var pendingQuery = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            scheduleQuery()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleQuery()
        }
    }

    fun start() {
        if (registered) return
        if (!hasImageReadPermission()) {
            Log.w(TAG, "Screenshot watcher not started: image read permission is missing")
            return
        }
        startedAtSeconds = System.currentTimeMillis() / 1000
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            registered = true
            Log.d(TAG, "Screenshot clipboard watcher registered")
            schedulePeriodicQuery()
        }.onFailure {
            Log.w(TAG, "Failed to register screenshot watcher", it)
        }
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacksAndMessages(null)
        runCatching {
            context.contentResolver.unregisterContentObserver(observer)
        }.onFailure {
            Log.w(TAG, "Failed to unregister screenshot watcher", it)
        }
        registered = false
        pendingQuery = false
    }

    private fun scheduleQuery() {
        if (!registered || pendingQuery) return
        pendingQuery = true
        handler.postDelayed({
            pendingQuery = false
            queryLatestScreenshot()?.let(::copyScreenshotToClipboard)
                ?: handler.postDelayed({
                    queryLatestScreenshot()?.let(::copyScreenshotToClipboard)
                }, RETRY_DELAY_MS)
        }, 500L)
    }

    private fun schedulePeriodicQuery() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!registered) return
                queryLatestScreenshot()?.let(::copyScreenshotToClipboard)
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }, POLL_INTERVAL_MS)
    }

    private fun queryLatestScreenshot(): ScreenshotCandidate? {
        if (!hasImageReadPermission()) return null
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
            }
        }.toTypedArray()
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                val pendingIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                } else {
                    -1
                }

                var checked = 0
                while (cursor.moveToNext() && checked < QUERY_LIMIT) {
                    checked += 1
                    if (pendingIndex >= 0 && cursor.getInt(pendingIndex) != 0) continue
                    val mimeType = cursor.getString(mimeIndex).orEmpty()
                    if (!mimeType.startsWith("image/")) continue

                    val dateAdded = cursor.getLong(addedIndex)
                    val dateModified = cursor.getLong(modifiedIndex)
                    val dateTakenSeconds = cursor.getLong(takenIndex).takeIf { it > 0L }?.div(1000L) ?: 0L
                    val eventTime = maxOf(dateAdded, dateModified, dateTakenSeconds)
                    if (eventTime + 3 < startedAtSeconds) continue

                    val displayName = cursor.getString(nameIndex).orEmpty()
                    val bucket = cursor.getString(bucketIndex).orEmpty()
                    val relativePath = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                    val looksLikeScreenshot = looksLikeScreenshot(displayName, bucket, relativePath)
                    if (!looksLikeScreenshot) continue
                    if (eventTime < System.currentTimeMillis() / 1000 - SCREENSHOT_RECENT_WINDOW_SECONDS) continue

                    val mediaId = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        mediaId
                    )
                    val key = screenshotKey(mediaId, displayName, dateAdded)
                    if (isHandled(key)) continue
                    return@use ScreenshotCandidate(uri, key)
                }
                null
            }
        }.onFailure {
            Log.w(TAG, "Failed to query latest screenshot", it)
        }.getOrNull()
    }

    private fun copyScreenshotToClipboard(candidate: ScreenshotCandidate) {
        val uri = candidate.uri
        runCatching {
            context.contentResolver.openInputStream(uri)?.close()
            val mimeType = context.contentResolver.getType(uri) ?: "image/*"
            scope.launch {
                FcitxClipboardManager.importLocalEntry(
                    text = uri.toString(),
                    type = mimeType,
                    timestamp = System.currentTimeMillis(),
                    notifyListeners = true
                )
            }
            clipboardManager.setPrimaryClip(
                ClipData.newUri(context.contentResolver, "Screenshot", uri)
            )
            markHandled(candidate.key)
            onScreenshotClipboardUri(uri.toString())
            Log.d(TAG, "Copied screenshot URI to clipboard: $uri")
        }.onFailure {
            Log.w(TAG, "Failed to copy screenshot URI to clipboard: $uri", it)
        }
    }

    private fun screenshotKey(
        mediaId: Long,
        displayName: String,
        dateAdded: Long
    ): String {
        return "$mediaId|$displayName|$dateAdded"
    }

    private fun isHandled(key: String): Boolean {
        ensureHandledKeysLoaded()
        return key in handledScreenshotKeys
    }

    private fun markHandled(key: String) {
        ensureHandledKeysLoaded()
        handledScreenshotKeys.remove(key)
        handledScreenshotKeys.add(key)
        while (handledScreenshotKeys.size > MAX_HANDLED_SCREENSHOTS) {
            val first = handledScreenshotKeys.firstOrNull() ?: break
            handledScreenshotKeys.remove(first)
        }
        prefs.edit()
            .putStringSet(PREF_HANDLED_SCREENSHOTS, handledScreenshotKeys.toSet())
            .apply()
    }

    private fun ensureHandledKeysLoaded() {
        if (handledKeysLoaded) return
        handledScreenshotKeys.clear()
        handledScreenshotKeys.addAll(prefs.getStringSet(PREF_HANDLED_SCREENSHOTS, emptySet()).orEmpty())
        handledKeysLoaded = true
    }

    private fun looksLikeScreenshot(displayName: String, bucket: String, relativePath: String): Boolean {
        val haystack = "$displayName/$bucket/$relativePath".lowercase()
        return SCREENSHOT_MARKERS.any { marker -> marker in haystack }
    }

    private fun hasImageReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private data class ScreenshotCandidate(
        val uri: Uri,
        val key: String
    )
}
