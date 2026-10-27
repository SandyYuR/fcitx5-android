/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Native callbacks run serially on the owner; requesting shutdown never waits on that thread. */
internal class EngineLifecycleController(
    private val scope: CoroutineScope,
    private val registry: FcitxLifecycleRegistry,
    private val onStarting: () -> Unit,
    private val initialize: () -> Unit,
    private val onReady: () -> Unit,
    private val onStopping: () -> Unit,
    private val finalize: () -> Unit,
    private val afterReady: () -> Unit = {},
) {
    private val lock = Any()
    private var desiredRunning = false
    private var stopping: Deferred<Unit>? = null
    private var restartScheduled = false

    fun start(): Unit = synchronized(lock) {
        desiredRunning = true
        val pending = stopping
        if (pending != null && !pending.isCompleted) {
            if (!restartScheduled) {
                restartScheduled = true
                scope.launch {
                    try {
                        pending.await()
                    } finally {
                        synchronized(lock) {
                            restartScheduled = false
                            if (desiredRunning) start()
                        }
                    }
                }
            }
        } else if (registry.currentState == FcitxLifecycle.State.STOPPED) {
            registry.postEvent(FcitxLifecycle.Event.ON_START)
            onStarting()
            scope.launch {
                try {
                    initialize()
                } finally {
                    synchronized(lock) {
                        // Stop may have been requested while native initialization was running.
                        if (registry.currentState == FcitxLifecycle.State.STARTING) {
                            onReady.invoke()
                            registry.postEvent(FcitxLifecycle.Event.ON_READY)
                            afterReady.invoke()
                        }
                        Unit
                    }
                }
            }
        }
    }

    fun stopAsync(): Deferred<Unit> = synchronized(lock) {
        desiredRunning = false
        stopping?.takeIf { !it.isCompleted }?.let { return@synchronized it }
        if (registry.currentState == FcitxLifecycle.State.STOPPED)
            return@synchronized CompletableDeferred(Unit)
        onStopping()
        registry.postEvent(FcitxLifecycle.Event.ON_STOP)
        scope.async {
            try {
                finalize()
            } finally {
                synchronized(lock) { registry.postEvent(FcitxLifecycle.Event.ON_STOPPED) }
            }
        }.also { stopping = it }
    }
}
