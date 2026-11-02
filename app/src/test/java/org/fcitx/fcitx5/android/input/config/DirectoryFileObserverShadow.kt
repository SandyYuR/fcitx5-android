/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import android.os.FileObserver
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import java.io.File

/** Models Android's process-wide, single inotify watch descriptor per directory. */
@Implements(FileObserver::class)
class DirectoryFileObserverShadow {
    @RealObject private lateinit var observer: FileObserver
    private lateinit var directory: String
    private var mask = 0
    private var active = false

    @Implementation fun __constructor__(path: String, events: Int) {
        directory = File(path).canonicalPath
        mask = events
    }

    @Implementation fun startWatching() {
        synchronized(watches) {
            if (active) return
            active = true
            starts[directory] = (starts[directory] ?: 0) + 1
            // A second FileObserver replaces the first callback, just as on Android.
            watches[directory] = this
        }
    }

    @Implementation fun stopWatching() {
        synchronized(watches) {
            // Stopping either observer removes the shared native watch.
            if (active) watches.remove(directory)
            active = false
        }
    }

    companion object {
        private val watches = mutableMapOf<String, DirectoryFileObserverShadow>()
        private val starts = mutableMapOf<String, Int>()

        fun emit(directory: File, event: Int, path: String?) {
            val watch = synchronized(watches) { watches[directory.canonicalPath] }
            if (watch != null && event and watch.mask != 0) watch.observer.onEvent(event, path)
        }

        fun starts(directory: File): Int = synchronized(watches) { starts[directory.canonicalPath] ?: 0 }
        fun isWatching(directory: File): Boolean = synchronized(watches) { directory.canonicalPath in watches }

        @JvmStatic @Resetter fun reset() {
            synchronized(watches) { watches.clear(); starts.clear() }
        }
    }
}
