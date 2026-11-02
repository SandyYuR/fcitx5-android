/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import android.os.FileObserver
import timber.log.Timber
import java.io.Closeable
import java.io.File

/** Android shares inotify watch descriptors, so each directory must have only one FileObserver. */
internal object DirectoryObservers {
    val shared = DirectoryObserverRegistry()
}

internal class DirectoryObserverRegistry(
    private val startObserver: (File, (Int, String?) -> Unit) -> Closeable = { directory, callback ->
        @Suppress("DEPRECATION") // String paths also support Android 7 and 8.
        val observer = object : FileObserver(directory.path, EVENTS) {
            override fun onEvent(event: Int, path: String?) = callback(event, path)
        }
        observer.startWatching()
        Closeable { observer.stopWatching() }
    }
) {
    private class Listener(val mask: Int, val callback: (Int, String?) -> Unit) {
        @Volatile var active = true
    }

    private class Entry(val directory: File) {
        val listeners = linkedSetOf<Listener>()
        var observer: Closeable? = null
        var generation = 0L
    }

    internal class Subscription(
        private val watching: () -> Boolean,
        private val unsubscribe: () -> Unit
    ) : Closeable {
        val isWatching: Boolean get() = watching()
        override fun close() = unsubscribe()
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun subscribe(directory: File, mask: Int, callback: (Int, String?) -> Unit): Subscription {
        val canonical = directory.canonicalFile
        val listener = Listener(mask, callback)
        val entry = synchronized(lock) {
            val entry = entries.getOrPut(canonical.path) { Entry(canonical) }
            entry.listeners.add(listener)
            try {
                startLocked(entry)
            } catch (e: Exception) {
                entry.listeners.remove(listener)
                if (entry.listeners.isEmpty()) entries.remove(canonical.path)
                throw e
            }
            entry
        }
        return Subscription(
            watching = { synchronized(lock) { listener.active && entry.observer != null } },
            unsubscribe = {
                synchronized(lock) {
                    if (listener.active) {
                        listener.active = false
                        entry.listeners.remove(listener)
                        if (entry.listeners.isEmpty()) {
                            stopLocked(entry)
                            entries.remove(canonical.path)
                        }
                    }
                }
            }
        )
    }

    /** Reattach after directory replacement. Event zero asks every subscriber to rescan. */
    fun refresh(directory: File) {
        val callbacks = synchronized(lock) {
            val entry = entries[directory.canonicalPath] ?: return
            stopLocked(entry)
            startLocked(entry)
            entry.listeners.toList()
        }
        dispatch(callbacks, 0, null)
    }

    private fun startLocked(entry: Entry) {
        if (entry.observer != null || !entry.directory.isDirectory) return
        val generation = ++entry.generation
        entry.observer = startObserver(entry.directory) callback@ { event, path ->
            val callbacks = synchronized(lock) {
                if (entry.generation != generation) return@callback
                if (event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
                    stopLocked(entry)
                }
                entry.listeners.toList()
            }
            // Never call subscribers while holding the registry lock.
            dispatch(callbacks, event, path)
        }
    }

    private fun stopLocked(entry: Entry) {
        ++entry.generation
        entry.observer?.close()
        entry.observer = null
    }

    private fun dispatch(listeners: List<Listener>, event: Int, path: String?) {
        listeners.forEach { listener ->
            if (listener.active && (event == 0 || event and listener.mask != 0)) {
                try {
                    listener.callback(event, path)
                } catch (e: Exception) {
                    Timber.w(e, "Directory observer subscriber failed")
                }
            }
        }
    }

    companion object {
        const val EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.CREATE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}
