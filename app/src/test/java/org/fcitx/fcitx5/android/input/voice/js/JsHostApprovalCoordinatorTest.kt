/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsHostApprovalCoordinatorTest {
    @Test fun sameHostSharesOnePromptAndDecision() = runBlocking {
        val coordinator = JsHostApprovalCoordinator(this)
        val approved = mutableSetOf<String>()
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        var prompts = 0
        suspend fun request() = coordinator.approve(
            host = "api.example",
            isApproved = { "api.example" in approved },
            prompt = {
                prompts++
                entered.send(Unit)
                release.receive()
                true
            },
            persist = { approved += "api.example" },
        )
        val first = async { request() }
        entered.receive()
        val second = async { request() }
        assertNull(withTimeoutOrNull(100) { entered.receive() })
        release.send(Unit)
        assertTrue(withTimeout(1_000) { first.await() })
        assertTrue(withTimeout(1_000) { second.await() })
        assertEquals(1, prompts)
    }

    @Test fun differentHostsNeverOverlapPrompts() = runBlocking {
        val coordinator = JsHostApprovalCoordinator(this)
        val approved = mutableSetOf<String>()
        val entered = Channel<String>(2)
        val release = Channel<Unit>(2)
        suspend fun request(host: String) = coordinator.approve(
            host = host,
            isApproved = { host in approved },
            prompt = { entered.send(host); release.receive(); true },
            persist = { approved += host },
        )
        val first = async { request("one.example") }
        val second = async { request("two.example") }
        val firstHost = withTimeout(1_000) { entered.receive() }
        assertNull(withTimeoutOrNull(100) { entered.receive() })
        release.send(Unit)
        val secondHost = withTimeout(1_000) { entered.receive() }
        assertTrue(firstHost != secondHost)
        release.send(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
    }

    @Test fun cancellingTheFirstWaiterDoesNotCancelAnotherWaiterForTheSameHost() = runBlocking {
        val coordinator = JsHostApprovalCoordinator(this)
        val approved = mutableSetOf<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var prompts = 0
        suspend fun request() = coordinator.approve(
            host = "shared.example",
            isApproved = { "shared.example" in approved },
            prompt = { prompts++; entered.complete(Unit); release.await(); true },
            persist = { approved += "shared.example" },
        )
        val owner = async { request() }
        entered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { request() }
        owner.cancelAndJoin()
        release.complete(Unit)
        assertTrue(withTimeout(1_000) { waiter.await() })
        assertEquals(1, prompts)
        assertTrue(request())
        assertEquals(1, prompts)
    }

    @Test fun cancellingTheOnlyWaiterStopsThePromptAndLeavesNoStaleRequest() = runBlocking {
        val coordinator = JsHostApprovalCoordinator(this)
        val entered = CompletableDeferred<Unit>()
        val promptEnded = CompletableDeferred<Unit>()
        var prompts = 0
        suspend fun request(prompt: suspend () -> Boolean) = coordinator.approve(
            host = "cancel.example",
            isApproved = { false },
            prompt = prompt,
            persist = { },
        )
        val abandoned = async {
            request {
                prompts++
                entered.complete(Unit)
                try { awaitCancellation() } finally { promptEnded.complete(Unit) }
            }
        }
        entered.await()
        abandoned.cancelAndJoin()
        withTimeout(1_000) { promptEnded.await() }
        assertTrue(request { prompts++; true })
        assertEquals(2, prompts)
    }
}
