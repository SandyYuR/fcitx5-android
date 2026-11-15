/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.utf8Size
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 一条流式响应产生的入站事件，由会话决定怎么交给插件。 */
internal sealed interface StreamEvent {
    val streamId: String

    /** 一个 SSE 事件（或非 SSE 响应的一行）。 */
    class Chunk(override val streamId: String, val event: String, val data: String) : StreamEvent

    /** 响应正常结束。 */
    class End(override val streamId: String) : StreamEvent

    /** 连接、状态码或读取失败。 */
    class Failed(override val streamId: String, val message: String) : StreamEvent
}

/**
 * foxy.http.stream 的宿主实现：一次 HTTPS 请求，按行解析 SSE 或纯文本。
 *
 * 与 [JsAppHttp] 的区别是「边收边发」：这里不缓存完整响应，每解析出一个事件就 await 一次
 * [dispatch]，插件处理完才继续读下一行，天然形成背压。流没有整体时长限制（SSE 可能长时间
 * 空闲），取消来自插件调用 cancelStream 或 App 关闭时的 [closeAll]。
 *
 * 同步的 start 只解析不含密钥的选项并预留有界活动槽。协程确认主机批准且流仍活动后，才读取
 * 密钥并构造请求；取消会同时停止授权流程和已建立的 Call。
 */
internal class JsAppStreams(
    private val scope: CoroutineScope,
    private val checkUrl: (String) -> HttpUrl,
    private val secret: (String) -> String?,
    private val approve: suspend (HttpUrl) -> Boolean,
    private val authorizeSend: (HttpUrl) -> Unit,
    private val dispatch: suspend (StreamEvent, isActive: () -> Boolean) -> Unit,
    client: OkHttpClient = JsAppHttp.CLIENT,
    private val maxStreams: Int = MAX_STREAMS,
) {
    companion object {
        const val MAX_STREAMS = 4
        private val SSE_LINE_ENDINGS = "\r\n".encodeUtf8()
    }

    private data class StreamRequest(
        val originalUrl: String,
        val destination: HttpUrl,
        val method: String,
        val body: String,
        val contentType: MediaType,
        val credential: String,
        val authHeader: String,
        val authPrefix: String,
        val extraHeadersJson: String,
    )

    private class ActiveStream(val id: String) {
        private val active = AtomicBoolean(true)
        @Volatile private var call: Call? = null
        @Volatile private var job: Job? = null

        fun isActive(): Boolean = active.get()

        @Synchronized fun attachJob(value: Job): Boolean {
            job = value
            if (!active.get()) value.cancel()
            return active.get()
        }

        @Synchronized fun attachCall(value: Call): Boolean {
            if (!active.get()) return false
            call = value
            return true
        }

        @Synchronized fun deactivate(cancelJob: Boolean, cancelCall: Boolean): Boolean {
            if (!active.compareAndSet(true, false)) return false
            if (cancelCall) call?.cancel()
            if (cancelJob) job?.cancel()
            return true
        }
    }

    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val nextId = AtomicInteger(1)
    private val activeStreams = ConcurrentHashMap<String, ActiveStream>()
    private val closed = AtomicBoolean(false)

    /** 校验无密钥选项并立即返回流 ID；批准、取密钥和真正请求都在 [scope] 里完成。 */
    fun start(options: JsonObject): String {
        check(!closed.get()) { "Session is closed" }
        val url = options.text("url")
        require(url.substringBefore("://", "").equals("https", ignoreCase = true)) { "HTTPS is required" }
        val destination = checkUrl(url)
        val method = options.text("method").ifBlank { "GET" }.uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported HTTP method" }
        val body = options.text("body")
        require(body.toByteArray().size <= 1024 * 1024) { "Request body is too large" }
        val extra = options["headers"] as? JsonObject ?: JsonObject(emptyMap())
        val contentType = extra.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.jsonPrimitive?.content?.toMediaType() ?: "application/json".toMediaType()
        val extraHeadersJson = JsonObject(extra.filterKeys { !it.equals("Content-Type", true) }).toString()
        // Validate plugin-controlled headers now without resolving a credential.
        JsVoiceHeaders.build("", null, "", "", extraHeadersJson)
        val request = StreamRequest(
            originalUrl = url,
            destination = destination,
            method = method,
            body = body,
            contentType = contentType,
            credential = options.text("credential"),
            authHeader = options.text("authHeader"),
            authPrefix = options.text("authPrefix"),
            extraHeadersJson = extraHeadersJson,
        )
        val id = nextId.getAndIncrement().toString()
        val state = ActiveStream(id)
        synchronized(activeStreams) {
            check(!closed.get()) { "Session is closed" }
            check(activeStreams.size < maxStreams) { "Too many HTTP streams" }
            activeStreams[id] = state
        }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { run(state, request) }
        if (!state.attachJob(job)) {
            activeStreams.remove(id, state)
            throw IllegalStateException("Session is closed")
        }
        job.start()
        return id
    }

    fun cancel(id: String) {
        activeStreams.remove(id)?.deactivate(cancelJob = true, cancelCall = true)
    }

    fun closeAll() {
        val pending = synchronized(activeStreams) {
            // Registration checks closed under this same monitor. It must finish inserting
            // before we drain, or observe closed and refuse the registration.
            if (!closed.compareAndSet(false, true)) return
            val states = ArrayList<ActiveStream>()
            // Concurrent completion may remove the last entry at any point. CHM's traversal
            // is weakly consistent; Collection.toList's size==1 shortcut is not.
            activeStreams.forEach { id, state ->
                if (activeStreams.remove(id, state)) states.add(state)
            }
            states
        }
        // Job cancellation may resume completion handlers; do not run them under the map lock.
        pending.forEach { it.deactivate(cancelJob = true, cancelCall = true) }
    }

    private suspend fun run(state: ActiveStream, request: StreamRequest) {
        try {
            currentCoroutineContext().ensureActive()
            if (!state.isActive()) return
            if (!approve(request.destination)) {
                emit(state, StreamEvent.Failed(state.id, "Network permission denied"))
                return
            }
            currentCoroutineContext().ensureActive()
            if (!state.isActive()) return
            check(checkUrl(request.originalUrl) == request.destination) { "Endpoint changed before sending" }
            authorizeSend(request.destination)
            currentCoroutineContext().ensureActive()
            val headers = JsVoiceHeaders.build(
                request.credential,
                request.credential.takeIf { it.isNotBlank() }?.let(secret),
                request.authHeader,
                request.authPrefix,
                request.extraHeadersJson,
            )
            currentCoroutineContext().ensureActive()
            if (!state.isActive()) return
            // Credential lookup may involve storage/keystore work. Recheck the current
            // endpoint and approval after it, immediately before attaching a network call.
            check(checkUrl(request.originalUrl) == request.destination) { "Endpoint changed before sending" }
            authorizeSend(request.destination)
            currentCoroutineContext().ensureActive()
            val built = Request.Builder().url(request.destination)
                .method(
                    request.method,
                    if (request.method == "GET") null else request.body.toRequestBody(request.contentType),
                )
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val call = client.newCall(built)
            if (!state.attachCall(call)) {
                call.cancel()
                return
            }
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) error("HTTP " + response.code)
                val source = response.body?.source() ?: error("Empty response")
                if (response.header("Content-Type")?.contains("text/event-stream", true) == true) {
                    readEvents(state, source)
                } else {
                    while (state.isActive()) {
                        val line = readLine(source) ?: break
                        if (!emit(state, StreamEvent.Chunk(state.id, "line", line))) return
                    }
                }
            }
            emit(state, StreamEvent.End(state.id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emit(state, StreamEvent.Failed(state.id, error.message ?: "Stream failed"))
        } finally {
            release(state)
        }
    }

    /** Check before dispatch and let the session recheck after acquiring its JS evaluation lock. */
    private suspend fun emit(state: ActiveStream, event: StreamEvent): Boolean {
        if (!state.isActive()) return false
        dispatch(event, state::isActive)
        return state.isActive()
    }

    private fun release(state: ActiveStream) {
        activeStreams.remove(state.id, state)
        state.deactivate(cancelJob = false, cancelCall = false)
    }

    /**
     * 解析 SSE：`event:` 与 `data:` 字段，多行 data 以换行连接，空行派发；
     * 事件名缺省为 `message`，注释行（`:` 开头）与其余字段忽略。
     */
    private suspend fun readEvents(state: ActiveStream, source: BufferedSource) {
        val lines = SseLineReader(source)
        var event = ""
        var eventBytes = "message".utf8Size()
        val data = StringBuilder()
        var dataBytes = 0L
        var hasData = false
        while (state.isActive()) {
            val line = lines.readLine() ?: break
            if (line.isEmpty()) {
                if (hasData) {
                    if (!emit(state, StreamEvent.Chunk(state.id, event.ifEmpty { "message" }, data.toString()))) return
                    data.setLength(0)
                    dataBytes = 0L
                    hasData = false
                }
                event = ""
                eventBytes = "message".utf8Size()
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> {
                    eventBytes = value.ifEmpty { "message" }.utf8Size()
                    require(eventBytes + dataBytes <= JsVoiceRuntime.RESPONSE_BYTES) {
                        "Stream event is too large"
                    }
                    event = value
                }
                "data" -> {
                    val added = value.utf8Size() + if (hasData) 1L else 0L
                    require(eventBytes + dataBytes + added <= JsVoiceRuntime.RESPONSE_BYTES) {
                        "Stream event is too large"
                    }
                    if (hasData) data.append('\n')
                    data.append(value)
                    dataBytes += added
                    hasData = true
                }
                else -> Unit
            }
        }
        if (state.isActive() && hasData) {
            emit(state, StreamEvent.Chunk(state.id, event.ifEmpty { "message" }, data.toString()))
        }
    }

    /** SSE accepts CR, LF and CRLF and ignores exactly one leading UTF-8 BOM. */
    private class SseLineReader(private val source: BufferedSource) {
        private var firstLine = true
        private var skipLf = false

        fun readLine(): String? {
            if (firstLine) {
                firstLine = false
                // Read only as much of the BOM prefix as matches. In particular, a stream
                // beginning with CR must not wait for three bytes before handling that line.
                if (source.request(1L) && source.buffer[0L] == 0xef.toByte() &&
                    source.request(2L) && source.buffer[1L] == 0xbb.toByte() &&
                    source.request(3L) && source.buffer[2L] == 0xbf.toByte()) {
                    source.skip(3L)
                }
            }
            if (skipLf) {
                skipLf = false
                if (source.request(1L) && source.buffer[0L] == '\n'.code.toByte()) source.skip(1L)
            }
            val limit = JsVoiceRuntime.RESPONSE_BYTES.toLong()
            var searchedBytes = 0L
            while (true) {
                val newline = source.buffer.indexOfElement(SSE_LINE_ENDINGS, searchedBytes)
                if (newline >= 0L) {
                    require(newline <= limit) { "Stream line is too long" }
                    val line = source.readUtf8(newline)
                    // Return a CR-terminated line immediately; consume its optional LF on
                    // the next read, even when the pair spans response buffers.
                    skipLf = source.readByte() == '\r'.code.toByte()
                    return line
                }
                val bufferedBytes = source.buffer.size
                require(bufferedBytes <= limit) { "Stream line is too long" }
                if (!source.request(bufferedBytes + 1L)) {
                    return if (bufferedBytes == 0L) null else source.readUtf8()
                }
                // request() fills a segment at a time. Do not rescan the previous segments
                // or wait for an entire size budget before looking for a newly received CR.
                searchedBytes = bufferedBytes
            }
        }
    }

    /** 读一行，去掉行尾的 CR；单行上限与普通响应的正文预算相同。 */
    private fun readLine(source: BufferedSource): String? {
        val limit = JsVoiceRuntime.RESPONSE_BYTES.toLong()
        val newline = source.indexOf('\n'.code.toByte(), 0L, limit + 2L)
        if (newline >= 0L) {
            val contentBytes = if (newline > 0L && source.buffer[newline - 1L] == '\r'.code.toByte()) {
                newline - 1L
            } else newline
            require(contentBytes <= limit) { "Stream line is too long" }
            return source.readUtf8Line()
        }
        // exhausted() also checks buffered data, so it cannot identify EOF with a valid trailing
        // line still buffered. Request beyond the payload and optional CR delimiter instead.
        require(!source.request(limit + 2L) && (source.buffer.size <= limit ||
            source.buffer.size == limit + 1L && source.buffer[limit] == '\r'.code.toByte())) {
            "Stream line is too long"
        }
        return source.readUtf8().removeSuffix("\r").ifEmpty { null }
    }
}
