/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
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
 * 与 [JsAppHttp] 的区别是「边收边发」：这里不缓存响应，每解析出一个事件就 await 一次
 * [dispatch]，插件处理完才继续读下一行，天然形成背压。流没有整体时长限制（SSE 可能长时间
 * 空闲），取消来自插件调用 cancelStream 或 App 关闭时的 [closeAll]。
 *
 * 授权与普通 HTTP 共用：主机必须是静态声明或设置里的 HTTPS 地址，且用户按插件/主机批准过。
 * 批准要弹对话框，而 stream() 是同步返回流 ID 的，所以授权放在协程里做，拒绝时以
 * [StreamEvent.Failed] 通知插件。
 */
internal class JsAppStreams(
    private val scope: CoroutineScope,
    private val checkUrl: (String) -> HttpUrl,
    private val secret: (String) -> String?,
    private val approve: suspend (HttpUrl) -> Boolean,
    private val dispatch: suspend (StreamEvent) -> Unit,
    client: OkHttpClient = JsAppHttp.CLIENT,
) {
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val nextId = AtomicInteger(1)
    private val calls = ConcurrentHashMap<String, Call>()
    private val closed = AtomicBoolean(false)

    /** 校验选项后立即返回流 ID；真正的请求在 [scope] 里跑。 */
    fun start(options: JsonObject): String {
        check(!closed.get()) { "Session is closed" }
        val url = options.text("url")
        require(url.startsWith("https://", ignoreCase = true)) { "HTTPS is required" }
        val destination = checkUrl(url)
        val method = options.text("method").ifBlank { "GET" }.uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported HTTP method" }
        val body = options.text("body")
        require(body.toByteArray().size <= 1024 * 1024) { "Request body is too large" }
        val extra = options["headers"] as? JsonObject ?: JsonObject(emptyMap())
        val contentType = extra.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.jsonPrimitive?.content ?: "application/json"
        val credential = options.text("credential")
        val headers = JsVoiceHeaders.build(
            credential,
            if (credential.isBlank()) null else secret(credential),
            options.text("authHeader"), options.text("authPrefix"),
            JsonObject(extra.filterKeys { !it.equals("Content-Type", true) }).toString(),
        )
        val request = Request.Builder().url(destination)
            .method(method, if (method == "GET") null else body.toRequestBody(contentType.toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        val id = nextId.getAndIncrement().toString()
        val call = client.newCall(request)
        calls[id] = call
        if (closed.get() && calls.remove(id) != null) {
            call.cancel()
            throw IllegalStateException("Session is closed")
        }
        scope.launch(Dispatchers.IO) { run(id, call, url, destination) }
        return id
    }

    fun cancel(id: String) {
        calls.remove(id)?.cancel()
    }

    fun closeAll() {
        closed.set(true)
        calls.keys.toList().forEach { id -> calls.remove(id)?.cancel() }
    }

    private suspend fun run(id: String, call: Call, url: String, destination: HttpUrl) {
        try {
            if (!approve(destination)) { fail(id, "Network permission denied"); return }
            // 批准可能涉及存储与对话框；发送前再核对一次端点，避免批准期间换了目标。
            check(checkUrl(url) == destination) { "Endpoint changed before sending" }
            call.execute().use { response ->
                if (!response.isSuccessful) error("HTTP " + response.code)
                val source = response.body?.source() ?: error("Empty response")
                if (response.header("Content-Type")?.contains("text/event-stream", true) == true) {
                    readEvents(id, source)
                } else {
                    while (true) {
                        val line = readLine(source) ?: break
                        dispatch(StreamEvent.Chunk(id, "line", line))
                    }
                }
            }
            if (calls.remove(id) != null) dispatch(StreamEvent.End(id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(id, error.message ?: "Stream failed")
        }
    }

    private suspend fun fail(id: String, message: String) {
        if (calls.remove(id) != null) dispatch(StreamEvent.Failed(id, message))
    }

    /**
     * 解析 SSE：`event:` 与 `data:` 字段，多行 data 以换行连接，空行派发；
     * 事件名缺省为 `message`，注释行（`:` 开头）与其余字段忽略。
     */
    private suspend fun readEvents(id: String, source: BufferedSource) {
        var event = ""
        val data = StringBuilder()
        while (true) {
            val line = readLine(source) ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    dispatch(StreamEvent.Chunk(id, event.ifEmpty { "message" }, data.toString()))
                    data.setLength(0)
                }
                event = ""
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
                else -> Unit
            }
        }
        if (data.isNotEmpty()) dispatch(StreamEvent.Chunk(id, event.ifEmpty { "message" }, data.toString()))
    }

    /** 读一行，去掉行尾的 CR；单行上限与普通响应的正文预算相同。 */
    private fun readLine(source: BufferedSource): String? {
        val limit = JsVoiceRuntime.RESPONSE_BYTES.toLong()
        if (source.indexOf('\n'.code.toByte(), 0L, limit) == -1L) {
            if (!source.exhausted()) error("Stream line is too long")
            return source.readUtf8().trimEnd('\r').ifEmpty { null }
        }
        return source.readUtf8Line()?.trimEnd('\r')
    }
}
