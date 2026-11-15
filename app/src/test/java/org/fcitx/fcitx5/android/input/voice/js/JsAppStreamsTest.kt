/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 固定住 foxy.http.stream 的宿主行为：SSE 解析、纯文本逐行、错误与取消。
 *
 * 为什么要有：流式接口是「边收边发」的，插件按 (id, event, data) 拼结果；解析规则或事件名
 * 一旦偏离，插件只会表现为「答非所问」，在真机上极难定位。
 */
class JsAppStreamsTest {

    private fun tlsServer(): Pair<MockWebServer, OkHttpClient> {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        return server to OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun newStreams(
        scope: CoroutineScope,
        client: OkHttpClient,
        checkUrl: (String) -> okhttp3.HttpUrl,
        secret: (String) -> String? = { null },
        approve: suspend (okhttp3.HttpUrl) -> Boolean = { true },
        dispatch: suspend (StreamEvent, () -> Boolean) -> Unit,
    ) = JsAppStreams(
        scope = scope,
        checkUrl = checkUrl,
        secret = secret,
        approve = approve,
        dispatch = dispatch,
        client = client,
    )

    @Test fun sseEventsAreParsedAndDispatchedInOrder() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: delta\ndata: he\ndata: llo\n\ndata: world\n\n")
        )
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            val id = streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            val first = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk
            assertNotNull(first)
            assertEquals(id, first!!.streamId)
            assertEquals("delta", first.event)
            assertEquals("he\nllo", first.data)
            val second = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk
            assertNotNull(second)
            assertEquals("message", second!!.event)
            assertEquals("world", second.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun plainResponsesAreDeliveredLineByLine() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("alpha\nbeta\n"))
        val streams = newStreams(scope, client, { server.url("/lines") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            val first = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk
            assertEquals("line", first!!.event)
            assertEquals("alpha", first.data)
            val second = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk
            assertEquals("beta", second!!.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun httpErrorsAreReportedAsFailed() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setResponseCode(500))
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            val id = streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            val failed = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertNotNull(failed)
            assertEquals(id, failed!!.streamId)
            assertTrue(failed.message.contains("500"))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun deniedApprovalFailsWithoutSending() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val streams = newStreams(scope, client, { server.url("/sse") }, approve = { false }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            val failed = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertNotNull(failed)
            assertEquals("Network permission denied", failed!!.message)
            assertEquals(0, server.requestCount)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun cancellingAPendingRequestDeliversNothing() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            val id = streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            streams.cancel(id)
            assertNull(events.poll(1, TimeUnit.SECONDS))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun credentialsAreInjectedAndOptionsAreValidated() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("done\n"))
        val secrets = mutableListOf<String>()
        val streams = newStreams(scope, client, { server.url("/sse") }, secret = { key -> secrets += key; "token-1" }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject {
                put("url", server.url("/sse").toString())
                put("method", "POST")
                put("body", "{}")
                put("credential", "apiKey")
            })
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.Chunk)
            assertEquals(listOf("apiKey"), secrets)
            assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))

            // 非 HTTPS、未知方法与未声明的密钥都在同步阶段拒绝，不会建连。
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { streams.start(buildJsonObject { put("url", "http://example.test/sse") }) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { streams.start(buildJsonObject {
                    put("url", server.url("/sse").toString()); put("method", "TRACE")
                }) }
            }
            Unit
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun cancellingFromTheFirstChunkSuppressesBufferedChunksAndEnd() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("first\nsecond\n"))
        lateinit var streams: JsAppStreams
        streams = newStreams(scope, client, { server.url("/lines") }, dispatch = { event, _ ->
            events += event
            if (event is StreamEvent.Chunk) streams.cancel(event.streamId)
        })
        try {
            streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            assertEquals("first", (events.poll(5, TimeUnit.SECONDS) as StreamEvent.Chunk).data)
            assertNull(events.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun cancellationWhileAnEventWaitsForDispatchSuppressesThatEvent() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("queued\n"))
        val streams = newStreams(scope, client, { server.url("/lines") }, dispatch = { event, isActive ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            if (isActive()) events += event
            completed.complete(Unit)
        })
        try {
            val id = streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            withTimeout(5_000) { entered.await() }
            streams.cancel(id)
            release.complete(Unit)
            withTimeout(5_000) { completed.await() }
            assertNull(events.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun cumulativeSseEventSizeIsBounded() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val half = "x".repeat(JsVoiceRuntime.RESPONSE_BYTES / 2)
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $half\ndata: $half\n\n"),
        )
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            val failed = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertNotNull(failed)
            assertTrue(failed!!.message.contains("event is too large"))
            assertNull(events.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun eventNameAndDataShareOneSseEventBudget() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val half = "x".repeat(JsVoiceRuntime.RESPONSE_BYTES / 2)
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: $half\ndata: ${half}x\n\n"),
        )
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            val failed = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertTrue(failed?.message.orEmpty().contains("event is too large"))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun approvalPrecedesSecretAccessAndCancellationStopsApproval() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        var secretReads = 0
        var streams = newStreams(
            scope,
            client,
            { server.url("/sse") },
            secret = { secretReads++; "token" },
            approve = {
                assertEquals(0, secretReads)
                false
            },
            dispatch = { event, _ -> events += event },
        )
        try {
            streams.start(buildJsonObject {
                put("url", server.url("/sse").toString())
                put("credential", "apiKey")
            })
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.Failed)
            assertEquals(0, secretReads)
            assertEquals(0, server.requestCount)
        } finally {
            streams.closeAll()

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Boolean>()
            val approvalFinished = CompletableDeferred<Unit>()
            streams = newStreams(
                scope,
                client,
                { server.url("/sse") },
                secret = { secretReads++; "token" },
                approve = {
                    entered.complete(Unit)
                    try { release.await() } finally { approvalFinished.complete(Unit) }
                },
                dispatch = { event, _ -> events += event },
            )
            val id = streams.start(buildJsonObject {
                put("url", server.url("/sse").toString())
                put("credential", "apiKey")
            })
            entered.await()
            streams.cancel(id)
            release.complete(true)
            withTimeout(1_000) { approvalFinished.await() }
            assertEquals(0, secretReads)
            assertEquals(0, server.requestCount)
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun concurrentStreamsAreBoundedAndCancellationReleasesASlot() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        repeat(JsAppStreams.MAX_STREAMS + 1) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        }
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { _, _ -> })
        try {
            val ids = (0 until JsAppStreams.MAX_STREAMS).map {
                streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            }
            assertTrue(runCatching {
                streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            }.exceptionOrNull()?.message.orEmpty().contains("Too many HTTP streams"))
            streams.cancel(ids.first())
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            Unit
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }
}

/** 固定住 foxy.http.onChunk/onEnd/onError 的分派形状。 */
class JsAppStreamCallbacksTest {

    @Test fun callbacksAreRegisteredByKindAndDispatchThroughInvoke() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            js.evaluate<String>(
                """(function () {
                    foxy.http.onChunk(function (id, event, data) { return 'chunk:' + id + ':' + event + ':' + data; });
                    foxy.http.onEnd(function (id) { return 'end:' + id; });
                    foxy.http.onError(function (id, message) { return 'error:' + id + ':' + message; });
                    return 'ok';
                })()"""
            )
            suspend fun invoke(kind: String, rest: String) = js.evaluate<String>(
                "String(__invoke('stream', {kind:'" + kind + "',id:'s1'" + rest + "}))"
            )
            assertEquals("chunk:s1:delta:hi", invoke("chunk", ",event:'delta',data:'hi'"))
            assertEquals("end:s1", invoke("end", ""))
            assertEquals("error:s1:boom", invoke("error", ",payload:'boom'"))
        } finally { js.close() }
    }

    @Test fun unregisteredCallbacksAreSilentlyDropped() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            assertEquals(
                "null",
                js.evaluate<String>("String(__invoke('stream', {kind:'chunk',id:'s1',event:'delta',data:'hi'}))")
            )
        } finally { js.close() }
    }
}
