/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程级的清空事务。
 *
 * 删除排在各条自己的队列里（logcat 的子进程、LogSink 的 writer），从点下按钮到文件真正
 * 消失之间有一个窗口。窗口里任何一次「读历史 + 接实时 reader」都还会看到准备删掉的内容：
 * 页面自己会先关掉按钮，但页面可以在这个窗口里被重建——清空任务挂在被销毁的那个
 * lifecycleScope 上会被取消，新页面既看不到清空、又会立刻把旧历史读回来。
 *
 * 事务放在进程里就没有这个缺口：谁的页面还在都能等它，新页面的首次加载同样会等它落地。
 */
internal class LogClear(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val lock = Any()
    private var inFlight: Job? = null

    /** 是否有一次清空还没落地。 */
    val clearing: Boolean
        get() = synchronized(lock) { inFlight?.isActive == true }

    /**
     * 启动一次清空；已经有一次在跑时返回同一个任务，第二个调用方只是等它。
     *
     * 事务体跑在进程作用域上：发起它的页面销毁之后，删除仍然会走完。
     */
    fun start(transaction: suspend () -> Unit): Job = synchronized(lock) {
        val current = inFlight
        if (current != null && current.isActive) return current
        scope.launch {
            try {
                transaction()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 删除失败不该把应用带走：这些文件只用于诊断，下一次写入会重建目录。
            }
        }.also { inFlight = it }
    }

    /** 等到当前这次清空落地；没有在跑的清空时立即返回。 */
    suspend fun await() {
        synchronized(lock) { inFlight }?.join()
    }

    companion object {
        val shared = LogClear()
    }
}
