/* SPDX-License-Identifier: LGPL-2.1-or-later */
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DataDescriptorPathTest {
    private val root = File("/assets")

    @Test fun descriptorKeysAlwaysUseForwardSlashes() {
        val nested = File(File(File(root, "usr"), "share"), "rime-data")
        assertEquals("usr/share/rime-data", descriptorKey(nested, root))
        assertFalse(descriptorKey(nested, root).contains('\\'))
    }

    @Test fun parentDirectoriesAreFoundByNormalizedKey() {
        val parent = File(File(root, "usr"), "share")
        val key = descriptorKey(parent, root)
        assertEquals("usr/share", key)
        assertEquals(descriptorKey(File(parent, "rime-data"), root), "$key/rime-data")
    }

    @Test fun alreadyRelativeFilesAreTheirOwnKey() {
        // parent chain of a relativeTo() result is relative to the root already
        val relativeParent = File(File(File("usr"), "share"), "opencc")
        assertEquals("usr/share/opencc", descriptorKey(relativeParent, root))
    }

    @Test fun backslashPathsAreRejectedBeforeReachingTheRuntime() {
        assertThrows(IllegalStateException::class.java) {
            requireDescriptorPaths(listOf("usr/share", "usr\\share"))
        }
    }

    @Test fun malformedPathsAreRejected() {
        for (invalid in listOf("", "/abs/path", "usr//share", "usr/./share", "usr/../share")) {
            assertThrows(invalid, IllegalStateException::class.java) { requireDescriptorPaths(listOf(invalid)) }
        }
    }

    @Test fun plainRelativePathsPassTheGuard() {
        requireDescriptorPaths(listOf("README.md", "usr", "usr/share/opencc/s2t.json"))
    }
}
