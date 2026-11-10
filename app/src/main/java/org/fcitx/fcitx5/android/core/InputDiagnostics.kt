package org.fcitx.fcitx5.android.core

import android.os.Build
import android.os.Trace
import android.util.Log
import org.fcitx.fcitx5.android.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Diagnostic counters describe delivery to one active collector, not an assertion of lost text. */
object InputDiagnostics {
    private val sequence = AtomicLong()

    internal fun interface CounterSink {
        fun set(name: String, value: Long)
    }

    private val traceSink = CounterSink(::counter)

    internal class ServiceTracker(
        private val enabled: Boolean,
        private val clockNanos: () -> Long,
        private val sink: CounterSink
    ) {
        private val sequence = AtomicLong()
        private val waiting = AtomicLong()
        private val running = AtomicLong()
        private val completed = AtomicLong()
        private val cancelled = AtomicLong()
        private val rejected = AtomicLong()

        fun create(category: String): ServiceTask? {
            if (!enabled) return null
            val task = ServiceTask(this, sequence.incrementAndGet(), category, clockNanos())
            sink.set("rime.service.waiting", waiting.incrementAndGet())
            return task
        }

        internal fun started(task: ServiceTask) {
            if (!task.state.compareAndSet(ServiceTask.Waiting, ServiceTask.Running)) return
            sink.set("rime.service.waiting", waiting.decrementAndGet().coerceAtLeast(0))
            sink.set("rime.service.running", running.incrementAndGet())
            sink.set("rime.service.wait_ns", (clockNanos() - task.queuedNanos).coerceAtLeast(0))
        }

        internal fun completed(task: ServiceTask, cause: Throwable?) {
            when (task.state.getAndSet(ServiceTask.Done)) {
                ServiceTask.Waiting -> sink.set("rime.service.waiting", waiting.decrementAndGet().coerceAtLeast(0))
                ServiceTask.Running -> sink.set("rime.service.running", running.decrementAndGet().coerceAtLeast(0))
                else -> return
            }
            if (cause == null) sink.set("rime.service.completed", completed.incrementAndGet())
            else sink.set("rime.service.cancelled", cancelled.incrementAndGet())
        }

        internal fun rejected() {
            sink.set("rime.service.rejected", rejected.incrementAndGet())
        }
    }

    internal class ServiceTask internal constructor(
        private val tracker: ServiceTracker,
        val id: Long,
        val category: String,
        internal val queuedNanos: Long
    ) : AbstractCoroutineContextElement(Key) {
        internal val state = AtomicInteger(Waiting)
        fun started() = tracker.started(this)
        fun completed(cause: Throwable?) = tracker.completed(this, cause)
        fun rejected() = tracker.rejected()

        companion object Key : CoroutineContext.Key<ServiceTask> {
            internal const val Waiting = 0
            internal const val Running = 1
            internal const val Done = 2
        }
    }

    private val serviceTracker = ServiceTracker(BuildConfig.INPUT_DIAGNOSTICS, System::nanoTime, traceSink)
    internal fun serviceTask(category: String): ServiceTask? = serviceTracker.create(category)

    internal val eventQueueDiagnostics = object : InputEventQueue.Diagnostics {
        override fun produced(batch: List<FcitxEvent<*>>) {
            if (!BuildConfig.INPUT_DIAGNOSTICS) return
            val now = System.nanoTime()
            batch.forEach { it.diagnosticProducedNanos = now }
        }

        override fun state(pendingBatches: Int, presentationReplaced: Long) {
            if (!BuildConfig.INPUT_DIAGNOSTICS) return
            counter("rime.events.pending_batches", pendingBatches.toLong())
            counter("rime.events.presentation_replaced", presentationReplaced)
        }
    }

    internal fun emitting(event: FcitxEvent<*>) {
        if (!BuildConfig.INPUT_DIAGNOSTICS) return
        event.diagnosticEmittedNanos = System.nanoTime()
        event.diagnosticSequence = sequence.incrementAndGet()
        counter("rime.events.emitted", event.diagnosticSequence)
    }
    private fun counter(name: String, value: Long) {
        if (Build.VERSION.SDK_INT >= 29) Trace.setCounter(name, value)
    }
    class Collector {
        private var last = 0L
        private var received = 0L
        private var gaps = 0L
        private var maxAge = 0L
        fun received(event: FcitxEvent<*>) {
            if (!BuildConfig.INPUT_DIAGNOSTICS || event.diagnosticSequence == 0L) return
            val seq = event.diagnosticSequence
            if (last > 0) gaps += (seq - last - 1).coerceAtLeast(0)
            last = seq; received++
            val now = System.nanoTime()
            val flowWait = (now - event.diagnosticEmittedNanos).coerceAtLeast(0)
            counter("rime.events.received", received)
            counter("rime.events.sequence_gaps", gaps)
            if (event.diagnosticProducedNanos > 0L) {
                val resultWait = (now - event.diagnosticProducedNanos).coerceAtLeast(0)
                maxAge = maxOf(maxAge, resultWait)
                counter("rime.events.result_wait_ns", resultWait)
            }
            counter("rime.events.flow_wait_ns", flowWait)
            if (received % 256L == 0L) Log.i("RimeInputMetrics", "received=$received gaps=$gaps max_age_ns=$maxAge")
        }
    }
}
