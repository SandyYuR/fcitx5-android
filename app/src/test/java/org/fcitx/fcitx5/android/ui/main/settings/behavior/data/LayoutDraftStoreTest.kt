/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The layout editor's unsaved draft used to ride inside the saved-instance Bundle, which is
 * parcelled to system_server on every stop. A 537KB draft blew the ~1MB Binder budget and crashed
 * with TransactionTooLargeException, so the draft now lives in a file and only its name is
 * parcelled. These tests pin the store's contract.
 */
class LayoutDraftStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(dir: File = tempFolder.root.resolve("drafts")) = LayoutDraftStore(dir)

    /** The Bundle only ever carries a name, never the payload. */
    @Test
    fun writeReturnsNameAndKeepsPayloadOutOfIt() {
        val store = store()
        val json = """{"default":[[{"type":"AlphabetKey","main":"q"}]]}"""
        val name = store.write(json)
        assertNotNull("a writable dir yields a snapshot name", name)
        val safeName = name!!
        assertFalse("the name must not embed the draft", safeName.contains("AlphabetKey"))
        assertTrue("a file name, not a payload", safeName.length < 64)
        assertEquals(json, store.read())
    }

    /** The directory is created on demand: nothing exists before the first stop. */
    @Test
    fun writeCreatesDirectory() {
        val dir = tempFolder.root.resolve("nested").resolve("does").resolve("not").resolve("exist")
        assertFalse(dir.exists())
        val store = store(dir)
        assertNotNull(store.write("{}"))
        assertTrue(dir.isDirectory)
    }

    /** Repeated stops must reuse one file rather than pile up snapshots. */
    @Test
    fun repeatedWritesReuseOneFile() {
        val dir = tempFolder.root.resolve("drafts")
        val store = store(dir)
        val first = store.write("""{"a":1}""")
        val second = store.write("""{"a":2}""")
        val third = store.write("""{"a":3}""")
        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, dir.listFiles()!!.size)
        assertEquals("""{"a":3}""", store.read())
    }

    /** An unchanged draft is not rewritten; the same name still comes back. */
    @Test
    fun unchangedWriteIsSkippedButStillReportsTheName() {
        val dir = tempFolder.root.resolve("drafts")
        val store = store(dir)
        val json = """{"a":1}"""
        val name = store.write(json)!!
        // Overwrite behind the store's back: if the second write really is skipped, the sentinel
        // survives. This is a timestamp-free way to observe "no write happened".
        File(dir, name).writeText("sentinel")
        assertEquals(name, store.write(json))
        assertEquals("no rewrite for an identical draft", "sentinel", File(dir, name).readText())
    }

    /** A deleted file is rewritten even when the content hash matches. */
    @Test
    fun writeRecreatesADeletedSnapshot() {
        val dir = tempFolder.root.resolve("drafts")
        val store = store(dir)
        val json = """{"a":1}"""
        val name = store.write(json)!!
        assertTrue(File(dir, name).delete())
        assertEquals(name, store.write(json))
        assertTrue(File(dir, name).isFile)
    }

    /** delete() removes both the file and the reference, so read() reports nothing. */
    @Test
    fun deleteRemovesFileAndReference() {
        val dir = tempFolder.root.resolve("drafts")
        val store = store(dir)
        val name = store.write("""{"a":1}""")!!
        store.delete()
        assertNull(store.snapshotName)
        assertNull(store.read())
        assertFalse(File(dir, name).exists())
    }

    /** Without an adopted name there is nothing to read: the on-disk layout wins. */
    @Test
    fun readWithoutSnapshotIsNull() {
        assertNull(store().read())
    }

    /** A name from the Bundle whose file is gone (cleared cache) must not throw. */
    @Test
    fun readOfMissingFileIsNull() {
        val store = store()
        store.adopt("draft-does-not-exist.json")
        assertNull(store.read())
    }

    /** adopt() takes over the incoming name, so the next write reuses that file. */
    @Test
    fun adoptTakesOverAnExistingSnapshot() {
        val dir = tempFolder.root.resolve("drafts")
        dir.mkdirs()
        val existing = File(dir, "draft-existing.json").apply { writeText("""{"a":1}""") }
        val store = store(dir)
        store.adopt(existing.name)
        assertEquals("""{"a":1}""", store.read())
        assertEquals(existing.name, store.write("""{"a":2}"""))
        assertEquals(1, dir.listFiles()!!.size)
    }

    /**
     * adopt() must not trust a stale content hash: the same JSON as the previous instance still
     * has to be written, because the adopted file may hold something else.
     */
    @Test
    fun adoptResetsTheSkipWriteHash() {
        val dir = tempFolder.root.resolve("drafts")
        val store = store(dir)
        val json = """{"a":1}"""
        val name = store.write(json)!!
        File(dir, name).writeText("clobbered by something else")
        store.adopt(name)
        assertEquals(name, store.write(json))
        assertEquals("the adopted file was rewritten", json, File(dir, name).readText())
    }

    /** Snapshots of processes that were killed outright are eventually reclaimed. */
    @Test
    fun pruneStaleDeletesUnreachableSnapshots() {
        val dir = tempFolder.root.resolve("drafts")
        dir.mkdirs()
        val orphan = File(dir, "draft-orphan.json").apply { writeText("{}") }
        val other = File(dir, "draft-other.json").apply { writeText("{}") }
        // Look at the directory from far enough in the future that both files are past maxAge,
        // instead of back-dating them: setLastModified is not reliable on every filesystem.
        val future = System.currentTimeMillis() + 10 * MAX_AGE
        assertEquals(2, store(dir).pruneStale(MAX_AGE, future))
        assertFalse(orphan.exists())
        assertFalse(other.exists())
    }

    /** Snapshots that are still young enough to be referenced are left alone. */
    @Test
    fun pruneStaleKeepsFreshSnapshots() {
        val dir = tempFolder.root.resolve("drafts")
        dir.mkdirs()
        val fresh = File(dir, "draft-fresh.json").apply { writeText("{}") }
        assertEquals(0, store(dir).pruneStale(MAX_AGE))
        assertTrue(fresh.exists())
    }

    /** The owned snapshot survives pruning however old it looks. */
    @Test
    fun pruneStaleKeepsTheOwnedSnapshot() {
        val dir = tempFolder.root.resolve("drafts")
        val store = store(dir)
        val name = store.write("{}")!!
        val future = System.currentTimeMillis() + 10 * MAX_AGE
        assertEquals(0, store.pruneStale(MAX_AGE, future))
        assertTrue(File(dir, name).exists())
        assertEquals("{}", store.read())
    }

    /** Pruning a directory that was never created is a no-op, not a failure. */
    @Test
    fun pruneStaleOnMissingDirectoryIsNoOp() {
        assertEquals(0, store(tempFolder.root.resolve("never-created")).pruneStale(MAX_AGE))
    }

    /**
     * The inline fallback threshold has to stay well under the Binder budget: the crash in the
     * report was a 540248-byte parcel.
     */
    @Test
    fun inlineFallbackOnlyAcceptsSmallDrafts() {
        assertTrue(LayoutDraftStore.fitsInBundle("x".repeat(LayoutDraftStore.INLINE_MAX_CHARS)))
        assertFalse(LayoutDraftStore.fitsInBundle("x".repeat(LayoutDraftStore.INLINE_MAX_CHARS + 1)))
        // 2 bytes per char when parcelled, and the observed crash was ~540KB.
        assertTrue(LayoutDraftStore.INLINE_MAX_CHARS * 2 < 128 * 1024)
    }

    private companion object {
        /**
         * Long enough that a freshly written file is never accidentally "stale" on a slow
         * machine or a filesystem with coarse mtime granularity, and short enough that
         * "now + 10 * MAX_AGE" stays a plain small number.
         */
        const val MAX_AGE = 60_000L
    }
}
