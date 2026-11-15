/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class JsNetworkCloseRaceTest {
    /** Reproduce removal after Collection.toList reads size=1, before it obtains an iterator. */
    private class RemovingLastEntryMap : ConcurrentHashMap<String, Any>() {
        override val entries: MutableSet<MutableMap.MutableEntry<String, Any>>
            get() {
                val view = super.entries
                return object : AbstractMutableSet<MutableMap.MutableEntry<String, Any>>() {
                    override val size: Int
                        get() = view.size.also { this@RemovingLastEntryMap.clear() }
                    override fun iterator() = view.iterator()
                    override fun add(element: MutableMap.MutableEntry<String, Any>): Boolean =
                        throw UnsupportedOperationException()
                }
            }
    }

    /** Pause the real registration after its closed check, but before its map insertion. */
    private class PausedRegistrationMap : ConcurrentHashMap<String, Any>() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var registered: Any? = null
        override fun put(key: String, value: Any): Any? {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Registration was not released" }
            return super.put(key, value).also { registered = value }
        }
    }

    private class TestSocket : WebSocket {
        val cancelled = AtomicBoolean()
        override fun request() = Request.Builder().url("https://example.test/socket").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled.set(true) }
    }

    private fun streams(scope: CoroutineScope) = JsAppStreams(
        scope = scope,
        checkUrl = { it.toHttpUrl() },
        secret = { null },
        approve = { awaitCancellation() },
        authorizeSend = { },
        dispatch = { _, _ -> error("An unapproved stream must not dispatch") },
    )

    private fun sockets() = JsWebSocketTransport(
        http = OkHttpClient.Builder().addInterceptor {
            throw IOException("Test interceptor prevents network access")
        }.build(),
        authorizeUrl = { "https://example.test/socket".toHttpUrl() },
        authorizeSend = { },
        headers = { _, _, _, _ -> emptyMap() },
        receive = { true },
    )

    private fun replaceMap(owner: Any, field: String, map: ConcurrentHashMap<String, Any>) {
        owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(owner, map)
    }

    private fun invoke(owner: Any, name: String, vararg args: Any): Any? = try {
        owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(owner, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    @Test fun closingStreamsSurvivesRemovalBetweenSnapshotSizeAndIteration() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val stopped = CompletableDeferred<Unit>()
        val waiting = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val streams = streams(scope)
        val state = Class.forName(JsAppStreams::class.java.name + "\$ActiveStream")
            .getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance("1")
        invoke(state, "attachJob", waiting as Job)
        val map = RemovingLastEntryMap().apply { put("1", state) }
        replaceMap(streams, "activeStreams", map)
        try {
            streams.closeAll()
            withTimeout(5_000) { stopped.await(); waiting.join() }
            assertTrue(waiting.isCancelled)
            assertTrue(map.isEmpty())
        } finally {
            scope.cancel()
            withTimeout(5_000) { waiting.join() }
        }
    }

    @Test fun closingSocketsSurvivesRemovalBetweenSnapshotSizeAndIteration() {
        val transport = sockets()
        val socket = TestSocket()
        val connection = Class.forName(JsWebSocketTransport::class.java.name + "\$Connection")
            .getDeclaredConstructor(HttpUrl::class.java).apply { isAccessible = true }
            .newInstance("https://example.test/socket".toHttpUrl())
        invoke(connection, "attach", socket)
        val map = RemovingLastEntryMap().apply { put("1", connection) }
        replaceMap(transport, "connections", map)
        try {
            transport.closeAll()
            assertTrue(socket.cancelled.get())
            assertTrue(map.isEmpty())
        } finally {
            socket.cancel()
        }
    }

    @Test fun closingStreamsCannotMissAnInFlightRegistration() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val streams = streams(scope)
        val map = PausedRegistrationMap()
        replaceMap(streams, "activeStreams", map)
        val options = buildJsonObject { put("url", "https://example.test/sse") }
        try {
            assertRegistrationIsDrained(map, { streams.start(options) }, streams::closeAll)
        } finally {
            scope.cancel()
        }
    }

    @Test fun closingSocketsCannotMissAnInFlightRegistration() {
        val transport = sockets()
        val map = PausedRegistrationMap()
        replaceMap(transport, "connections", map)
        assertRegistrationIsDrained(map, {
            invoke(transport, "connect", "wss://example.test/socket", "", "", "", "{}")
        }, transport::closeAll)
    }

    private fun assertRegistrationIsDrained(
        map: PausedRegistrationMap,
        register: () -> Any?,
        close: () -> Unit,
    ) {
        val registerFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val closeEntered = CountDownLatch(1)
        val registering = Thread({
            try { register() } catch (error: Throwable) { registerFailure.set(error) }
        }, "js-network-registration-test").apply { isDaemon = true }
        val closing = Thread({
            closeEntered.countDown()
            try { close() } catch (error: Throwable) { closeFailure.set(error) }
        }, "js-network-close-test").apply { isDaemon = true }
        registering.start()
        try {
            assertTrue(map.entered.await(5, TimeUnit.SECONDS))
            closing.start()
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closing.isAlive && closing.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("Close must wait for the checked registration to finish", Thread.State.BLOCKED, closing.state)
            map.release.countDown()
            registering.join(5_000)
            closing.join(5_000)
            assertFalse(registering.isAlive)
            assertFalse(closing.isAlive)
            assertNull(closeFailure.get())
            registerFailure.get()?.let { failure ->
                // Close may deactivate a stream between insertion and attaching its lazy job.
                assertTrue(failure.toString(), failure is IllegalStateException && failure.message == "Session is closed")
            }
            assertTrue("Close must drain the registration it waited for", map.isEmpty())
            assertEquals(false, invoke(requireNotNull(map.registered), "isActive"))
            assertThrows(IllegalStateException::class.java) { register() }
        } finally {
            map.release.countDown()
            for (thread in listOf(registering, closing)) {
                if (thread.state == Thread.State.NEW) continue
                thread.join(5_000)
                if (thread.isAlive) { thread.interrupt(); thread.join(1_000) }
            }
            // An intentionally broken close must not leave cleanup waiting on its map monitor.
            if (!registering.isAlive && !closing.isAlive) runCatching { close() }
            assertFalse("Registration thread did not terminate", registering.isAlive)
            assertFalse("Close thread did not terminate", closing.isAlive)
        }
    }
}
