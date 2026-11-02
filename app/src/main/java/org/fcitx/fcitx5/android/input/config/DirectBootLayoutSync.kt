/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Watches unlocked storage independently of keyboard visibility and editor file getters. */
internal class DirectBootLayoutSync(
    private val context: Context,
    private val profile: ManagedPreference.PString,
    destination: File
) : Closeable {
    private val store = DirectBootLayoutStore(destination)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val rewatch = AtomicBoolean(true)
    private var rootWatcher: FileObserver? = null
    private var configWatcher: FileObserver? = null
    private val profileListener = ManagedPreference.OnChangeListener<String> { _, _ -> requestSync() }

    private val worker = scope.launch {
        try {
            for (ignored in changes) {
                try {
                    sync()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to sync Direct Boot keyboard layout")
                }
            }
        } finally {
            rootWatcher?.stopWatching()
            configWatcher?.stopWatching()
        }
    }

    init {
        profile.registerOnChangeListener(profileListener)
        requestSync()
    }

    fun requestSync() {
        changes.trySend(Unit)
    }

    @Suppress("DEPRECATION") // String paths also support Android 7 and 8.
    private fun sync() {
        val root = context.getExternalFilesDir(null) ?: return
        val config = File(root, "config")
        if (rootWatcher == null) {
            rootWatcher = object : FileObserver(root.path, CREATE or MOVED_TO or DELETE or MOVED_FROM) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "config") {
                        rewatch.set(true)
                        requestSync()
                    }
                }
            }.also { it.startWatching() }
        }
        if (rewatch.getAndSet(false) || configWatcher == null) {
            configWatcher?.stopWatching()
            configWatcher = null
            if (!config.isDirectory && !config.mkdirs()) {
                throw IOException("Cannot watch keyboard config directory")
            }
            configWatcher = object : FileObserver(config.path,
                CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or DELETE_SELF or MOVE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                        rewatch.set(true)
                        requestSync()
                    } else if (path?.startsWith("TextKeyboardLayout") == true && path.endsWith(".json")) {
                        requestSync()
                    }
                }
            }.also { it.startWatching() }
        }
        val selected = UserConfigFiles.normalizeTextKeyboardLayoutProfile(profile.getValue())
        if (selected == null) store.clear()
        else store.syncFrom(File(config, UserConfigFiles.textKeyboardLayoutFileName(selected)))
    }

    override fun close() {
        profile.unregisterOnChangeListener(profileListener)
        changes.close()
        scope.cancel()
    }

    internal suspend fun join() = worker.join()
}
