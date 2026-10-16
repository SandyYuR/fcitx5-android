package org.fxrime.direct

import java.util.concurrent.atomic.AtomicLong

/*
 * SessionGenerationGate — 异步结果的作废判据。
 *
 * 为什么必须有这一层（报告 §4「生命周期」领域）：
 *   现有链路里，Fcitx.kt 用 MutableSharedFlow(extraBufferCapacity=15, onBufferOverflow=DROP_OLDEST)
 *   向 UI 回流事件。直连之后如果沿用类似的异步回流，就必须回答「一个迟到的候选列表该不该显示」。
 *   没有代次号就只能靠 DROP_OLDEST 之类的丢弃策略兜底，而报告 §5.3 明确禁止宣称
 *   「DROP_OLDEST 已造成用户可见丢字」——也就是说这条路径的实际情况尚未测量，不能假装它没问题。
 *
 * 这里的做法（与 native 侧 dr_generation_allows 同口径，见 native/keymap.c）：
 *   - generation 是一个单调递增的计数器，每次「焦点切换 / session 重建 / 引擎重启」自增。
 *   - 派发异步任务时记下当时的 generation，结果回来时比对；不符即丢弃，且计数可被读到。
 *   - 这不是性能优化，是正确性约束：防止 A 输入框的候选显示在 B 输入框上。
 *
 * 只依赖 JDK 与 kotlin-stdlib，不 import android.*，因此可以在 JVM 上编译与单测。
 */
class SessionGenerationGate {

    private val current = AtomicLong(1)
    private val staleDropped = AtomicLong(0)
    private val accepted = AtomicLong(0)

    /** 当前代次。 */
    val generation: Long get() = current.get()

    /** 被判为过期而丢弃的结果数 —— 必须能读到，不能静默丢。 */
    val droppedStale: Long get() = staleDropped.get()

    /** 通过代次校验的结果数。 */
    val acceptedCount: Long get() = accepted.get()

    /** 焦点切换 / session 重建 / 引擎重启时调用一次。 */
    fun bump(): Long = current.incrementAndGet()

    /**
     * 用当前代次包一个待消费的结果。只有同代次的结果才允许被消费。
     * [producer] 在调用线程上立即求值（真正进入 librime 的那一步仍要过 RimeThreadOwner）。
     */
    fun <T> bind(producer: () -> T): Pending<T> = Pending(generation, producer())

    /**
     * 消费前校验。generation 不符返回 null 并自增 droppedStale；
     * 注意返回 null 有两种含义（过期 / 任务本身产出 null），调用方要区分时看 droppedStale 是否变化。
     */
    fun <T> consume(pending: Pending<T>): T? {
        if (pending.generation != current.get()) {
            staleDropped.incrementAndGet()
            return null
        }
        accepted.incrementAndGet()
        return pending.value
    }

    /** 批量消费：只保留同代次的结果，顺序不变。 */
    fun <T> consumeAll(items: List<Pending<T>>): List<T> {
        val gen = current.get()
        val out = ArrayList<T>(items.size)
        for (item in items) {
            if (item.generation == gen) {
                out += item.value
                accepted.incrementAndGet()
            } else {
                staleDropped.incrementAndGet()
            }
        }
        return out
    }

    /**
     * 代次快照，用于断言「这次按键与这批候选是同一代」。
     * 与 RimeThreadOwner 配合：派发时取 generation，结果回来时交给 consume。
     */
    fun snapshot(): Long = current.get()

    data class Pending<T>(val generation: Long, val value: T)
}
