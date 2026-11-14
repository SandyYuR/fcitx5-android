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
 * 为什么抽出来：foxy 的 App 也能连 WebSocket，而此前只有语音会话装了真正的传输，App 会话装的是
 * 一碰就抛错的占位实现。两份实现会让「哪些主机允许连」「每次发送前是否复查授权」这类安全判断
 * 分叉，所以传输只保留这一份，策略由构造参数注入：语音会话注入整份清单授权，App 会话注入
 * 「声明主机 + 用户逐主机确认」。
 *
 * 入站事件不在这里决定语义：语音会话要把文本/二进制都交给 plugin.onMessage，App 会话要分到
 * foxy.websocket 的四个回调，所以这里只给出 [WsInbound]，由调用方翻译。
 */
internal class JsWebSocketTransport(
    http: OkHttpClient,
    private val authorizeUrl: (String) -> HttpUrl,
    private val authorizeSend: () -> Unit,
    private val headers: (secretField: String, authHeader: String, authPrefix: String, headersJson: String) -> Map<String, String>,
    private val receive: (WsInbound) -> Unit,
    private val maxConnections: Int = 4,
) {
    private val closed = AtomicBoolean(false)
    private val socketHttp by lazy {
        BoundedWebSocketTransport.client(http, secure = true, maxMessage = JsVoiceRuntime.WEBSOCKET_BYTES)
    }
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val opened = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
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
            authorizeSend()
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            val message = text(args, 1)
            require(message.utf8Size() <= JsVoiceRuntime.WEBSOCKET_BYTES) { "WebSocket message exceeds its limit" }
            check(socket.send(message)) { "WebSocket send failed" }
            null
        }
        js.function("__n_wsSendBinary") { args ->
            authorizeSend()
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            check(socket.send(ByteString.of(*decode(text(args, 1))))) { "WebSocket send failed" }
            null
        }
        js.function("__n_wsSendGzipFrame") { args ->
            authorizeSend()
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            val frame = JsVoiceHelpers.gzipFrame(decode(text(args, 1)), decode(text(args, 2)))
            check(socket.send(ByteString.of(*frame))) { "WebSocket send failed" }
            null
        }
        js.function("__n_wsClose") { args ->
            sockets.remove(text(args, 0))?.close(1000, null)
            null
        }
    }

    fun closeAll() {
        if (!closed.compareAndSet(false, true)) return
        sockets.values.forEach { it.cancel() }
        sockets.clear()
        opened.values.forEach { it.cancel() }
        opened.clear()
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
        val id = nextId.getAndIncrement().toString()
        val gate = CompletableDeferred<Unit>()
        synchronized(opened) {
            check(!closed.get()) { "Session closed" }
            check(opened.size < maxConnections) { "Too many WebSocket connections" }
            opened[id] = gate
        }
        val builder = Request.Builder().url(destination)
        built.forEach { (name, value) -> builder.header(name, value) }
        check(!closed.get()) { "Session closed" }
        val socket = socketHttp.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                gate.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                receive(WsInbound.Text(id, text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 用 okio 的 base64 而不是 android.util.Base64：这段传输要能在 JVM 契约测试里跑。
                receive(WsInbound.Binary(id, bytes.base64()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                gate.completeExceptionally(t)
                receive(WsInbound.Failed(id, t.message.orEmpty()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                // Preserve final messages already queued before this close frame.
                receive(WsInbound.Closed(id, code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(id)
                opened.remove(id)
            }
        })
        sockets[id] = socket
        if (closed.get()) { socket.cancel(); sockets.remove(id) }
        return id
    }

    private suspend fun awaitOpen(id: String) {
        val gate = opened[id] ?: throw IllegalStateException("Unknown socket")
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
