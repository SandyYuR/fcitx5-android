/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
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

    private fun clientWithSseSource(source: Source): OkHttpClient {
        val buffered = source.buffer()
        return OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = buffered
                }).build()
        }.build()
    }

    private fun newStreams(
        scope: CoroutineScope,
        client: OkHttpClient,
        checkUrl: (String) -> okhttp3.HttpUrl,
        secret: (String) -> String? = { null },
        approve: suspend (okhttp3.HttpUrl) -> Boolean = { true },
        authorizeSend: (okhttp3.HttpUrl) -> Unit = { },
        dispatch: suspend (StreamEvent, () -> Boolean) -> Unit,
    ) = JsAppStreams(
        scope = scope,
        checkUrl = checkUrl,
        secret = secret,
        approve = approve,
        authorizeSend = authorizeSend,
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

    @Test fun plainResponseWithoutATrailingNewlineDeliversItsFinalLine() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("alpha\n尾行"))
        val streams = newStreams(scope, client, { server.url("/lines") }, dispatch = { event, _ -> events += event })
        try {
            val id = streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            assertEquals("alpha", (events.poll(5, TimeUnit.SECONDS) as StreamEvent.Chunk).data)
            val tail = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk
            assertNotNull(tail)
            assertEquals(id, tail!!.streamId)
            assertEquals("line", tail.event)
            assertEquals("尾行", tail.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun plainLineBudgetIncludesTheFinalLineAndAllowsTheExactLimit() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val fullLine = "x".repeat(JsVoiceRuntime.RESPONSE_BYTES)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(fullLine))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(fullLine + "x"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(fullLine + "\n"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(fullLine + "\r\n"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(fullLine + "\r"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(fullLine + "x\r\n"))
        val streams = newStreams(scope, client, { server.url("/lines") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            assertEquals(fullLine, (events.poll(5, TimeUnit.SECONDS) as StreamEvent.Chunk).data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)

            streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            val failed = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertTrue(failed?.message.orEmpty().contains("line is too long"))

            streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            assertEquals(fullLine, (events.poll(5, TimeUnit.SECONDS) as StreamEvent.Chunk).data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)

            repeat(2) {
                streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
                assertEquals(fullLine, (events.poll(5, TimeUnit.SECONDS) as StreamEvent.Chunk).data)
                assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
            }
            streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            val tooLong = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertTrue(tooLong?.message.orEmpty().contains("line is too long"))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun sseEmptyDataLinesAndTheUnterminatedFinalEventArePreserved() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data:\r\n\r\ndata:\r\ndata: content\r\ndata:\r\n\r\nevent: delta\r\ndata: tail"))
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            assertEquals("", (events.poll(5, TimeUnit.SECONDS) as StreamEvent.Chunk).data)
            assertEquals("\ncontent\n", (events.poll(5, TimeUnit.SECONDS) as StreamEvent.Chunk).data)
            val final = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk
            assertNotNull(final)
            assertEquals("delta", final!!.event)
            assertEquals("tail", final.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun sseIgnoresOnlyTheInitialByteOrderMark() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("\uFEFFdata: first\n\ndata: \uFEFFsecond\n\n"))
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            assertEquals("first", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertEquals("\uFEFFsecond", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun sseCarriageReturnEventDoesNotReadAheadForALineFeedOrEof() = runBlocking {
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val source = object : Source {
            private val chunk = Buffer().writeUtf8("data: first\r\r")
            override fun read(sink: Buffer, byteCount: Long): Long {
                check(chunk.size > 0L) { "Read beyond a complete CR-delimited event before dispatch" }
                return chunk.read(sink, byteCount)
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val client = clientWithSseSource(source)
        lateinit var streams: JsAppStreams
        streams = newStreams(scope, client, JsAppEndpointPolicy(setOf("example.test"), emptyMap())::checkUrl,
            dispatch = { event, _ ->
                events += event
                if (event is StreamEvent.Chunk) streams.cancel(event.streamId)
            })
        try {
            streams.start(buildJsonObject { put("url", "https://example.test/sse") })
            assertEquals("first", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertNull(events.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            streams.closeAll()
            scope.cancel()
        }
    }

    @Test fun sseCrLfAcrossResponseChunksRemainsOneLineEnding() = runBlocking {
        val (server, baseClient) = tlsServer()
        // MockWebServer's chunked body includes HTTP/1.1 framing. HTTP/2 would deliver
        // that framing as payload instead of decoding it as a chunked response.
        server.protocols = listOf(Protocol.HTTP_1_1)
        val client = baseClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setChunkedBody("\uFEFFdata: first\r\ndata: second\r\n\r\ndata: tail\r", 1))
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            val firstEvent = events.poll(5, TimeUnit.SECONDS)
            assertTrue("Expected chunk, got ${firstEvent?.javaClass?.simpleName}: " +
                (firstEvent as? StreamEvent.Failed)?.message.orEmpty(), firstEvent is StreamEvent.Chunk)
            assertEquals("first\nsecond", (firstEvent as StreamEvent.Chunk).data)
            assertEquals("tail", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun sseByteOrderMarkAndCrLfCanArriveOneByteAtATime() = runBlocking {
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val payload = Buffer().writeUtf8("\uFEFFdata: first\r\ndata: second\r\n\r\ndata: tail\r")
        // HTTP chunks may be combined before reaching Okio. This source guarantees every
        // byte of the BOM and every CRLF pair crosses a source-read boundary.
        val client = clientWithSseSource(object : Source {
            override fun read(sink: Buffer, byteCount: Long) = payload.read(sink, minOf(byteCount, 1L))
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        })
        val streams = newStreams(scope, client, JsAppEndpointPolicy(setOf("example.test"), emptyMap())::checkUrl,
            dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", "https://example.test/sse") })
            assertEquals("first\nsecond", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertEquals("tail", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
        }
    }

    @Test fun sseCrDelimitedLinesKeepTheByteBudget() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val comment = ":" + "x".repeat(JsVoiceRuntime.RESPONSE_BYTES - 1)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("\uFEFF$comment\rdata: accepted\r\r"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("${comment}x\rdata: rejected\r\r"))
        val streams = newStreams(scope, client, { server.url("/sse") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            assertEquals("accepted", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
            streams.start(buildJsonObject { put("url", server.url("/sse").toString()) })
            val failed = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertTrue(failed?.message.orEmpty().contains("line is too long"))
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun plainResponsesPreserveByteOrderMarksAndEmbeddedCarriageReturns() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain")
            .setBody("\uFEFFfirst\rsecond\n\uFEFFtail"))
        val streams = newStreams(scope, client, { server.url("/lines") }, dispatch = { event, _ -> events += event })
        try {
            streams.start(buildJsonObject { put("url", server.url("/lines").toString()) })
            assertEquals("\uFEFFfirst\rsecond", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertEquals("\uFEFFtail", (events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Chunk)?.data)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is StreamEvent.End)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun endpointIsRecheckedAfterCredentialLookupBeforeSending() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        var endpointAllowed = true
        val streams = newStreams(
            scope,
            client,
            checkUrl = {
                check(endpointAllowed) { "Endpoint is no longer configured" }
                server.url("/sse")
            },
            secret = { endpointAllowed = false; "token" },
            dispatch = { event, _ -> events += event },
        )
        try {
            streams.start(buildJsonObject {
                put("url", server.url("/sse").toString())
                put("credential", "apiKey")
            })
            val failed = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertTrue(failed?.message.orEmpty().contains("no longer configured"))
            assertEquals(0, server.requestCount)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun hostApprovalIsRecheckedBeforeAndAfterCredentialLookup() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        var approved = true
        var revokeDuringApproval = true
        var secretReads = 0
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("done\n")) }
        val streams = newStreams(
            scope,
            client,
            { server.url("/sse") },
            secret = { secretReads++; approved = false; "token" },
            approve = { if (revokeDuringApproval) approved = false; true },
            authorizeSend = { check(approved) { "Network permission revoked" } },
            dispatch = { event, _ -> events += event },
        )
        try {
            val options = buildJsonObject {
                put("url", server.url("/sse").toString())
                put("credential", "apiKey")
            }
            streams.start(options)
            val beforeSecret = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertTrue(beforeSecret?.message.orEmpty().contains("permission revoked"))
            assertEquals(0, secretReads)
            assertEquals(0, server.requestCount)

            approved = true
            revokeDuringApproval = false
            streams.start(options)
            val afterSecret = events.poll(5, TimeUnit.SECONDS) as? StreamEvent.Failed
            assertTrue(afterSecret?.message.orEmpty().contains("permission revoked"))
            assertEquals(1, secretReads)
            assertEquals(0, server.requestCount)
        } finally {
            streams.closeAll()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun scopeCancellationDuringCredentialLookupPreventsSendingAndCallbacks() = runBlocking {
        val (server, client) = tlsServer()
        val scope = scope()
        val events = LinkedBlockingQueue<StreamEvent>()
        val secretRead = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("done\n"))
        val streams = newStreams(
            scope,
            client,
            { server.url("/sse") },
            secret = { scope.cancel(); secretRead.complete(Unit); "token" },
            dispatch = { event, _ -> events += event },
        )
        try {
            streams.start(buildJsonObject {
                put("url", server.url("/sse").toString())
                put("credential", "apiKey")
            })
            withTimeout(5_000) {
                secretRead.await()
                requireNotNull(scope.coroutineContext[Job]).join()
            }
            assertEquals(0, server.requestCount)
            assertTrue(events.isEmpty())
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
