/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import android.app.Application
import android.content.Intent
import android.widget.ImageButton
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 清空必须真正落地：列表里的行、导出拿到的行、以及旋转重建后重新贴回来的行，都只能来自清空之后。
 *
 * 两个审阅问题都落在这条边界上：
 *  - 清空没有完成信号时，刷新会读到还没删掉的持久历史，把刚清掉的行重新发布出来；
 *  - 崩溃堆栈存在内存字段和 Intent extra 两个副本，只清列表的话，刷新或旋转都会把它贴回来。
 */
class LogClearTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private fun lines(activity: LogActivity): List<String> {
        var snapshot = emptyList<String>()
        onMain { snapshot = activity.findViewById<LogView>(R.id.logView).snapshotLines() }
        return snapshot
    }

    /** 等到列表连续两次取样一致：历史发布是分批的，早一步取样会漏掉后面几批。 */
    private fun settledLines(activity: LogActivity): List<String> {
        var previous = lines(activity)
        repeat(20) {
            Thread.sleep(300)
            val current = lines(activity)
            if (current == previous) return current
            previous = current
        }
        return previous
    }

    private fun resumedLogActivity(ignoring: LogActivity): LogActivity? {
        var found: LogActivity? = null
        onMain {
            found = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<LogActivity>()
                .firstOrNull { it !== ignoring }
        }
        return found
    }

    @Test fun clearingACrashPageDropsTheStackForRefreshAndRecreation() {
        val fixture = "clear-fixture-crash-stacktrace"
        val first = instrumentation.startActivitySync(
            Intent(application, LogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(LogActivity.FROM_CRASH, true)
                .putExtra(LogActivity.CRASH_STACK_TRACE, fixture)
        ) as LogActivity
        var latest = first
        try {
            await { lines(first).any { it.contains(fixture) } }

            onMain {
                first.findViewById<ImageButton>(R.id.btnClear).performClick()
                // 清空还没落地：刷新与导出必须立刻关上，否则它们读到的正是还没删掉的历史。
                assertFalse(first.findViewById<ImageButton>(R.id.btnRefresh).isEnabled)
                assertFalse(first.findViewById<ImageButton>(R.id.btnExport).isEnabled)
                assertFalse(first.findViewById<ImageButton>(R.id.btnClear).isEnabled)
            }

            await {
                var enabled = false
                onMain { enabled = first.findViewById<ImageButton>(R.id.btnRefresh).isEnabled }
                enabled && lines(first).none { it.contains(fixture) }
            }

            onMain { first.recreate() }
            await { resumedLogActivity(first)?.let { latest = it; true } ?: false }
            assertNull(latest.intent.getStringExtra(LogActivity.CRASH_STACK_TRACE))
            // 重建会重新走一遍「读历史 + 贴崩溃堆栈」，清掉的内容不能在这里复活。
            assertTrue(settledLines(latest).none { it.contains(fixture) })
        } finally {
            onMain { latest.finish() }
        }
    }
}
