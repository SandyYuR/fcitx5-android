/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.utf8Size
import org.fcitx.fcitx5.android.clipboardsync.network.BoundedWebSocketTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 一条从 WebSocket 回到脚本的入站事件。 */
internal sealed interface WsInbound {
    val socketId: String

    class Text(override val socketId: String, val text: String) : WsInbound
    class Binary(override val socketId: String, val base64: String) : WsInbound
    class Failed(override val socketId: String, val message: String) : WsInbound
    class Closed(override val socketId: String, val code: Int) : WsInbound
}

/**
 * 语音会话与 App 会话共用的一份 WebSocket 传输。
 *
 * 目标 URL 与连接一起保存，使每次发送都能重新检查当前授权。入站接收方返回 false 表示其有界
 * 队列已满；传输随即取消并释放该连接，避免 OkHttp 回调把 JVM 内存变成无界缓冲。
 */
internal class JsWebSocketTransport(
    http: OkHttpClient,
    private val authorizeUrl: (String) -> HttpUrl,
    private val authorizeSend: (HttpUrl) -> Unit,
    private val headers: (secretField: String, authHeader: String, authPrefix: String, headersJson: String) -> Map<String, String>,
    private val receive: (WsInbound) -> Boolean,
    private val maxConnections: Int = 4,
) {
    private class Connection(val destination: HttpUrl) {
        val opened = CompletableDeferred<Unit>()
        private val active = AtomicBoolean(true)
        private val closeNotified = AtomicBoolean(false)
        @Volatile private var socket: WebSocket? = null

        fun isActive(): Boolean = active.get()

        @Synchronized fun attach(value: WebSocket): Boolean {
            if (!active.get()) return false
            socket = value
            return true
        }

        @Synchronized fun socketForSend(): WebSocket =
            socket?.takeIf { active.get() } ?: throw IllegalStateException("socket closed")

        @Synchronized fun deactivate(error: Throwable? = null): WebSocket? {
            if (!active.compareAndSet(true, false)) return null
            if (error == null) opened.cancel() else opened.completeExceptionally(error)
            return socket.also { socket = null }
        }

        fun markCloseNotified(): Boolean = closeNotified.compareAndSet(false, true)
    }

    private val closed = AtomicBoolean(false)
    private val socketHttp by lazy {
        BoundedWebSocketTransport.client(http, secure = true, maxMessage = JsVoiceRuntime.WEBSOCKET_BYTES)
    }
    private val connections = ConcurrentHashMap<String, Connection>()
    private val nextId = AtomicInteger(1)

    fun install(js: QuickJs) {
        js.function("__n_wsConnect") { args ->
            connect(
                url = text(args, 0),
                secretField = text(args, 1),
                authHeader = text(args, 2),
                authPrefix = text(args, 3),
                headersJson = text(args, 4),
            )
        }
        js.asyncFunction("__n_wsWaitOpen") { args -> awaitOpen(text(args, 0)); null }
        js.function("__n_wsSend") { args ->
            val connection = connectionForSend(text(args, 0))
            val message = text(args, 1)
            require(message.utf8Size() <= JsVoiceRuntime.WEBSOCKET_BYTES) { "WebSocket message exceeds its limit" }
            check(connection.socketForSend().send(message)) { "WebSocket send failed" }
            null
        }
        js.function("__n_wsSendBinary") { args ->
            val connection = connectionForSend(text(args, 0))
            check(connection.socketForSend().send(ByteString.of(*decode(text(args, 1))))) { "WebSocket send failed" }
            null
        }
        js.function("__n_wsSendGzipFrame") { args ->
            val connection = connectionForSend(text(args, 0))
            val frame = JsVoiceHelpers.gzipFrame(decode(text(args, 1)), decode(text(args, 2)))
            check(connection.socketForSend().send(ByteString.of(*frame))) { "WebSocket send failed" }
            null
        }
        js.function("__n_wsClose") { args ->
            val id = text(args, 0)
            val connection = connections.remove(id)
            connection?.deactivate()?.close(1000, null)
            if (connection?.markCloseNotified() == true) receive(WsInbound.Closed(id, 1000))
            null
        }
    }

    fun closeAll() {
        val pending = synchronized(connections) {
            // Share connect's registration boundary so close cannot miss a checked but
            // not-yet-inserted connection. Failure callbacks may still remove entries.
            if (!closed.compareAndSet(false, true)) return
            val sockets = ArrayList<Connection>()
            connections.forEach { id, connection ->
                if (connections.remove(id, connection)) sockets.add(connection)
            }
            sockets
        }
        // Completing waitOpen and cancelling sockets can resume callbacks; release the map first.
        pending.forEach { it.deactivate()?.cancel() }
    }

    private fun connectionForSend(id: String): Connection {
        val connection = connections[id] ?: throw IllegalStateException("socket closed")
        authorizeSend(connection.destination)
        return connection
    }

    private fun connect(
        url: String,
        secretField: String,
        authHeader: String,
        authPrefix: String,
        headersJson: String,
    ): String {
        val destination = authorizeUrl(url)
        val built = headers(secretField, authHeader, authPrefix, headersJson)
        // Header construction may read credentials from storage; current grants and configured
        // endpoints must still allow this destination when the connection is created.
        check(authorizeUrl(url) == destination) { "Endpoint changed before connecting" }
        // Complete synchronous request validation before reserving a connection slot.
        val request = Request.Builder().url(destination)
            .apply { built.forEach { (name, value) -> header(name, value) } }
            .build()
        val id = nextId.getAndIncrement().toString()
        val connection = Connection(destination)
        synchronized(connections) {
            check(!closed.get()) { "Session closed" }
            check(connections.size < maxConnections) { "Too many WebSocket connections" }
            connections[id] = connection
        }
        val socket = try {
            socketHttp.newWebSocket(request, listener(id, connection))
        } catch (error: Throwable) {
            connections.remove(id, connection)
            connection.deactivate(error)
            throw error
        }
        if (!connection.attach(socket)) socket.cancel()
        return id
    }

    private fun listener(id: String, connection: Connection) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (connection.isActive()) connection.opened.complete(Unit) else webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            deliver(id, connection, WsInbound.Text(id, text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            deliver(id, connection, WsInbound.Binary(id, bytes.base64()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (connections.remove(id, connection)) {
                connection.deactivate(t)
                receive(WsInbound.Failed(id, t.message.orEmpty()))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            if (connection.markCloseNotified()) deliver(id, connection, WsInbound.Closed(id, code))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (connection.markCloseNotified()) deliver(id, connection, WsInbound.Closed(id, code))
            release(id, connection, cancelSocket = false)
        }
    }

    private fun deliver(id: String, connection: Connection, event: WsInbound) {
        if (!connection.isActive()) return
        if (!receive(event)) release(id, connection, cancelSocket = true)
    }

    private fun release(id: String, connection: Connection, cancelSocket: Boolean) {
        if (!connections.remove(id, connection)) return
        val socket = connection.deactivate()
        if (cancelSocket) socket?.cancel()
    }

    private suspend fun awaitOpen(id: String) {
        val gate = connections[id]?.opened ?: throw IllegalStateException("Unknown socket")
        withTimeout(10_000L) { gate.await() }
    }

    private fun text(args: Array<Any?>, index: Int): String = args.getOrNull(index)?.toString().orEmpty()

    private fun decode(value: String): ByteArray {
        require(value.length <= (JsVoiceRuntime.BINARY_BYTES + 2) / 3 * 4) { "Binary input exceeds its limit" }
        val bytes = value.decodeBase64() ?: throw IllegalArgumentException("Invalid base64 input")
        require(bytes.size <= JsVoiceRuntime.BINARY_BYTES) { "Binary input exceeds its limit" }
        return bytes.toByteArray()
    }
}
