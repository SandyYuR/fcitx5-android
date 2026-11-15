/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coalesces approval for one host and serializes prompts for different hosts. */
internal class JsHostApprovalCoordinator(private val scope: CoroutineScope) {
    private class Request {
        val result = CompletableDeferred<Boolean>()
        var waiters = 0
        var job: Job? = null
    }

    private val stateLock = Mutex()
    private val promptLock = Mutex()
    private val pending = mutableMapOf<String, Request>()

    suspend fun approve(
        host: String,
        isApproved: () -> Boolean,
        prompt: suspend () -> Boolean,
        persist: () -> Unit,
    ): Boolean {
        if (isApproved()) return true
        val request = stateLock.withLock {
            pending[host]?.also { it.waiters++ } ?: Request().also { created ->
                created.waiters = 1
                pending[host] = created
                created.job = scope.launch(start = CoroutineStart.LAZY) {
                    execute(host, created, isApproved, prompt, persist)
                }.also(Job::start)
            }
        }
        try {
            return request.result.await()
        } finally {
            withContext(NonCancellable) {
                val abandoned = stateLock.withLock {
                    request.waiters--
                    if (request.waiters == 0 && !request.result.isCompleted) {
                        if (pending[host] === request) pending.remove(host)
                        request.job
                    } else null
                }
                abandoned?.cancel()
            }
        }
    }

    private suspend fun execute(
        host: String,
        request: Request,
        isApproved: () -> Boolean,
        prompt: suspend () -> Boolean,
        persist: () -> Unit,
    ) {
        try {
            val approved = promptLock.withLock {
                if (isApproved()) true
                else prompt().also { accepted -> if (accepted) persist() }
            }
            request.result.complete(approved)
        } catch (error: CancellationException) {
            request.result.cancel(error)
            throw error
        } catch (error: Throwable) {
            request.result.completeExceptionally(error)
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock {
                    if (pending[host] === request) pending.remove(host)
                }
            }
        }
    }
}
