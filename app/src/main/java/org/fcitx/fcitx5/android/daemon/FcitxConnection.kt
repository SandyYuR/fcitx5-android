/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.FcitxAPI
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Retain lifecycle cancellation, without dispatching a cached read off the caller. */
internal fun <T> runImmediatelyInContext(context: CoroutineContext, block: suspend () -> T): T =
    runBlocking(context.minusKey(ContinuationInterceptor)) { block() }

/**
 * Clients should use [FcitxConnection] to run fcitx operations.
 */
interface FcitxConnection {

    /**
     * Run an operation immediately
     * The suspended [block] will be executed in caller's thread.
     * Use this function only for non-blocking operations like
     * accessing [FcitxAPI.eventFlow].
     */
    fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T

    /**
     * Run an operation immediately if fcitx is at ready state.
     * Otherwise, caller will be suspended until fcitx is ready and operation is done.
     * The suspended [block] will be executed in caller's thread.
     * Client should use this function in most cases.
     */
    suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T

    /**
     * Run an operation if fcitx is at ready state.
     * Otherwise, do nothing.
     * The suspended [block] will be executed in thread pool.
     * This function does not block or suspend the caller.
     */
    fun runIfReady(block: suspend FcitxAPI.() -> Unit)

    val lifecycleScope: CoroutineScope
}
