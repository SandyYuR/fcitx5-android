/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 固定住 App 会话的 WebSocket 传输与 foxy.websocket 回调分派。
 *
 * 为什么要有：语音会话与 App 会话共用同一段传输，但入站语义不同——语音走 plugin.onMessage 单入口，
 * App 走 onMessage/onBinary/onError/onClose 四个回调。分派错了插件会收不到帧，而这类错误在真机上
 * 只表现为「插件没反应」，很难定位，所以在这里钉住。
 */
class JsWebSocketTransportTest {

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

    @Test fun callbacksAreRegisteredByKindAndDispatchThroughInvoke() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            js.evaluate<String>(
                """(function () {
                    foxy.websocket.onMessage(function (socket, message) { return 'message:' + socket + ':' + message; });
                    foxy.websocket.onBinary(function (socket, base64) { return 'binary:' + socket + ':' + base64; });
                    foxy.websocket.onError(function (socket, message) { return 'error:' + socket + ':' + message; });
                    foxy.websocket.onClose(function (socket, wasClean) { return 'close:' + socket + ':' + wasClean; });
                    return 'ok';
                })()"""
            )
            suspend fun invoke(kind: String, payload: String) = js.evaluate<String>(
                "String(__invoke('ws', {kind:'" + kind + "',socket:'s1',payload:" + payload + "}))"
            )
            assertEquals("message:s1:hi", invoke("message", "'hi'"))
            assertEquals("binary:s1:AAEC", invoke("binary", "'AAEC'"))
            assertEquals("error:s1:boom", invoke("error", "'boom'"))
            assertEquals("close:s1:true", invoke("close", "true"))
        } finally { js.close() }
    }

    @Test fun unregisteredCallbacksAreSilentlyDropped() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            assertEquals(
                "null",
                js.evaluate<String>("String(__invoke('ws', {kind:'message',socket:'s1',payload:'hi'}))")
            )
        } finally { js.close() }
    }

    @Test fun framesReachTheTransportAsInboundEvents() = runBlocking {
        val (server, client) = tlsServer()
        val received = LinkedBlockingQueue<WsInbound>()
        val serverMessages = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                serverMessages += text
            }
        }))
        val headerArgs = mutableListOf<List<String>>()
        var sendAllowed = true
        val transport = JsWebSocketTransport(
            http = client,
            authorizeUrl = { server.url("/ws") },
            authorizeSend = { check(sendAllowed) { "Network permission revoked" } },
            headers = { secretField, authHeader, authPrefix, headersJson ->
                headerArgs += listOf(secretField, authHeader, authPrefix, headersJson)
                emptyMap()
            },
            receive = { received += it; true },
        )
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            transport.install(js)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            val socket = js.evaluate<String>(
                "foxy.websocket.connect('wss://example.test/ws','','','','{}')"
            )
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(listOf("", "", "", "{}")), headerArgs)

            serverSocket!!.send("hello")
            val text = received.poll(5, TimeUnit.SECONDS) as? WsInbound.Text
            assertNotNull(text)
            assertEquals(socket, text!!.socketId)
            assertEquals("hello", text.text)

            serverSocket!!.send("abc".encodeUtf8())
            val binary = received.poll(5, TimeUnit.SECONDS) as? WsInbound.Binary
            assertNotNull(binary)
            assertEquals(socket, binary!!.socketId)
            assertEquals("YWJj", binary.base64)

            js.evaluate<String>("foxy.websocket.send('" + socket + "','ping')")
            assertEquals("ping", serverMessages.poll(5, TimeUnit.SECONDS))

            sendAllowed = false
            val revoked = runCatching {
                js.evaluate<String>("foxy.websocket.send('" + socket + "','after-revoke')")
            }.exceptionOrNull()
            assertTrue(revoked?.message.orEmpty().contains("revoked"))
            sendAllowed = true

            // 服务端主动关闭：客户端应当收到 Closed，并把关闭码带给插件。
            serverSocket!!.close(1000, null)
            val last = received.poll(5, TimeUnit.SECONDS)
            val closed = last as? WsInbound.Closed
            assertNotNull("got " + last, closed)
            assertEquals(1000, closed!!.code)

            // 插件自己关掉之后，这个 socket 就不该再能发送。
            js.evaluate<String>("foxy.websocket.close('" + socket + "')")
            val failure = runCatching {
                js.evaluate<String>("foxy.websocket.send('" + socket + "','after-close')")
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("socket closed"))
        } finally {
            // 先关服务端那条连接再收服务器：MockWebServer 的 shutdown 会等队列排空。
            runCatching { serverSocket?.close(1000, null) }
            transport.closeAll()
            js.close()
            runCatching { server.shutdown() }
        }
    }

    @Test fun connectRefusesHostsThePluginHasNotAuthorized() = runBlocking {
        val transport = JsWebSocketTransport(
            http = OkHttpClient(),
            authorizeUrl = { throw IllegalStateException("Host is not authorized; call foxy.websocket.authorize first") },
            authorizeSend = { },
            headers = { _, _, _, _ -> emptyMap() },
            receive = { true },
        )
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            transport.install(js)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            val failure = runCatching {
                js.evaluate<String>("foxy.websocket.connect('wss://example.test/ws','','','','{}')")
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("not authorized"))
        } finally {
            transport.closeAll()
            js.close()
        }
    }

    @Test fun failedConnectionsReleaseTheirSlotForAReplacement() = runBlocking {
        val (server, client) = tlsServer()
        val received = LinkedBlockingQueue<WsInbound>()
        server.enqueue(MockResponse().setResponseCode(503))
        val opened = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
        }))
        val transport = JsWebSocketTransport(
            http = client,
            authorizeUrl = { server.url("/ws") },
            authorizeSend = { },
            headers = { _, _, _, _ -> emptyMap() },
            receive = { received += it; true },
            maxConnections = 1,
        )
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            transport.install(js)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            js.evaluate<String>("foxy.websocket.connect('wss://example.test/first','','','','{}')")
            assertTrue(received.poll(5, TimeUnit.SECONDS) is WsInbound.Failed)
            val replacement = js.evaluate<String>(
                "foxy.websocket.connect('wss://example.test/replacement','','','','{}')",
            )
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            js.evaluate<String>("foxy.websocket.close('" + replacement + "')")
            Unit
        } finally {
            transport.closeAll()
            js.close()
            runCatching { server.shutdown() }
        }
    }

    @Test fun authorizationIsRecheckedAfterHeadersBeforeConnecting() = runBlocking {
        val (server, client) = tlsServer()
        var allowed = true
        var revokeDuringHeaders = true
        val opened = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
        }))
        val transport = JsWebSocketTransport(
            http = client,
            authorizeUrl = {
                check(allowed) { "Network permission revoked" }
                server.url("/ws")
            },
            authorizeSend = { },
            headers = { _, _, _, _ ->
                if (revokeDuringHeaders) allowed = false
                emptyMap()
            },
            receive = { true },
            maxConnections = 1,
        )
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            transport.install(js)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            val rejected = runCatching {
                js.evaluate<String>("foxy.websocket.connect('wss://example.test/ws','','','','{}')")
            }.exceptionOrNull()
            assertTrue(rejected?.message.orEmpty().contains("revoked"))
            assertEquals(0, server.requestCount)

            allowed = true
            revokeDuringHeaders = false
            val socket = js.evaluate<String>("foxy.websocket.connect('wss://example.test/ws','','','','{}')")
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            js.evaluate<String>("foxy.websocket.close('" + socket + "')")
            Unit
        } finally {
            transport.closeAll()
            js.close()
            runCatching { server.shutdown() }
        }
    }

    @Test fun invalidRequestHeadersDoNotConsumeAConnectionSlot() = runBlocking {
        val (server, client) = tlsServer()
        val opened = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
        }))
        val transport = JsWebSocketTransport(
            http = client,
            authorizeUrl = { server.url("/ws") },
            authorizeSend = { },
            headers = { field, header, prefix, json -> JsVoiceHeaders.build(field, null, header, prefix, json) },
            receive = { true },
            maxConnections = 1,
        )
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            transport.install(js)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            val rejected = runCatching {
                js.evaluate<String>("""foxy.websocket.connect('wss://example.test/ws','','','','{"X-Plugin":"中"}')""")
            }.exceptionOrNull()
            assertNotNull(rejected)
            assertEquals(0, server.requestCount)

            val socket = js.evaluate<String>("foxy.websocket.connect('wss://example.test/ws','','','','{}')")
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            js.evaluate<String>("foxy.websocket.close('" + socket + "')")
            Unit
        } finally {
            transport.closeAll()
            js.close()
            runCatching { server.shutdown() }
        }
    }

    @Test fun rejectedInboundEventClosesAndReleasesTheConnection() = runBlocking {
        val (server, client) = tlsServer()
        val opened = CountDownLatch(1)
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                opened.countDown()
            }
        }))
        val transport = JsWebSocketTransport(
            http = client,
            authorizeUrl = { server.url("/ws") },
            authorizeSend = { },
            headers = { _, _, _, _ -> emptyMap() },
            receive = { false },
            maxConnections = 1,
        )
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            transport.install(js)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            val socket = js.evaluate<String>("foxy.websocket.connect('wss://example.test/ws','','','','{}')")
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            serverSocket!!.send("overflow")
            var released = false
            repeat(100) {
                if (runCatching {
                    js.evaluate<String>("foxy.websocket.send('" + socket + "','probe')")
                }.exceptionOrNull()?.message.orEmpty().contains("socket closed")) {
                    released = true
                    return@repeat
                }
                Thread.sleep(10)
            }
            assertTrue(released)
        } finally {
            transport.closeAll()
            js.close()
            runCatching { serverSocket?.cancel() }
            runCatching { server.shutdown() }
        }
    }
}
