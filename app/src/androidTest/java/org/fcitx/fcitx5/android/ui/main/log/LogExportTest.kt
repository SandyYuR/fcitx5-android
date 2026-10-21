/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.fcitx.fcitx5.android.core.InteractiveThreadPriority
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LogExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    @Test fun realAdapterSnapshotSurvivesAppendsClearAndFilterChanges() {
        lateinit var adapter: LogAdapter
        lateinit var frozen: List<String>
        onMain {
            adapter = LogAdapter()
            adapter.append("RimeInputTiming first", "first")
            adapter.append("unrelated", "other")
            adapter.setFilterQuery("RimeInputTiming")
            frozen = adapter.snapshotLines()
        }
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        val writer = thread {
            try {
                repeat(20) {
                    val file = LogExportStore(application.cacheDir).prepare("header\n", frozen)
                    assertEquals("header\nRimeInputTiming first", file.readText())
                    file.delete()
                }
            } catch (e: Throwable) { failure = e }
            finally { completed.countDown() }
        }
        repeat(50) { index -> onMain {
            adapter.append("new $index", "new")
            adapter.clear()
            adapter.setFilterQuery("")
        } }
        assertTrue(completed.await(15, TimeUnit.SECONDS))
        writer.join()
        failure?.let { throw it }
        assertThrows(IllegalStateException::class.java) { adapter.snapshotLines() }
    }

    @Test fun pendingPickerSnapshotRestoresAndRepeatedTapsCannotStartAnotherExport() {
        lateinit var state: SavedStateHandle
        lateinit var model: LogExportViewModel
        onMain {
            state = SavedStateHandle()
            model = LogExportViewModel(application, state)
            model.prepare("header\n", listOf("original snapshot"))
            model.prepare("wrong\n", listOf("must not replace pending snapshot"))
        }
        await { model.state.value.phase == LogExportViewModel.Phase.READY }
        onMain {
            assertTrue(model.launchPicker())
            assertFalse(model.launchPicker())
            val restored = LogExportViewModel(application, SavedStateHandle(mapOf(
                "log_export_snapshot" to state.get<String>("log_export_snapshot"),
                "log_export_phase" to state.get<String>("log_export_phase")
            )))
            assertEquals(LogExportViewModel.Phase.PICKING, restored.state.value.phase)
            val file = LogExportStore(application.cacheDir).restore(state["log_export_snapshot"])!!
            assertEquals("header\noriginal snapshot", file.readText())
            restored.destination(null)
            assertEquals(LogExportViewModel.Phase.IDLE, restored.state.value.phase)
        }
    }

    @Test fun consecutiveRealOutputWritesCompleteBeforeSuccessIsReported() {
        lateinit var model: LogExportViewModel
        onMain { model = LogExportViewModel(application, SavedStateHandle()) }
        repeat(3) { index ->
            val output = File(application.cacheDir, "log-export-test-$index.txt")
            try {
                onMain { model.prepare("header $index\n", listOf("候选日志 $index")) }
                await { model.state.value.phase == LogExportViewModel.Phase.READY }
                onMain { assertTrue(model.launchPicker()); model.destination(Uri.fromFile(output)) }
                await { model.state.value.phase == LogExportViewModel.Phase.SUCCESS }
                assertEquals("header $index\n候选日志 $index", output.readText())
                onMain { model.consumeResult() }
            } finally { output.delete() }
        }
    }

    @Test fun logActivityCreatesSavedStateModelAndRetainsItAcrossRecreation() {
        val first = instrumentation.startActivitySync(Intent(application, LogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LogActivity
        var latest = first
        lateinit var original: LogExportViewModel
        try {
            onMain { original = ViewModelProvider(first)[LogExportViewModel::class.java]; first.recreate() }
            await {
                onMain {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<LogActivity>().firstOrNull { it !== first }?.let { latest = it }
                }
                latest !== first
            }
            onMain { assertSame(original, ViewModelProvider(latest)[LogExportViewModel::class.java]) }
        } finally { onMain { latest.finish() } }
    }

    @Test fun inputPriorityRestoresEvenWhenNativeWorkThrows() {
        var failure: Throwable? = null
        val worker = thread {
            try {
                val previous = Process.getThreadPriority(Process.myTid())
                val priority = InteractiveThreadPriority()
                assertThrows(IllegalStateException::class.java) {
                    priority.run {
                        assertEquals(minOf(previous, Process.THREAD_PRIORITY_DISPLAY), Process.getThreadPriority(Process.myTid()))
                        priority.run { assertEquals(Process.getThreadPriority(Process.myTid()), priority.lastPriority) }
                        throw IllegalStateException("simulated engine error")
                    }
                }
                assertEquals(previous, Process.getThreadPriority(Process.myTid()))
            } catch (e: Throwable) { failure = e }
        }
        worker.join()
        failure?.let { throw it }
    }
}
