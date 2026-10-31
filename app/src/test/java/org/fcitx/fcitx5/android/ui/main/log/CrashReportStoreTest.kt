/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CrashReportStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aFreshProcessCanReadThePreviousCrash() {
        val report = "Crash build: fixture\njava.lang.IllegalStateException: fixture\n at example.ThemePage.open()\n"
        assertTrue(CrashReportStore(temp.root).record(report, 1000))
        assertEquals(report, CrashReportStore(temp.root).read(2000))
    }

    @Test fun failedReplacementPreservesThePreviousCompleteReport() {
        assertTrue(CrashReportStore(temp.root).record("old"))
        assertFalse(CrashReportStore(temp.root) { _, _ -> false }.record("new"))
        assertEquals("old", CrashReportStore(temp.root).read())
        assertEquals(listOf("last-app-crash.txt"), File(temp.root, "crash-reports").listFiles()!!.map { it.name })
    }

    @Test fun reportsAreBoundedAndExpireAndCanBeCleared() {
        val store = CrashReportStore(temp.root)
        assertTrue(store.record("崩".repeat(CrashReportStore.MAX_CHARS + 10), 1000))
        assertEquals(CrashReportStore.MAX_CHARS, store.read(1001)!!.length)
        assertNull(store.read(1001 + CrashReportStore.RETENTION_MS))
        assertTrue(store.record("next")); store.delete(); assertNull(store.read())
    }

    @Test fun aMissingOrUnwritableStoreDoesNotThrowAndSymbolicLinksAreRejected() {
        assertNull(CrashReportStore(temp.root).read())
        val obstacle = temp.newFile("obstacle")
        assertFalse(CrashReportStore(obstacle).record("test"))
        val outside = temp.newFolder("outside")
        val base = temp.newFolder("base")
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(base, "crash-reports").toPath(), outside.toPath())
        assertFalse(CrashReportStore(base).record("private trace"))
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun oldReportsRemainInExportsAfterReturningToAnOrdinaryFilteredLogPage() {
        val report = "--------- Persisted app crash\nCrash build: previous\njava.lang.IllegalStateException: fixture"
        assertTrue(CrashReportStore(temp.root).record(report))
        val export = LogExportStore(temp.root) { CrashReportStore(temp.root).read() }
            .prepare("Current build: next\n", listOf("only matching current log line"))
        val text = export.readText()
        assertTrue(text.contains(report)); assertTrue(text.contains("Current build: next"))
        assertTrue(text.endsWith("only matching current log line"))
    }

    @Test fun aMissingCrashReportCannotPreventOrdinaryLogExport() {
        val export = LogExportStore(temp.root) { error("report unavailable") }.prepare("header\n", listOf("log"))
        assertEquals("header\nlog", export.readText())
    }
}
