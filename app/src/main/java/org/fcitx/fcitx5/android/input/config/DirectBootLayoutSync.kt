/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Watches unlocked storage independently of keyboard visibility and editor file getters. */
internal class DirectBootLayoutSync(
    private val context: Context,
    private val profile: ManagedPreference.PString,
    destination: File,
    private val initialRetryMillis: Long = 1_000L
) : Closeable {
    init {
        require(initialRetryMillis in 1..30_000L)
    }

    private val store = DirectBootLayoutStore(destination)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val rewatch = AtomicBoolean(false)
    private val observers = DirectoryObservers.shared
    private var rootWatcher: DirectoryObserverRegistry.Subscription? = null
    private var configWatcher: DirectoryObserverRegistry.Subscription? = null
    private var watchedRoot: File? = null
    @Volatile private var selectedFileName: String? = resolveSelectedFileName()
    private val profileListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        selectedFileName = resolveSelectedFileName()
        requestSync()
    }

    private val worker = scope.launch {
        var retryMillis: Long? = null
        try {
            while (isActive) {
                val timeout = retryMillis
                if (timeout == null) changes.receive()
                else withTimeoutOrNull(timeout) { changes.receive() }
                val synced = try {
                    sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to sync Direct Boot keyboard layout")
                    false
                }
                retryMillis = if (synced) null
                    else if (timeout == null) initialRetryMillis
                    else (timeout * 2).coerceAtMost(30_000L)
            }
        } finally {
            releaseWatches()
        }
    }

    init {
        profile.registerOnChangeListener(profileListener)
        requestSync()
    }

    private fun resolveSelectedFileName(): String? =
        UserConfigFiles.normalizeTextKeyboardLayoutProfile(profile.getValue())
            ?.let(UserConfigFiles::textKeyboardLayoutFileName)

    fun requestSync() {
        changes.trySend(Unit)
    }

    private fun releaseWatches() {
        rootWatcher?.close()
        configWatcher?.close()
        rootWatcher = null
        configWatcher = null
        watchedRoot = null
    }

    private fun sync(): Boolean {
        val root = context.getExternalFilesDir(null)?.canonicalFile
        if (root == null || !root.isDirectory) {
            releaseWatches()
            rewatch.set(true)
            return false
        }
        if (watchedRoot != null && watchedRoot != root) {
            releaseWatches()
            rewatch.set(true)
        }
        if (rootWatcher == null) {
            rootWatcher = observers.subscribe(root, ROOT_EVENTS) { event, path ->
                if (path == "config" || event and SELF_EVENTS != 0) {
                    rewatch.set(true)
                    requestSync()
                }
            }
            watchedRoot = root
        } else if (rootWatcher?.isWatching != true) {
            observers.refresh(root)
        }
        if (rootWatcher?.isWatching != true) return false

        val config = File(root, "config")
        if (rewatch.getAndSet(false)) observers.refresh(config)
        if (configWatcher == null) {
            configWatcher = observers.subscribe(config, LAYOUT_EVENTS) { event, path ->
                if (event and SELF_EVENTS != 0) {
                    rewatch.set(true)
                    requestSync()
                } else if (event == 0 || path == selectedFileName) {
                    requestSync()
                }
            }
        } else if (config.isDirectory && configWatcher?.isWatching != true) {
            observers.refresh(config)
        }
        selectedFileName = resolveSelectedFileName()
        val name = selectedFileName
        if (name == null) store.clear()
        else store.syncFrom(File(config, name))
        // The parent watch handles creation; do not mkdir and race a directory restore.
        return !config.isDirectory || configWatcher?.isWatching == true
    }

    override fun close() {
        profile.unregisterOnChangeListener(profileListener)
        scope.cancel()
        changes.close()
    }

    internal suspend fun join() = worker.join()

    companion object {
        private const val SELF_EVENTS = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        private const val ROOT_EVENTS = FileObserver.CREATE or FileObserver.MOVED_TO or
            FileObserver.DELETE or FileObserver.MOVED_FROM or SELF_EVENTS
        private const val LAYOUT_EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.DELETE or SELF_EVENTS
    }
}
