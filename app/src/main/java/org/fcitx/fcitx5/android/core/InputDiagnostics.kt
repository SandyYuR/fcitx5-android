package org.fcitx.fcitx5.android.core

import android.os.Build
import android.os.Trace
import android.util.Log
import org.fcitx.fcitx5.android.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic counters describe delivery to one active collector, not an assertion of lost text. */
object InputDiagnostics {
    private val sequence = AtomicLong()
    internal fun emitting(event: FcitxEvent<*>) {
        if (!BuildConfig.INPUT_DIAGNOSTICS) return
        event.diagnosticEmittedNanos = System.nanoTime()
        event.diagnosticSequence = sequence.incrementAndGet()
        counter("rime.events.produced", event.diagnosticSequence)
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
            val age = (System.nanoTime() - event.diagnosticEmittedNanos).coerceAtLeast(0)
            maxAge = maxOf(maxAge, age)
            counter("rime.events.received", received)
            counter("rime.events.sequence_gaps", gaps)
            counter("rime.events.pending", (sequence.get() - seq).coerceAtLeast(0))
            counter("rime.events.age_ns", age)
            if (received % 256L == 0L) Log.i("RimeInputMetrics", "received=$received gaps=$gaps max_age_ns=$maxAge")
        }
    }
}
