/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import okhttp3.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class BoundedWebSocketTransportTest {
    private data class Outcome(val message: String?, val failure: Throwable?, val request: String, val sni: String?)

    private class Fixture(secure: Boolean, certificateHost: String = "test.example",
                          val extension: Boolean = false, val send: (OutputStream) -> Unit) : AutoCloseable {
        val certificate = HeldCertificate.Builder().commonName(certificateHost).addSubjectAlternativeName(certificateHost).build()
        private val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val socket = if (secure) tls.sslSocketFactory().let {
            val context = javax.net.ssl.SSLContext.getInstance("TLS")
            context.init(arrayOf(tls.keyManager), arrayOf(tls.trustManager), null)
            context.serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress())
        } else ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val port = socket.localPort
        private val finish = CountDownLatch(1)
        @Volatile private var accepted: Socket? = null
        @Volatile var request = ""
        @Volatile var sni: String? = null
        private val worker = thread(name = "bounded-ws-fixture", isDaemon = true) {
            try {
                accepted = socket.accept().apply { soTimeout = 5000 }
                val peer = accepted!!
                val input = peer.getInputStream()
                val header = ByteArrayOutputStream()
                while (header.size() < 32 * 1024) {
                    val b = input.read(); if (b < 0) break
                    header.write(b)
                    if (header.size() >= 4 && header.toString("US-ASCII").endsWith("\r\n\r\n")) break
                }
                request = header.toString("US-ASCII")
                if (peer is SSLSocket) sni = ((peer.session as? ExtendedSSLSession)?.requestedServerNames
                    ?.filterIsInstance<SNIHostName>()?.firstOrNull())?.asciiName
                val key = request.split("\r\n").first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val ext = if (extension) "Sec-WebSocket-Extensions: permessage-deflate\r\n" else ""
                peer.getOutputStream().apply {
                    write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n$ext\r\n").toByteArray())
                    send(this); flush()
                }
                finish.await(15, TimeUnit.SECONDS)
            } catch (_: Exception) { /* Closing sockets and negative TLS fixtures terminate here. */ }
        }
        override fun close() { finish.countDown(); accepted?.close(); socket.close(); worker.join(2000) }
    }

    private fun exercise(secure: Boolean = false, limit: Long = 100, trust: Boolean = true,
                         certificateHost: String = "test.example", extension: Boolean = false,
                         configure: (OkHttpClient.Builder, Int) -> Unit = { _, _ -> },
                         send: (OutputStream) -> Unit): Outcome {
        Fixture(secure, certificateHost, extension, send).use { fixture ->
            val builder = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress())
                }).readTimeout(0, TimeUnit.SECONDS)
            if (secure && trust) {
                val tls = HandshakeCertificates.Builder().addTrustedCertificate(fixture.certificate.certificate).build()
                builder.sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            }
            configure(builder, fixture.port)
            val client = BoundedWebSocketTransport.client(builder.build(), secure, limit)
            val done = CountDownLatch(1)
            var message: String? = null
            var failure: Throwable? = null
            val scheme = if (secure) "wss" else "ws"
            val ws = client.newWebSocket(Request.Builder().url("$scheme://test.example:${fixture.port}/clipsocket").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) { message = text; done.countDown() }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure = t; done.countDown() }
                })
            assertTrue("No bounded outcome", done.await(15, TimeUnit.SECONDS))
            val outcome = Outcome(message, failure, fixture.request, fixture.sni)
            ws.cancel(); client.connectionPool.evictAll()
            return outcome
        }
    }

    @Test fun realPlainSocketRejectsOversizedDeclaredMessageWithoutSendingPayload() {
        val result = exercise { it.write(wsHeader(1, 1L shl 30)) }
        assertTrue(result.failure.toString(), result.failure is ClipCascadeMessageTooLargeException)
        assertNull(result.message)
    }
    @Test fun realTlsSocketRejectsOversizedDeclaredMessageBeforeOnMessage() {
        val result = exercise(secure = true) { it.write(wsHeader(1, 1L shl 30)) }
        assertTrue(result.failure.toString(), result.failure is ClipCascadeMessageTooLargeException)
        assertNull(result.message)
    }
    @Test fun realFragmentationWithPingCannotResetTheCumulativeLimit() {
        val result = exercise { out ->
            out.write(wsHeader(1, 70, false)); out.write(ByteArray(70) { 65 })
            out.write(wsHeader(9, 0)); out.write(wsHeader(0, 40))
        }
        assertTrue(result.failure.toString(), result.failure is ClipCascadeMessageTooLargeException)
        assertNull(result.message)
    }
    @Test fun normalFragmentedUtf8MessagesStillArrive() {
        val text = "你好😀".toByteArray()
        val result = exercise { out ->
            out.write(wsHeader(1, 4, false)); out.write(text, 0, 4) // split inside UTF-8 character
            out.write(wsHeader(9, 0)); out.write(wsHeader(0, (text.size - 4).toLong())); out.write(text, 4, text.size - 4)
        }
        assertNull(result.failure); assertEquals("你好😀", result.message)
    }
    @Test fun tlsRetainsSniCookieJarAndDefaultHostnameVerification() {
        val result = exercise(secure = true, configure = { builder, _ ->
            builder.cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { }
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("fixture").value("yes").hostOnlyDomain(url.host).build())
            })
        }) { it.write(wsHeader(1, 2)); it.write("ok".toByteArray()) }
        assertNull(result.failure); assertEquals("ok", result.message)
        assertEquals("test.example", result.sni)
        assertTrue(result.request, result.request.contains("Cookie: fixture=yes", true))
    }
    @Test fun compressionOfferIsRemovedFromTheActualUpgradeRequest() {
        val result = exercise { it.write(wsHeader(1, 2)); it.write("ok".toByteArray()) }
        assertNull(result.failure)
        assertFalse(result.request, result.request.contains("Sec-WebSocket-Extensions", true))
    }
    @Test fun unsolicitedCompressionResponseIsRejected() {
        val result = exercise(extension = true) { it.write(byteArrayOf(0xc1.toByte(), 0)) }
        assertNotNull(result.failure); assertNull(result.message)
    }
    @Test fun untrustedCertificateStillFails() {
        val result = exercise(secure = true, trust = false) { }
        assertNotNull(result.failure); assertNull(result.message)
    }
    @Test fun trustedCertificateForTheWrongHostnameStillFails() {
        val result = exercise(secure = true, certificateHost = "other.example") { }
        assertTrue(result.failure.toString(), result.failure is javax.net.ssl.SSLPeerUnverifiedException)
        assertNull(result.message)
    }
    @Test fun httpProxyDoesNotBypassThePlainSocketGuard() {
        val result = exercise(configure = { builder, port ->
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), port)))
        }) { it.write(wsHeader(1, 1L shl 30)) }
        assertTrue(result.failure.toString(), result.failure is ClipCascadeMessageTooLargeException)
    }
    @Test fun explicitPlainSocksFailsBeforeAnUnguardedUpgradeRequest() {
        val result = exercise(configure = { builder, port ->
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getLoopbackAddress(), port)))
        }) { }
        assertTrue(result.failure.toString(), result.failure?.message?.contains("SOCKS") == true)
        assertEquals("", result.request)
    }
    @Test fun selectedPlainSocksFailsWithoutSilentlyFallingBackToDirect() {
        val result = exercise(configure = { builder, port ->
            builder.proxy(null).proxySelector(object : ProxySelector() {
                override fun select(uri: URI) = listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getLoopbackAddress(), port)))
                override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) { }
            })
        }) { }
        assertTrue(result.failure.toString(), result.failure?.message?.contains("SOCKS") == true)
        assertEquals("", result.request)
    }
}
