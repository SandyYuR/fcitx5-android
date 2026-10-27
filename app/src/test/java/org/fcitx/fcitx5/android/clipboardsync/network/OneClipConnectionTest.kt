/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OneClipConnectionTest {
    private class Endpoint(private val warmups: Int = 0, private val streamHeaders: Boolean = false) : Closeable {
        private val server = ServerSocket(0)
        private val sockets = CopyOnWriteArrayList<Socket>()
        val reachedSilentRequest = CountDownLatch(1)
        val silentRequestClosed = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}"
        private val worker = thread(isDaemon = true, name = "oneclip-test-server") {
            runCatching {
                var ordinal = 0
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        sockets.add(socket)
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) { }
                        ordinal++
                        if (ordinal <= warmups) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            if (streamHeaders) socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            reachedSilentRequest.countDown()
                            try { while (input.read() >= 0) { } } finally { silentRequestClosed.countDown() }
                        }
                    }
                }
            }
        }
        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            worker.join(1000)
        }
    }
    private fun client(url: String, httpTimeoutMs: Long = 5000, handshakeMs: Long = 5000): OneClipEventClient {
        val http = OkHttpClient.Builder().readTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS).build()
        val sse = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        return OneClipEventClient(url, http, sse, handshakeMs)
    }
    @Test fun silentWarmupHasAFiniteTimeout() = runBlocking {
        Endpoint().use { server ->
            val client = client(server.url, httpTimeoutMs = 250)
            try {
                val result = withTimeout(5000) { runCatching { client.connect {} } }
                assertTrue(result.exceptionOrNull() is IOException)
                assertFalse(client.isConnected())
            } finally { client.close() }
        }
    }
    @Test fun closeCancelsTheCurrentlyRunningWarmup() = runBlocking {
        Endpoint().use { server ->
            val client = client(server.url)
            try {
                val result = async(Dispatchers.IO) { runCatching { client.connect {} } }
                assertTrue(server.reachedSilentRequest.await(5, TimeUnit.SECONDS))
                client.close()
                assertTrue(withTimeout(2000) { result.await() }.exceptionOrNull() is IOException)
                assertTrue(server.silentRequestClosed.await(2, TimeUnit.SECONDS))
            } finally { client.close() }
        }
    }
    @Test fun coroutineCancellationAlsoCancelsWarmup() = runBlocking {
        Endpoint().use { server ->
            val client = client(server.url)
            try {
                val job = launch(Dispatchers.IO) { client.connect {} }
                assertTrue(server.reachedSilentRequest.await(5, TimeUnit.SECONDS))
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(server.silentRequestClosed.await(2, TimeUnit.SECONDS))
            } finally { client.close() }
        }
    }
    @Test fun sseHeadersHaveAHandshakeDeadline() = runBlocking {
        Endpoint(warmups = 2).use { server ->
            val client = client(server.url, handshakeMs = 250)
            try {
                val result = withTimeout(5000) { runCatching { client.connect {} } }
                assertTrue(result.exceptionOrNull() is IOException)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("handshake"))
                assertTrue(server.silentRequestClosed.await(2, TimeUnit.SECONDS))
            } finally { client.close() }
        }
    }
    @Test fun aConnectedQuietSseStreamOutlivesItsHandshakeDeadline() = runBlocking {
        Endpoint(warmups = 2, streamHeaders = true).use { server ->
            val client = client(server.url, handshakeMs = 250)
            try {
                withTimeout(5000) { client.connect {} }
                delay(400)
                assertTrue(client.isConnected())
            } finally { client.close() }
        }
    }
    @Test fun reconnectsShareTransportsButKeepSeparateCookieJars() {
        fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as OkHttpClient
        val first = field(OneClipEventClient("http://localhost"), "httpClient")
        val second = field(OneClipEventClient("http://localhost"), "httpClient")
        assertSame(first.connectionPool, second.connectionPool)
        val cascadeA = field(ClipCascadeClient("http://localhost", "a", "a"), "client")
        val cascadeB = field(ClipCascadeClient("http://localhost", "b", "b"), "client")
        val loginA = field(ClipCascadeClient("http://localhost", "a", "a"), "httpClient")
        val loginB = field(ClipCascadeClient("http://localhost", "b", "b"), "httpClient")
        // Login TCP/TLS connections still reuse the shared transport. A WebSocket
        // upgrade must never borrow an unguarded login socket (A9).
        assertSame(loginA.connectionPool, loginB.connectionPool)
        assertNotSame(cascadeA.connectionPool, loginA.connectionPool)
        assertNotSame(cascadeB.connectionPool, loginB.connectionPool)
        assertSame(cascadeA.dispatcher, cascadeB.dispatcher)
        assertSame(cascadeA.dispatcher, loginA.dispatcher)
        assertNotSame(cascadeA.cookieJar, cascadeB.cookieJar)
    }
}
