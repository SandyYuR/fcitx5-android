/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import android.os.FileObserver
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File

class DirectoryObserverRegistryTest {
    @get:Rule val temp = TemporaryFolder()
    private val callbacks = mutableListOf<(Int, String?) -> Unit>()
    private var stops = 0
    private val registry = DirectoryObserverRegistry { _, callback ->
        callbacks.add(callback)
        Closeable { stops++ }
    }

    @Test fun canonicalAliasesShareOneObserverUntilTheLastSubscriptionCloses() {
        val seen = mutableListOf<String>()
        val first = registry.subscribe(temp.root, FileObserver.MODIFY) { _, _ -> seen.add("first") }
        val second = registry.subscribe(File(temp.root, "."), FileObserver.MODIFY) { _, _ -> seen.add("second") }
        assertEquals(1, callbacks.size)
        callbacks.single()(FileObserver.MODIFY, "layout.json")
        assertEquals(listOf("first", "second"), seen)
        first.close()
        first.close()
        assertEquals(0, stops)
        assertFalse(first.isWatching)
        assertTrue(second.isWatching)
        callbacks.single()(FileObserver.MODIFY, "layout.json")
        assertEquals(listOf("first", "second", "second"), seen)
        second.close()
        assertEquals(1, stops)
    }

    @Test fun eachSubscriberReceivesItsOwnEventMask() {
        val seen = mutableListOf<String>()
        registry.subscribe(temp.root, FileObserver.MODIFY) { _, _ -> seen.add("editing") }
        registry.subscribe(temp.root, FileObserver.CLOSE_WRITE) { _, _ -> seen.add("completed") }
        callbacks.single()(FileObserver.MODIFY, "layout.json")
        assertEquals(listOf("editing"), seen)
        callbacks.single()(FileObserver.CLOSE_WRITE, "layout.json")
        assertEquals(listOf("editing", "completed"), seen)
        assertEquals(1, callbacks.size)
    }

    @Test fun replacementRefreshesAllSubscribersAndRejectsOldObserverEvents() {
        val seen = mutableListOf<Int>()
        val first = registry.subscribe(temp.root, FileObserver.CLOSE_WRITE) { event, _ -> seen.add(event) }
        val second = registry.subscribe(temp.root, FileObserver.MODIFY) { event, _ -> seen.add(event) }
        val stale = callbacks.single()
        registry.refresh(temp.root)
        assertEquals(listOf(0, 0), seen)
        assertEquals(1, stops)
        assertEquals(2, callbacks.size)
        stale(FileObserver.CLOSE_WRITE, "layout.json")
        assertEquals(listOf(0, 0), seen)
        callbacks.last()(FileObserver.CLOSE_WRITE, "layout.json")
        assertEquals(listOf(0, 0, FileObserver.CLOSE_WRITE), seen)
        assertTrue(first.isWatching)
        assertTrue(second.isWatching)
    }

    @Test fun missingDirectoryCanBeSubscribedBeforeItIsCreated() {
        val directory = File(temp.root, "config")
        val subscription = registry.subscribe(directory, FileObserver.CLOSE_WRITE) { _, _ -> }
        assertFalse(subscription.isWatching)
        assertEquals(0, callbacks.size)
        assertTrue(directory.mkdir())
        registry.refresh(directory)
        assertTrue(subscription.isWatching)
        assertEquals(1, callbacks.size)
        subscription.close()
        assertEquals(1, stops)
    }

    @Test fun directorySelfRemovalInvalidatesTheSharedWatchUntilRefresh() {
        var invalidations = 0
        val first = registry.subscribe(temp.root, FileObserver.DELETE_SELF) { _, _ -> invalidations++ }
        val second = registry.subscribe(temp.root, FileObserver.DELETE_SELF) { _, _ -> invalidations++ }
        callbacks.single()(FileObserver.DELETE_SELF, null)
        assertEquals(2, invalidations)
        assertFalse(first.isWatching)
        assertFalse(second.isWatching)
        assertEquals(1, stops)
        registry.refresh(temp.root)
        assertTrue(first.isWatching)
        assertTrue(second.isWatching)
        assertEquals(2, callbacks.size)
    }
}
