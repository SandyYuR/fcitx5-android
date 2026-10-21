/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Admission to the existing ordered service queue, not a second queue of keys. */
class InputKeyGate(
    private val clockNanos: () -> Long = System::nanoTime,
    private val maxWaitingKeys: Int = 128,
    private val maxWaitNanos: Long = 5_000_000_000L
) {
    private enum class Runtime { Stopped, Starting, Running, Blocked, Failed }
    class Ticket internal constructor(val generation: Long, val capturedNanos: Long, val waitedForBinding: Boolean) {
        internal var released = false
    }
    private var runtime = Runtime.Stopped
    private var generation = 0L
    private var enabled = false
    private var resolved = false
    private var ready = false
    private var waiting = 0

    @Synchronized fun starting() { runtime = Runtime.Starting }
    @Synchronized fun running(success: Boolean = true) { runtime = if (success) Runtime.Running else Runtime.Failed }
    @Synchronized fun block() { runtime = Runtime.Blocked }
    @Synchronized fun stop() { runtime = Runtime.Stopped; enabled = false; ready = false }
    @Synchronized fun invalidate(value: Long) {
        generation = value; enabled = false; resolved = false; ready = false; waiting = 0
    }
    @Synchronized fun prepare(value: Long, acceptsKeys: Boolean) {
        if (value < generation) return
        generation = value; enabled = acceptsKeys; resolved = false; ready = false; waiting = 0
    }
    @Synchronized fun resolve(value: Long, usable: Boolean) {
        if (generation == value) { resolved = true; ready = usable }
    }
    @Synchronized fun canProcess() = runtime == Runtime.Running && enabled && resolved && ready

    @Synchronized fun capture(onRejected: (String) -> Unit = {}): Ticket? {
        val rejection = when {
            !enabled -> "no_editor"
            runtime != Runtime.Starting && runtime != Runtime.Running -> runtime.name.lowercase()
            resolved && !ready -> "binding_unavailable"
            !resolved && waiting >= maxWaitingKeys -> "queue_full"
            else -> null
        }
        if (rejection != null) { onRejected(rejection); return null }
        val pending = !resolved
        if (pending) waiting++
        return Ticket(generation, clockNanos(), pending)
    }
    @Synchronized fun canDeliver(ticket: Ticket): Boolean = !ticket.released &&
        ticket.generation == generation && canProcess() &&
        (!ticket.waitedForBinding || clockNanos() - ticket.capturedNanos <= maxWaitNanos)

    @Synchronized fun release(ticket: Ticket) {
        if (ticket.released) return
        ticket.released = true
        if (ticket.waitedForBinding && ticket.generation == generation) waiting = (waiting - 1).coerceAtLeast(0)
    }
}
