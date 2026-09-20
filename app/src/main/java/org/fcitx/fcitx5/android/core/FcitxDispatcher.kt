/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Thrown when work is handed to a dispatcher that is no longer accepting jobs.
 *
 * A dedicated type rather than a bare [IllegalStateException], so the callers that queue fcitx
 * operations can drop exactly this condition without swallowing unrelated programming errors —
 * an uncaught exception on `fcitx.lifecycleScope` reaches the default handler and takes the
 * process down.
 */
class FcitxNotRunningException(message: String) : IllegalStateException(message)

class FcitxDispatcher(private val controller: FcitxController) : CoroutineDispatcher() {

    class WrappedRunnable(private val runnable: Runnable) : Runnable by runnable {
        private val time = System.currentTimeMillis()

        override fun run() {
            val delta = System.currentTimeMillis() - time
            if (delta > JOB_WAITING_LIMIT) {
                Timber.w("$this has waited $delta ms to get run since created!")
            }
            runnable.run()
        }

        override fun toString(): String = "WrappedRunnable[${hashCode()}]"
    }

    // this is fcitx main thread
    private val internalDispatcher = Executors.newSingleThreadExecutor {
        Thread(it).apply {
            name = "fcitx-main"
        }
    }.asCoroutineDispatcher()

    private val internalScope = CoroutineScope(internalDispatcher)

    interface FcitxController {
        fun nativeStartup()
        fun nativeLoopOnce()
        fun nativeScheduleEmpty()
        fun nativeExit()

        /**
         * Called on the fcitx thread when the native loop is over, whatever the reason: an
         * explicit [stop], or [nativeStartup] / the loop having thrown.
         *
         * [error] is non-null when the loop ended because of a failure. Implementations must
         * bring the lifecycle to STOPPED in both cases — when this was only signalled for the
         * success path, a startup failure left the state at STARTING forever, which rejected
         * every later `start()`/`stop()` and left every `whenReady` waiter suspended.
         */
        fun onStopped(error: Throwable?)
    }

    /**
     * Guards [accepting], [running], [nativeReady], [queue] and the teardown latches as one
     * critical section.
     *
     * These were three independent atomics, so "check accepting → enqueue → wake" was not atomic
     * against "stop accepting → drain → exit native". A job could be enqueued after the drain and
     * never run: its coroutine stayed suspended forever and the input event it carried was
     * silently lost (AGENTS.md §8 requires commit/delete/key to be lossless).
     */
    private val lock = ReentrantLock()

    private val queue = ArrayDeque<WrappedRunnable>()

    /** New work is accepted (and will eventually run) while true. Only touched under [lock]. */
    private var accepting = false

    /** The native loop should keep going while true. Only touched under [lock]. */
    private var running = false

    /** The native side is up and may be woken. Only touched under [lock]. */
    private var nativeReady = false

    /**
     * Identifies the current loop. [start] bumps it, so a previous loop's teardown can never clear
     * the flags of — or exit — an instance that has already been started again.
     */
    private var loopToken = 0

    /** Completed when the current loop has fully finished, including `onStopped`. */
    private var loopDone: CountDownLatch? = null

    /**
     * Start the dispatcher.
     *
     * Returns immediately.
     *
     * @return true when this call actually started a native loop, false when one was already
     * active (or on its way up).
     */
    fun start(): Boolean {
        Timber.d("FcitxDispatcher start()")
        val token: Int
        lock.withLock {
            if (accepting || running) {
                Timber.w("Skip start: dispatcher is already active")
                return false
            }
            accepting = true
            running = true
            loopToken += 1
            token = loopToken
            loopDone = CountDownLatch(1)
        }
        internalScope.launch { runNativeLoop(token) }
        return true
    }

    /**
     * Ask the native loop to stop, without waiting for it.
     *
     * This used to block the caller until the native side noticed, and every IME teardown did that
     * on the Android main thread — so the app froze for as long as the native loop took (see the
     * "blocked for 10+ seconds / Skipped 1378 frames" history recorded in native-lib.cpp). The
     * outcome is reported through [FcitxController.onStopped]; code that must know the engine is
     * really gone uses [stopAndWait], which is explicitly blocking.
     *
     * Safe to call from any thread, including the main thread, and from any state — including
     * while the engine is still starting.
     */
    fun stop() {
        Timber.i("FcitxDispatcher stop()")
        lock.withLock {
            accepting = false
            if (!running) return
            running = false
            wakeNativeLocked()
        }
    }

    /**
     * Ask the native loop to stop and block until it has finished tearing down.
     *
     * **Must not be called from the main thread**: it waits for the loop, and the teardown path
     * runs jobs that call back into the fcitx thread. Returns false if [timeoutMs] elapsed first.
     */
    fun stopAndWait(timeoutMs: Long = STOP_TIMEOUT_MS): Boolean {
        val latch = lock.withLock {
            accepting = false
            running = false
            wakeNativeLocked()
            loopDone
        } ?: return true // no loop has ever been started
        return try {
            val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                Timber.e("Timed out after ${timeoutMs}ms waiting for fcitx to stop")
            }
            finished
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Timber.w(e, "Interrupted while waiting for fcitx to stop")
            false
        }
    }

    private fun runNativeLoop(token: Int) {
        var error: Throwable? = null
        var started = false
        try {
            if (!shouldKeepRunning()) {
                // stop() landed before this coroutine got scheduled: nothing was started, so
                // there is nothing to exit either.
                Timber.d("FcitxDispatcher: start was cancelled before nativeStartup()")
                return
            }
            Timber.d("nativeStartup()")
            controller.nativeStartup()
            started = true
            lock.withLock {
                nativeReady = true
                // wake once to process jobs that arrived before startup completed
                wakeNativeLocked()
            }
            while (isActive && shouldKeepRunning()) {
                // blocking...
                controller.nativeLoopOnce()
                // do scheduled jobs
                drain().forEach { it.run() }
            }
        } catch (t: Throwable) {
            error = t
            Timber.e(t, "fcitx native loop failed")
        } finally {
            if (isSuperseded(token)) {
                // A new instance was started while this one was tearing down. Touching the flags
                // or the native side now would tear down the *new* engine.
                Timber.e("fcitx loop $token finished after a newer one started; skipping teardown")
                releaseLoopDone(token)
                return
            }
            // Run whatever is still queued *before* tearing the native side down. These are the
            // `withFcitxContext` jobs (commit / delete / key) that were enqueued while the engine
            // was still alive; discarding them loses input silently and leaves their coroutines
            // suspended forever — the old code only logged "$n job(s) didn't get a chance to run".
            // `accepting` stays true for the duration so a job that enqueues follow-up work is not
            // rejected; the loop below re-drains until nothing is left.
            var round = 0
            while (round < MAX_TEARDOWN_ROUNDS) {
                val batch = drain()
                if (batch.isEmpty()) break
                batch.forEach { it.runDuringTeardown() }
                round += 1
            }
            val leftover = lock.withLock {
                accepting = false
                nativeReady = false
                // The loop is over for good, so refuse new work and let a later start() proceed.
                // Leaving this true made a failed startup permanently unusable: start() bailed out
                // on `accepting || running` and the lifecycle never came back to STOPPED.
                running = false
                queue.toList().also { queue.clear() }
            }
            leftover.forEach { it.runDuringTeardown() }
            if (started) {
                Timber.i("nativeExit()")
                runCatching { controller.nativeExit() }
                    .onFailure { Timber.w(it, "nativeExit() failed") }
            }
            // Converge the lifecycle. Done before releasing waiters so that a caller returning
            // from stopAndWait() sees STOPPED.
            runCatching { controller.onStopped(error) }
                .onFailure { Timber.e(it, "onStopped() failed") }
            releaseLoopDone(token)
        }
    }

    private fun releaseLoopDone(token: Int) {
        val latch = lock.withLock {
            if (token != loopToken) null else loopDone.also { loopDone = null }
        }
        latch?.countDown()
    }

    private fun WrappedRunnable.runDuringTeardown() {
        runCatching { run() }
            .onFailure { Timber.w(it, "Queued fcitx job failed during teardown") }
    }

    /** Take everything currently queued. */
    private fun drain(): List<WrappedRunnable> = lock.withLock {
        if (queue.isEmpty()) return emptyList()
        queue.toList().also { queue.clear() }
    }

    private fun shouldKeepRunning(): Boolean = lock.withLock { running }

    private fun isSuperseded(token: Int): Boolean = lock.withLock { token != loopToken }

    /**
     * Wake the native loop if it is up. Must be called with [lock] held.
     *
     * Holding the lock across the native call is what makes this race-free: the teardown path
     * clears [nativeReady] under the same lock before it touches native, so a wake decided under
     * the lock can never reach a dispatcher that has already been destroyed. Previously the check
     * and the call sat outside any lock, next to a `finally` that cleared the flag and called
     * `nativeExit()` — so `nativeScheduleEmpty()` could land after the native instance was gone.
     */
    private fun wakeNativeLocked() {
        if (!nativeReady) return
        runCatching { controller.nativeScheduleEmpty() }
            .onFailure { Timber.w(it, "nativeScheduleEmpty() failed") }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        lock.withLock {
            if (!accepting) {
                if (context[Job]?.isActive == false) {
                    Timber.d("Drop runnable from cancelled context while dispatcher is stopped: $block")
                    return
                }
                throw FcitxNotRunningException("Dispatcher is not in running state!")
            }
            queue.addLast(WrappedRunnable(block))
            // always wake, so `nativeLoopOnce()` does not block the thread with work waiting
            wakeNativeLocked()
        }
    }

    companion object {
        const val JOB_WAITING_LIMIT = 2000L

        /** How long [stopAndWait] waits for the native loop by default. */
        const val STOP_TIMEOUT_MS = 15_000L

        /**
         * Upper bound on teardown drain rounds.
         *
         * Each round runs the jobs that were queued, which may enqueue more (a coroutine that
         * suspends and resumes on this dispatcher). Real workloads settle in one or two rounds;
         * the bound only exists so a job that re-dispatches itself forever cannot hang teardown.
         */
        private const val MAX_TEARDOWN_ROUNDS = 64
    }

}
