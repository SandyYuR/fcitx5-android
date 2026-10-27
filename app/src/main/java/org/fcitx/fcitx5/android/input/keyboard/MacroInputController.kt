/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** Main-thread ownership of running macros and intentionally held Down steps. */
internal class MacroInputController(private val onReleaseFailure: (Throwable) -> Unit = {}) {
    private val running = mutableSetOf<Execution>()
    private val pressed = mutableListOf<Press>()

    inner class Execution internal constructor(val job: Job, private val current: () -> Boolean) {
        val isCurrent get() = current()

        fun checkCurrent() {
            job.ensureActive()
            if (!isCurrent) throw CancellationException("Macro editor session ended")
        }

        fun press(key: KeyRef): Press {
            checkCurrent()
            return Press(this, key).also { pressed.add(it) }
        }

        fun release(key: KeyRef, fallback: () -> Unit) {
            checkCurrent()
            val press = pressed.lastOrNull { it.key == key }
            if (press != null) press.release() else fallback()
        }

        fun finish(completed: Boolean) {
            running.remove(this)
            // A successful Down-only macro deliberately holds the key for a later Up.
            // Cancellation/failure owns only this execution's still-pressed keys.
            if (!completed) pressed.filter { it.owner === this }.asReversed().forEach { it.release(cancelled = true) }
        }
    }

    inner class Press internal constructor(internal val owner: Execution, internal val key: KeyRef) {
        private val releases = mutableListOf<(Boolean) -> Unit>()

        fun send(down: () -> Unit, up: (cancelled: Boolean) -> Unit) {
            owner.checkCurrent()
            // Register first: a dispatch can update modifier state before it throws.
            releases.add(up)
            down()
        }

        fun release(cancelled: Boolean = false) {
            if (!pressed.remove(this)) return
            var firstFailure: Throwable? = null
            for (release in releases.asReversed()) {
                try {
                    release(cancelled || !owner.isCurrent)
                } catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure
                    else firstFailure.addSuppressed(failure)
                }
            }
            if (firstFailure != null) {
                if (cancelled) onReleaseFailure(firstFailure) else throw firstFailure
            }
        }
    }

    fun begin(job: Job, isCurrent: () -> Boolean): Execution =
        Execution(job, isCurrent).also { it.checkCurrent(); running.add(it) }

    fun cancelAll() {
        running.toList().forEach { it.job.cancel(CancellationException("Input editor changed")) }
        pressed.toList().asReversed().forEach { it.release(cancelled = true) }
    }
}
