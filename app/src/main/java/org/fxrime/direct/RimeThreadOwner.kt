package org.fxrime.direct

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * RimeThreadOwner — librime 的单线程 owner。
 *
 * 为什么必须有这一层（报告 §4「进程/线程」领域）：
 *   librime 不是线程安全的。现有链路里这个角色由 FcitxDispatcher 的 `fcitx-main` 单线程承担
 *   （FcitxDispatcher.kt:23-140）；foxy 里由 `rime-main` 单线程承担，所有调用经
 *   `runOnRime(vy)` = `getRimeThread().submit(Callable).get()` 同步阻塞派发。
 *   直连之后没有 fcitx 兜底，这个 owner 必须由我们自己建，否则并发进 librime 就是数据竞争。
 *
 * 语义对齐点：
 *   - 线程名固定（默认 "rime-main"，与 foxy 同名；也可传 "fcitx-main" 对齐现有仓库）。
 *   - daemon = true：不能因为引擎线程还活着而阻止进程退出。
 *   - 提交即阻塞取回（submit().get()），调用方视角是同步的，与现有 fcitx-main 的语义一致。
 *   - 退出时未执行任务不静默丢弃：shutdown 后显式 drain 并计数，调用方能读到这个数字。
 *
 * 只依赖 JDK 与 kotlin-stdlib，不 import android.*，因此可以在 JVM 上编译与单测。
 */
class RimeThreadOwner(threadName: String = "rime-main") {

    private val factory = object : ThreadFactory {
        private val seq = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "$threadName-${seq.getAndIncrement()}")
            t.isDaemon = true
            return t
        }
    }

    private val executor = Executors.newSingleThreadExecutor(factory)
    private val ownerName = AtomicReference<String?>(null)
    private val invocationCount = AtomicInteger(0)
    private val rejectedCount = AtomicInteger(0)

    /** 当前持有 owner 的线程名，未初始化时为 null。用于断言「没有并发进入」。 */
    val currentOwnerName: String? get() = ownerName.get()

    /** 已经派发到 rime 线程的调用次数。 */
    val invocations: Int get() = invocationCount.get()

    /** 关闭后被拒绝的任务数 —— 这个数字必须能被读到，不能静默丢。 */
    val rejectedAfterShutdown: Int get() = rejectedCount.get()

    init {
        // 立刻把线程拉起来并记下名字，便于外部断言 owner 只有一个。
        executor.submit { ownerName.set(Thread.currentThread().name) }.get()
    }

    /**
     * 在 rime 线程上执行 [block] 并同步取回结果。
     * 已在 rime 线程上时直接执行，避免自己等自己（重入会死锁：submit().get() 等不到自己）。
     */
    fun <T> runOnRime(block: () -> T): T {
        if (Thread.currentThread().name.orEmpty().startsWith(ownerName.get() ?: "\u0000")) {
            return block()
        }
        if (executor.isShutdown) {
            rejectedCount.incrementAndGet()
            throw IllegalStateException("RimeThreadOwner 已关闭，任务被拒绝（rejectedCount 已自增，未静默丢弃）")
        }
        invocationCount.incrementAndGet()
        return executor.submit(Callable { block() }).get()
    }

    /** 异步派发，返回 future。调用方需自己配合 SessionGenerationGate 校验结果是否还有效。 */
    fun <T> submitOnRime(block: () -> T): java.util.concurrent.Future<T> {
        if (executor.isShutdown) {
            rejectedCount.incrementAndGet()
            throw IllegalStateException("RimeThreadOwner 已关闭，任务被拒绝")
        }
        invocationCount.incrementAndGet()
        return executor.submit(Callable { block() })
    }

    /**
     * 断言当前不在 rime 线程上（用于捕捉误把 runOnRime 嵌套调用的情况）。
     */
    fun isOnRimeThread(): Boolean =
        Thread.currentThread().name == ownerName.get()

    /**
     * 关闭。这里刻意不调用 shutdownNow()：报告 §4 要求「退出时未执行任务的处理」是显式的，
     * 静默丢弃未执行的按键会丢字。故先 shutdown() 让已排队任务跑完，超时后才 shutdownNow()。
     * 返回被丢弃的任务数（正常情况下应为 0）。
     */
    fun shutdown(timeoutMs: Long = 3000): Int {
        executor.shutdown()
        val finished = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        val dropped = if (finished) 0 else executor.shutdownNow().size
        rejectedCount.addAndGet(dropped)
        return dropped
    }
}
