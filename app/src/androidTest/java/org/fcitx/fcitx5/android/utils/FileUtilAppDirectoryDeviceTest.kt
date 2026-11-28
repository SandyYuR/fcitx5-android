/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses only a new UUID directory under the actual Context.cacheDir; no settings or IME changes. */
class FileUtilAppDirectoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withOwnedCacheDirectory(block: (File, MutableList<File>) -> Unit) {
        val cache = context.cacheDir
        val root = File(cache, "atomic-app-alias-${UUID.randomUUID()}")
        assertEquals(cache.canonicalFile, root.parentFile!!.canonicalFile)
        assertFalse(root.exists())
        assertTrue(root.mkdir())
        val owned = mutableListOf(root)
        var failure: Throwable? = null
        try {
            block(root, owned)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = AssertionError("Could not remove test-owned cache fixtures")
            owned.asReversed().forEach { file ->
                try {
                    // delete() unlinks a leaf link; never recursively follow a fixture link.
                    check(file.delete() || (!file.exists() && file.parentFile?.list()?.contains(file.name) != true)) {
                        "Cannot remove test fixture: $file"
                    }
                } catch (error: Throwable) { cleanup.addSuppressed(error) }
            }
            if (cleanup.suppressed.isNotEmpty()) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    @Test fun actualContextCachePathSupportsAtomicReplacementAndFailureKeepsTheOldFile() =
        withOwnedCacheDirectory { directory, owned ->
            val target = File(directory, "value").also { owned += it }
            val temporary = File(directory, "value.tmp").also { owned += it }
            val appRoot = File(context.applicationInfo.dataDir).canonicalFile
            assertTrue(directory.canonicalPath.startsWith(appRoot.path + File.separator))
            FileUtil.writeAtomically(target, "original")
            assertEquals("original", target.readText())
            FileUtil.writeAtomically(target, "complete")
            assertEquals("complete", target.readText())
            try {
                FileUtil.writeAtomically(target) { it.write(1); throw IOException("fixture write failure") }
                fail("A failed writer must propagate its failure")
            } catch (expected: IOException) {
                assertEquals("fixture write failure", expected.message)
            }
            assertEquals("complete", target.readText())
            assertFalse(temporary.exists())
        }

    @Test fun trustedApplicationRootStillRejectsTargetTemporaryAndAncestorLinks() =
        withOwnedCacheDirectory { directory, owned ->
            val safe = File(directory, "safe").also { owned += it; assertTrue(it.mkdir()) }
            val keep = File(safe, "keep").also { owned += it; it.writeText("original") }
            val target = File(directory, "linked-target").also { owned += it }
            val targetTemporary = File(directory, "linked-target.tmp").also { owned += it }
            Os.symlink(keep.path, target.path)
            assertTrue(runCatching { FileUtil.writeAtomically(target, "new") }.isFailure)
            assertEquals("original", keep.readText())
            assertFalse(targetTemporary.exists())

            val ordinary = File(directory, "ordinary").also { owned += it; it.writeText("previous") }
            val temporary = File(directory, "ordinary.tmp").also { owned += it }
            Os.symlink(keep.path, temporary.path)
            assertTrue(runCatching { FileUtil.writeAtomically(ordinary, "new") }.isFailure)
            assertEquals("previous", ordinary.readText())
            assertEquals("original", keep.readText())

            val redirect = File(directory, "redirect").also { owned += it }
            Os.symlink(safe.path, redirect.path)
            val forbiddenDirectory = File(safe, "must-not-create").also { owned += it }
            owned += File(forbiddenDirectory, "value")
            owned += File(forbiddenDirectory, "value.tmp")
            assertTrue(runCatching {
                FileUtil.writeAtomically(File(redirect, "must-not-create/value"), "new")
            }.isFailure)
            assertFalse(forbiddenDirectory.exists())
            assertEquals("original", keep.readText())
            assertEquals(keep.path, Os.readlink(target.path))
            assertEquals(keep.path, Os.readlink(temporary.path))
            assertEquals(safe.path, Os.readlink(redirect.path))
        }
}
