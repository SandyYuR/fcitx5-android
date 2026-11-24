/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LogExportStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val store get() = LogExportStore(temp.root)

    @Test fun repeatedExportsUseFrozenSnapshotsWhileLiveLogsChange() {
        val live = arrayListOf("候选已提交", "second line")
        val frozen = live.toList()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writes = executor.submit<List<String>> {
                (1..20).map {
                    val file = store.prepare("header\n", frozen)
                    val output = ByteArrayOutputStream()
                    store.copy(file) { output }
                    file.delete()
                    output.toString(Charsets.UTF_8.name())
                }
            }
            repeat(5_000) { live.clear(); live.add("new $it") }
            assertTrue(writes.get(10, TimeUnit.SECONDS).all { it == "header\n候选已提交\nsecond line" })
        } finally { executor.shutdownNow() }
    }

    @Test fun providerFailureClosesOutputAndLeavesCompleteSnapshotForRecovery() {
        val file = store.prepare("header\n", listOf("完整日志"))
        var closed = false
        val broken = object : OutputStream() {
            override fun write(value: Int) { throw IOException("provider disconnected") }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { store.copy(file) { broken } }
        assertTrue(closed)
        val retry = ByteArrayOutputStream()
        store.copy(file) { retry }
        assertEquals(file.readText(), retry.toString(Charsets.UTF_8.name()))
    }

    @Test fun missingSourceDoesNotOpenDestinationAndNullOutputIsReported() {
        val file = store.prepare("header\n", listOf("line"))
        assertThrows(IOException::class.java) { store.copy(file) { null } }
        file.delete()
        var opened = false
        assertThrows(IOException::class.java) { store.copy(file) { opened = true; ByteArrayOutputStream() } }
        assertFalse(opened)
    }

    @Test fun shareAndFileExportRenderTheSameTextIncludingTheCrashReport() {
        // 分享与导出必须同源：崩溃后重开普通日志页再分享时，保留的崩溃报告是唯一的现场记录
        val report = "--------- Persisted app crash\njava.lang.IllegalStateException: fixture"
        val withCrash = LogExportStore(temp.root) { report }
        val rendered = withCrash.render("header\n", listOf("first", "second"))
        assertEquals("header\n\n$report\nfirst\nsecond", rendered)
        assertEquals(rendered, withCrash.prepare("header\n", listOf("first", "second")).readText())
    }

    @Test fun aMissingCrashReportRendersOnlyHeaderAndLines() {
        assertEquals("header\nline", store.render("header\n", listOf("line")))
        assertEquals("header\nline", LogExportStore(temp.root) { error("unavailable") }
            .render("header\n", listOf("line")))
    }

    @Test fun onlyNonemptyPrivateSnapshotsCanBeRestoredAfterRecreation() {
        val file = store.prepare("header\n", listOf("line"))
        assertEquals(file, store.restore(file.name))
        assertNull(store.restore("../${file.name}"))
        assertNull(store.restore(file.absolutePath))
        file.writeText("")
        assertNull(store.restore(file.name))
    }
}
