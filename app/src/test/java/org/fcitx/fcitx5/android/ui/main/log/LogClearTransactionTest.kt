/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清空事务必须比发起它的页面活得久。
 *
 * 删除排在 logcat 子进程和 LogSink writer 各自的队列里，从点下按钮到文件真正消失之间有
 * 一个窗口。页面在这个窗口里被重建时，挂在它 lifecycleScope 上的任务会被取消；如果事务
 * 一起被取消，删除从此没人做，而新页面已经把还没删掉的历史读了回来。
 */
class LogClearTransactionTest {

    @Test
    fun theTransactionSurvivesTheCallerThatStartedIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clear = LogClear(scope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val caller = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val job = clear.start {
                started.complete(Unit)
                release.await()
            }
            started.await()
            assertTrue(clear.clearing)

            // 页面在清空进行中被销毁：发起它的作用域被取消，事务不受影响。
            caller.cancel()

            assertTrue(clear.clearing)
            assertTrue(job.isActive)
            release.complete(Unit)
            job.join()
            assertFalse(clear.clearing)
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun aSecondClearJoinsTheOneAlreadyRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clear = LogClear(scope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            // 第二次点击必须等同一个「已经落地不了」的事务，不能另起一次删除。
            val first = clear.start {
                started.complete(Unit)
                release.await()
            }
            started.await()
            val second = clear.start { }
            assertSame(first, second)
            release.complete(Unit)
            first.join()
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun aFailedTransactionDoesNotStayInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clear = LogClear(scope)
        try {
            val job: Job = clear.start { error("delete failed") }
            job.join()
            assertFalse(clear.clearing)
            assertFalse(job.isActive)
        } finally {
            scope.cancel()
        }
    }
}
