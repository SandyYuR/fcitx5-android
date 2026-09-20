/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class FcitxLifecycleRegistry : FcitxLifecycle {

    private val internalStateFlow = MutableStateFlow(FcitxLifecycle.State.STOPPED)

    override val stateFlow = internalStateFlow.asStateFlow()

    override val currentState: FcitxLifecycle.State
        get() = internalStateFlow.value

    private val job = SupervisorJob()

    override val lifecycleScope = CoroutineScope(job + Dispatchers.Default)

    private val transitionLock = Any()

    /**
     * Advance the lifecycle by [event], reporting whether the transition happened.
     *
     * Two behaviours changed here on purpose:
     *
     * 1. **An out-of-order event no longer throws.** The old code asserted the current state with
     *    `ensureAt` and threw `IllegalStateException` otherwise. `postEvent` runs on the native
     *    event-callback thread (`Fcitx.handleFcitxEvent` → `ReadyEvent`), so a late event threw
     *    *inside* the JNI upcall and could take the native loop down with it. A stale event is an
     *    expected race, not a programming error, so it is now logged and ignored.
     * 2. **`ON_STOP` also accepts `STARTING`.** Being asked to stop before the engine ever became
     *    ready is normal — the last client can disconnect during the first-run deployment window.
     *    Accepting only `READY` meant the entire stop request was dropped: the engine stayed
     *    resident with zero clients, and every later `start()` was rejected because the state was
     *    not STOPPED (`restartFcitx` therefore silently did nothing).
     */
    fun postEvent(event: FcitxLifecycle.Event): Boolean {
        val next: FcitxLifecycle.State?
        val current: FcitxLifecycle.State
        synchronized(transitionLock) {
            current = internalStateFlow.value
            next = when (event) {
                FcitxLifecycle.Event.ON_START ->
                    if (current == FcitxLifecycle.State.STOPPED) FcitxLifecycle.State.STARTING else null

                FcitxLifecycle.Event.ON_READY ->
                    if (current == FcitxLifecycle.State.STARTING) FcitxLifecycle.State.READY else null

                FcitxLifecycle.Event.ON_STOP ->
                    if (current == FcitxLifecycle.State.STARTING ||
                        current == FcitxLifecycle.State.READY
                    ) {
                        FcitxLifecycle.State.STOPPING
                    } else null

                FcitxLifecycle.Event.ON_STOPPED ->
                    if (current == FcitxLifecycle.State.STOPPING) FcitxLifecycle.State.STOPPED else null
            }
            if (next != null) internalStateFlow.value = next
        }
        if (next == null) {
            Timber.w("Ignored lifecycle event $event at state $current")
            return false
        }
        if (next >= FcitxLifecycle.State.STOPPING) {
            // Releases everything parked in `whenReady`/`whenAtState`. Without this, waiters stayed
            // suspended for the rest of the process whenever the engine never reached READY.
            job.cancelChildren()
        }
        return true
    }
}

interface FcitxLifecycle {
    val stateFlow: StateFlow<State>
    val currentState: State
    val lifecycleScope: CoroutineScope

    enum class State {
        STARTING,
        READY,
        STOPPING,
        STOPPED
    }

    enum class Event {
        ON_START,
        ON_READY,
        ON_STOP,
        ON_STOPPED
    }
}

interface FcitxLifecycleOwner {
    val lifecycle: FcitxLifecycle
}

val FcitxLifecycleOwner.lifeCycleScope
    get() = lifecycle.lifecycleScope

suspend inline fun <T> FcitxLifecycle.whenAtState(
    state: FcitxLifecycle.State,
    block: suspend CoroutineScope.() -> T
): T {
    stateFlow.first { it == state }
    return block(lifecycleScope)
}

suspend inline fun <T> FcitxLifecycle.whenReady(noinline block: suspend CoroutineScope.() -> T) =
    whenAtState(FcitxLifecycle.State.READY, block)

suspend inline fun <T> FcitxLifecycle.whenStopped(noinline block: suspend CoroutineScope.() -> T) =
    whenAtState(FcitxLifecycle.State.STOPPED, block)

fun <T> FcitxLifecycle.launchWhenReady(block: suspend CoroutineScope.() -> T) =
    lifecycleScope.launch { whenReady(block) }

fun <T> FcitxLifecycle.launchWhenStopped(block: suspend CoroutineScope.() -> T) =
    lifecycleScope.launch { whenStopped(block) }
