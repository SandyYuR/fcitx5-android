/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeveloperHeapDumpStateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recreatedExportResolvesTheOriginalPrivateDump() {
        val cache = temporary.newFolder("cache")
        val dump = java.io.File(cache, "app_2026-11-27.hprof").apply { writeText("heap") }
        assertEquals(dump, DeveloperFragment.pendingHeapDump(cache, dump.name))
        assertEquals("heap", DeveloperFragment.pendingHeapDump(cache, dump.name)!!.readText())
    }

    @Test fun missingOrForeignIdentityDoesNotAcquireAFileForCleanup() {
        val cache = temporary.newFolder("cache")
        val outside = temporary.newFile("outside.hprof").apply { writeText("preserve") }
        for (name in listOf(null, "", "missing.hprof", "../outside.hprof", outside.absolutePath, "not-a-dump.txt")) {
            assertNull(DeveloperFragment.pendingHeapDump(cache, name))
        }
        assertEquals("preserve", outside.readText())
    }
}
