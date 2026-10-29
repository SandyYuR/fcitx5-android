/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.os.Bundle
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.utils.appContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Commands queued to the per-session worker. Shared by the provider and the JS net bridge. */
internal sealed interface JsVoiceCommand {
    class Audio(val base64: String) : JsVoiceCommand
    object Stop : JsVoiceCommand
    /** 一帧服务器消息。[binaryBase64] 非空表示二进制帧（火山引擎 bigmodel 就是二进制协议）。 */
    class Message(val socket: String, val text: String, val binaryBase64: String? = null) : JsVoiceCommand
    class Fail(val message: String) : JsVoiceCommand
}

/**
 * Runs a Foxy-style JavaScript voice.asr plugin in QuickJS and exposes it as an in-process
 * [IVoiceInputProvider].
 *
 * 这条路径刻意与 AIDL 插件同构：对外仍然是 [IVoiceInputProvider]，因此
 * [VoiceInputProviderManager][org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager]
 * 的会话、PCM 队列、静音判定、结束看门狗全部照旧复用，JS 只是换了「插件在哪跑」。
 *
 * 每次会话一个 QuickJS 实例 + 一个消费 [JsVoiceCommand] 的 worker，所有 JS 调用按到达顺序串行；
 * QuickJS 只在 IO 线程上求值，不碰主线程。
 */
internal class JsVoiceInputProvider(
    private val pluginId: String,
    private val scriptSource: () -> String,
    private val declaredHosts: Set<String>,
    /** 插件在 feature 里声明的采样率；宿主采集端按这个值启动。 */
    private val declaredSampleRate: Int = 16000,
    private val locale: String,
    private val http: OkHttpClient,
) : IVoiceInputProvider.Stub() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sampleRate = declaredSampleRate.coerceIn(8000, 48000)
    private var worker: Job? = null
    private var commands: Channel<JsVoiceCommand>? = null
    private var callback: IVoiceInputCallback? = null

    override fun isAvailable(): Boolean = true

    override fun getPreferredConfig(): Bundle = Bundle().apply {
        putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, declaredSampleRate.coerceIn(8000, 48000))
        putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, 16)
        putInt(VoiceInputIpc.ConfigKeys.CHANNELS, 1)
    }

    override fun configure(params: Bundle?) {
        sampleRate = (params?.getInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, declaredSampleRate) ?: declaredSampleRate)
            .coerceIn(8000, 48000)
    }

    override fun startSession(cb: IVoiceInputCallback?) {
        val target = cb ?: return
        stopWorker()
        val rx = Channel<JsVoiceCommand>(Channel.UNLIMITED)
        commands = rx
        callback = target
        val taskId = UUID.randomUUID().toString()
        val rate = sampleRate
        worker = scope.launch { runSession(target, taskId, rate, rx) }
    }

    override fun feedAudio(pcm: ByteArray?, offset: Int, len: Int, ptsMs: Long) {
        val data = pcm ?: return
        if (offset < 0 || len <= 0 || offset + len > data.size) return
        commands?.trySend(JsVoiceCommand.Audio(Base64.encodeToString(data, offset, len, Base64.NO_WRAP)))
    }

    override fun endStream() {
        commands?.trySend(JsVoiceCommand.Stop)
    }

    override fun cancelSession() {
        val target = callback
        stopWorker()
        target?.onSessionEnded()
    }

    override fun stopSession() {
        stopWorker()
    }

    private fun stopWorker() {
        worker?.cancel()
        worker = null
        commands = null
        callback = null
    }

    private suspend fun runSession(cb: IVoiceInputCallback, taskId: String, rate: Int, rx: Channel<JsVoiceCommand>) {
        val net = JsVoiceNet(http, declaredHosts, rx) { field ->
            JsVoiceSettingsStore.secret(pluginId, field)
        }
        val js = QuickJs.create(Dispatchers.IO)
        try {
            net.install(js, locale)
            js.evaluate<Any?>(JsVoicePrelude.SOURCE + "\n" + scriptSource())
            val undeclared = undeclaredHosts(js)
            if (undeclared.isNotEmpty()) {
                // 脚本要连的域名不在清单里：连接会在 net 层被拒，不在这里说清楚就只能看到
                // 一句「Host is not declared by the plugin」，看不出是脚本本身换了。
                cb.onError(
                    VoiceInputIpc.ErrorCodes.NOT_AVAILABLE,
                    appContext.getString(R.string.js_voice_undeclared_host, undeclared.joinToString(", ")),
                )
                return
            }
            val fields = describeFields(js, locale)
            val values = JsVoiceFields.currentValues(fields, JsVoiceSettingsStore.values(pluginId))
            // 只要求**当前可见**的密钥。豆包插件按 authMode 在 apiKey 与 accessToken 之间二选一，
            // 若把 hide 着的那一个也算成必填，用户永远启动不了会话。
            val missing = fields.firstOrNull {
                it.isSecret && JsVoiceFields.isVisible(it, values) && !JsVoiceSettingsStore.hasSecret(pluginId, it.key)
            }
            if (missing != null) {
                cb.onError(
                    VoiceInputIpc.ErrorCodes.NOT_AVAILABLE,
                    appContext.getString(
                        R.string.js_voice_secret_missing,
                        missing.label.ifBlank { missing.key },
                    ),
                )
                return
            }
            // 每个生命周期回调都带上同一份设置：脚本的 stop() 会重新读 flashEndpoint/platform/itn，
            // 只给 start() 下发会让「单次识别」回落到默认平台，设置页的选择等于没生效。
            val settings = buildJsonObject {
                values.forEach { (key, value) -> put(key, value) }
                // 数字而不是字符串：脚本把它原样塞进 DashScope 的 parameters.sample_rate，
                // 发成 "16000" 会被服务端当成非法参数。
                put("sampleRate", JsonPrimitive(rate))
                put("taskId", taskId)
            }
            val started = call(js, "start", settings, START_TIMEOUT_MS)
            val socket = (started.result as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (handle(cb, started.actions)) return
            for (cmd in rx) {
                val finished = when (cmd) {
                    is JsVoiceCommand.Audio -> handle(cb, call(js, "audio", buildJsonObject {
                        settings.forEach { (key, value) -> put(key, value) }
                        put("pcm", cmd.base64)
                        put("socket", socket)
                    }, AUDIO_TIMEOUT_MS).actions)
                    JsVoiceCommand.Stop -> handle(cb, call(js, "stop", buildJsonObject {
                        settings.forEach { (key, value) -> put(key, value) }
                        put("socket", socket)
                    }, STOP_TIMEOUT_MS).actions)
                    is JsVoiceCommand.Message -> handle(cb, call(js, "onMessage", buildJsonObject {
                        put("socket", cmd.socket)
                        // 两个键分开给：插件按 message.message 读文本帧、按 message.binaryBase64 读二进制帧，
                        // 混在一起会让文本协议误把 base64 当 JSON 去 parse。
                        if (cmd.binaryBase64 != null) put("binaryBase64", cmd.binaryBase64)
                        else put("message", cmd.text)
                    }, MESSAGE_TIMEOUT_MS).actions)
                    is JsVoiceCommand.Fail -> {
                        cb.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, cmd.message)
                        true
                    }
                }
                if (finished) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cb.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, e.message ?: "JS voice plugin failed")
        } finally {
            net.closeAll()
            js.close()
        }
    }

    private class CallResult(val result: JsonElement, val actions: List<JsonObject>)

    /**
     * Invokes plugin method [name] and collects the actions it emitted.
     *
     * 具体怎么把 Promise 拍落地在 [JsVoiceInvoker]：插件回调常常在一次微任务里就 resolve，
     * 只求值一次会拿到 Promise 对象本身而不是它的返回值。
     */
    private suspend fun call(js: QuickJs, name: String, arg: JsonObject, timeoutMs: Long): CallResult {
        val outcome = JsVoiceInvoker.call({ code -> js.evaluate<String>(code) }, name, arg.toString(), timeoutMs)
        outcome.error?.let { error(it) }
        val actions = outcome.actions.toMutableList()
        (outcome.result as? JsonObject)?.let { actions += it }
        return CallResult(outcome.result ?: JsonNull, actions)
    }

    /** Hosts the script declared in `register()` that the bundled allowlist does not cover. */
    private suspend fun undeclaredHosts(js: QuickJs): List<String> =
        Json.parseToJsonElement(js.evaluate<String>("__hosts()"))
            .jsonArray
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filterNot { it in declaredHosts }

    /** Reads the settings the plugin declared in `configure(ui, locale)`. */
    private suspend fun describeFields(js: QuickJs, locale: String): List<JsVoiceField> {
        val raw = js.evaluate<String>("__describe(" + JsonPrimitive(locale) + ")")
        return JsVoiceFields.parse(raw)
    }

    /** Maps action objects to callback events. Returns true when the session is over. */
    private fun handle(cb: IVoiceInputCallback, actions: List<JsonObject>): Boolean {
        var ended = false
        for (action in actions) {
            if (action["ready"]?.jsonPrimitive?.booleanOrNull == true) cb.onReady()
            action["partial"]?.jsonPrimitive?.contentOrNull?.let { cb.onPartialResult(it) }
            action["final"]?.jsonPrimitive?.contentOrNull?.let { cb.onSegmentFinal(it) }
            action["error"]?.jsonPrimitive?.contentOrNull?.let {
                cb.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, it)
                return true
            }
            if (action["ended"]?.jsonPrimitive?.booleanOrNull == true) ended = true
        }
        if (ended) cb.onSessionEnded()
        return ended
    }

    companion object {
        /**
         * 收尾看门狗预算，宿主 [VoiceInputProviderManager][org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager]
         * 用它代替 IPC 插件的 3s。
         *
         * 为什么必须更长：flash 模式把整段音频 POST 到云端再取回文本，Foxy 给 stop 回调的预算
         * 就是 35s；按 3s 收尾会在识别结果回来之前拆掉会话，而那时文本还在 HTTP 响应里。
         */
        const val FINISH_WATCHDOG_MS = 40_000L

        private const val START_TIMEOUT_MS = 10_000L
        private const val AUDIO_TIMEOUT_MS = 3_000L
        private const val MESSAGE_TIMEOUT_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 35_000L
    }
}

/**
 * 只给设置页用的占位原生实现：没有任何网络与音频能力。
 *
 * 为什么必须有：前奏里的 __native 对象在求值那一刻就把 __n_locale、__n_wsConnect 等名字全部
 * 解引用了一遍，缺一个就是 ReferenceError（真机上的表现是「读取插件设置失败」）。
 */
internal object JsVoiceStubNatives {
    fun install(js: QuickJs, locale: String) {
        js.function("__n_locale") { locale }
        js.function("__n_wsConnect") { throw IllegalStateException("network is not available here") }
        js.function("__n_wsWaitOpen") { null }
        js.function("__n_wsSend") { null }
        js.function("__n_wsSendBinary") { null }
        js.function("__n_wsSendGzipFrame") { null }
        js.function("__n_wsClose") { null }
        js.function("__n_httpPost") { throw IllegalStateException("network is not available here") }
        js.function("__n_hmacSha1Base64") { throw IllegalStateException("signing is not available here") }
        js.function("__n_wavBase64") { args ->
            encode(JsVoiceHelpers.wavBytes(decode(text(args, 0)), text(args, 1).toInt()))
        }
        js.function("__n_utf8Base64") { args -> encode(text(args, 0).toByteArray(Charsets.UTF_8)) }
        js.function("__n_base64Utf8") { args -> String(decode(text(args, 0)), Charsets.UTF_8) }
        js.function("__n_byteLength") { args -> decode(text(args, 0)).size }
        js.function("__n_sliceBase64") { args ->
            val data = decode(text(args, 0))
            val offset = text(args, 1).toInt().coerceIn(0, data.size)
            val length = text(args, 2).toInt().coerceIn(0, data.size - offset)
            encode(data.copyOfRange(offset, offset + length))
        }
        js.function("__n_readUInt8") { args -> decode(text(args, 0))[text(args, 1).toInt()].toInt() and 0xff }
        js.function("__n_readInt32BE") { args ->
            JsVoiceHelpers.readInt32BE(decode(text(args, 0)), text(args, 1).toInt())
        }
        js.function("__n_gunzipUtf8") { args -> String(JsVoiceHelpers.gunzip(decode(text(args, 0))), Charsets.UTF_8) }
    }

    private fun text(args: Array<Any?>, index: Int): String = args.getOrNull(index)?.toString().orEmpty()

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private fun encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}

/** Loads a plugin script in a throwaway QuickJS instance, e.g. to render its settings screen. */
internal object JsVoiceScript {
    suspend fun describe(script: String, locale: String): List<JsVoiceField> {
        val js = QuickJs.create(Dispatchers.IO)
        return try {
            JsVoiceStubNatives.install(js, locale)
            js.evaluate<Any?>(JsVoicePrelude.SOURCE + "\n" + script)
            JsVoiceFields.parse(js.evaluate<String>("__describe(" + JsonPrimitive(locale) + ")"))
        } finally {
            runCatching { js.close() }
        }
    }
}

/**
 * Owns the QuickJS bindings for one session. Each native is a global "__n_<name>", which the
 * prelude exposes as __native.<name>. Calls are synchronous because the QuickJS evaluation already
 * runs on an IO thread.
 */
private class JsVoiceNet(
    private val http: OkHttpClient,
    private val declaredHosts: Set<String>,
    private val rx: Channel<JsVoiceCommand>,
    private val secretFor: (String) -> String?,
) {
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val opened = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val nextId = AtomicInteger(1)

    fun install(js: QuickJs, locale: String) {
        js.function("__n_locale") { _ -> locale }
        js.function("__n_hmacSha1Base64") { args ->
            encode(JsVoiceCrypto.hmacSha1(text(args, 0), text(args, 1), secretFor))
        }
        js.function("__n_wsConnect") { args ->
            connect(
                url = text(args, 0),
                secretField = text(args, 1),
                authHeader = text(args, 2),
                authPrefix = text(args, 3),
                headersJson = text(args, 4),
            )
        }
        js.function("__n_wsWaitOpen") { args -> awaitOpen(text(args, 0)); null }
        js.function("__n_wsSend") { args ->
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            socket.send(text(args, 1))
            null
        }
        js.function("__n_wsSendBinary") { args ->
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            socket.send(ByteString.of(*bytes(args, 1)))
            null
        }
        js.function("__n_wsSendGzipFrame") { args ->
            val socket = sockets[text(args, 0)] ?: error("socket closed")
            socket.send(ByteString.of(*JsVoiceHelpers.gzipFrame(bytes(args, 1), bytes(args, 2))))
            null
        }
        js.function("__n_wsClose") { args ->
            sockets.remove(text(args, 0))?.close(1000, null)
            null
        }
        js.function("__n_httpPost") { args ->
            post(
                url = text(args, 0),
                secretField = text(args, 1),
                body = text(args, 2),
                headersJson = text(args, 3),
            )
        }
        js.function("__n_wavBase64") { args ->
            encode(JsVoiceHelpers.wavBytes(bytes(args, 0), text(args, 1).toInt()))
        }
        js.function("__n_utf8Base64") { args -> encode(text(args, 0).toByteArray(Charsets.UTF_8)) }
        js.function("__n_base64Utf8") { args -> String(bytes(args, 0), Charsets.UTF_8) }
        js.function("__n_byteLength") { args -> bytes(args, 0).size }
        js.function("__n_sliceBase64") { args ->
            val data = bytes(args, 0)
            val offset = text(args, 1).toInt().coerceIn(0, data.size)
            val length = text(args, 2).toInt().coerceIn(0, data.size - offset)
            encode(data.copyOfRange(offset, offset + length))
        }
        js.function("__n_readUInt8") { args -> bytes(args, 0)[text(args, 1).toInt()].toInt() and 0xff }
        js.function("__n_readInt32BE") { args -> JsVoiceHelpers.readInt32BE(bytes(args, 0), text(args, 1).toInt()) }
        js.function("__n_gunzipUtf8") { args -> String(JsVoiceHelpers.gunzip(bytes(args, 0)), Charsets.UTF_8) }
    }

    private fun connect(
        url: String,
        secretField: String,
        authHeader: String,
        authPrefix: String,
        headersJson: String,
    ): String {
        if (!JsVoiceHelpers.isAllowedUrl(url, declaredHosts)) {
            throw IllegalArgumentException("Host is not declared by the plugin")
        }
        val headers = JsVoiceHeaders.build(
            secretField = secretField,
            secret = secretField.takeIf { it.isNotBlank() }?.let(secretFor),
            authHeader = authHeader,
            authPrefix = authPrefix,
            headersJson = headersJson,
        )
        val id = nextId.getAndIncrement().toString()
        val gate = CompletableDeferred<Unit>()
        opened[id] = gate
        val builder = Request.Builder().url(url.replaceFirst("wss://", "https://"))
        headers.forEach { (name, value) -> builder.header(name, value) }
        sockets[id] = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                gate.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                rx.trySend(JsVoiceCommand.Message(id, text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                rx.trySend(JsVoiceCommand.Message(id, "", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                gate.completeExceptionally(t)
                rx.trySend(JsVoiceCommand.Fail("WebSocket failed: " + t.message))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(id)
            }
        })
        return id
    }

    private fun awaitOpen(id: String) {
        val gate = opened[id] ?: throw IllegalStateException("Unknown socket")
        runBlocking { withTimeout(10_000L) { gate.await() } }
    }

    private fun post(url: String, secretField: String, body: String, headersJson: String): String {
        if (!JsVoiceHelpers.isAllowedUrl(url, declaredHosts)) {
            throw IllegalArgumentException("Host is not declared by the plugin")
        }
        val headers = JsVoiceHeaders.build(
            secretField = secretField,
            secret = secretField.takeIf { it.isNotBlank() }?.let(secretFor),
            authHeader = "",
            authPrefix = "",
            headersJson = headersJson,
        )
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP " + response.code)
            response.body?.string().orEmpty()
        }
    }

    fun closeAll() {
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear()
        opened.values.forEach { it.cancel() }
        opened.clear()
    }

    private fun text(args: Array<Any?>, index: Int): String = args.getOrNull(index)?.toString().orEmpty()

    private fun bytes(args: Array<Any?>, index: Int): ByteArray = Base64.decode(text(args, index), Base64.NO_WRAP)

    private fun encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}
