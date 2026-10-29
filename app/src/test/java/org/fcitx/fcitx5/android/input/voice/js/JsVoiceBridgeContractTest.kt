/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 用桌面版 QuickJS 跑**真实的** bundled 脚本与真实前奏：验证的是宿主与插件的契约，
 * 不是替身的行为。
 *
 * 单测跑在 JVM 上，所以 app/build.gradle.kts 把单元测试配置里的 quickjs-kt-android 换成
 * quickjs-kt-jvm（后者自带桌面原生库）。这里用假的 __n_* 原生实现接住插件对宿主的所有调用，
 * 因此可以断言「插件到底让宿主发了什么」。
 */
class JsVoiceBridgeContractTest {

    private val scriptDir = File("src/main/assets/bundled/js-voice")
    private val scriptFile = File(scriptDir, "qwen-asr.js")
    private val doubaoFile = File(scriptDir, "doubao-asr.js")
    private val tencentFile = File(scriptDir, "tencent-asr.js")
    private val translateFile = File(scriptDir, "tencent-translate.js")

    private inner class Harness {
        val sent = mutableListOf<String>()
        val sentBinary = mutableListOf<String>()

        /** sendGzipFrame 的入参：(prefixBase64, payloadBase64)。gzip 由宿主做，这里只收明文。 */
        val frames = mutableListOf<Pair<String, String>>()
        val posts = mutableListOf<Pair<String, String>>()
        var connectUrl = ""
        var connectSecretField = ""
        var connectHeaders = emptyMap<String, String>()
        val signatures = mutableListOf<Pair<String, String>>()
        var closed = false
        var postResponse =
            """{"output":{"choices":[{"message":{"content":[{"text":"你好世界。"}]}}]}}"""

        fun install(js: QuickJs, locale: String = "zh-CN") {
            js.function("__n_locale") { locale }
            js.function("__n_hmacSha1Base64") { args ->
                val field = args[0].toString()
                val payload = args[1].toString()
                signatures += field to payload
                encode(JsVoiceCrypto.hmacSha1(field, payload) { if (it == "secretKey") "test-secret" else null })
            }
            js.function("__n_wsConnect") { args ->
                connectUrl = args.getOrNull(0)?.toString().orEmpty()
                connectSecretField = args.getOrNull(1)?.toString().orEmpty()
                connectHeaders = JsVoiceHeaders.build(
                    connectSecretField,
                    "test-secret",
                    args.getOrNull(2)?.toString().orEmpty(),
                    args.getOrNull(3)?.toString().orEmpty(),
                    args.getOrNull(4)?.toString().orEmpty(),
                )
                "socket-1"
            }
            js.function("__n_wsWaitOpen") { null }
            js.function("__n_wsSend") { args ->
                sent += args.getOrNull(1)?.toString().orEmpty()
                null
            }
            js.function("__n_wsSendBinary") { args ->
                sentBinary += args.getOrNull(1)?.toString().orEmpty()
                null
            }
            js.function("__n_wsSendGzipFrame") { args ->
                frames += args.getOrNull(1)?.toString().orEmpty() to args.getOrNull(2)?.toString().orEmpty()
                null
            }
            js.function("__n_wsClose") { _ ->
                closed = true
                null
            }
            js.function("__n_httpPost") { args ->
                posts += args.getOrNull(0)?.toString().orEmpty() to args.getOrNull(2)?.toString().orEmpty()
                postResponse
            }
            js.function("__n_wavBase64") { args ->
                encode(JsVoiceHelpers.wavBytes(decode(args[0].toString()), args[1].toString().toInt()))
            }
            js.function("__n_utf8Base64") { args -> encode(args[0].toString().toByteArray()) }
            js.function("__n_base64Utf8") { args -> String(decode(args[0].toString())) }
            js.function("__n_byteLength") { args -> decode(args[0].toString()).size }
            js.function("__n_sliceBase64") { args ->
                val data = decode(args[0].toString())
                val offset = args[1].toString().toInt().coerceIn(0, data.size)
                val length = args[2].toString().toInt().coerceIn(0, data.size - offset)
                encode(data.copyOfRange(offset, offset + length))
            }
            js.function("__n_readUInt8") { args ->
                decode(args[0].toString())[args[1].toString().toInt()].toInt() and 0xff
            }
            js.function("__n_readInt32BE") { args ->
                JsVoiceHelpers.readInt32BE(decode(args[0].toString()), args[1].toString().toInt())
            }
            js.function("__n_gunzipUtf8") { args -> String(JsVoiceHelpers.gunzip(decode(args[0].toString()))) }
        }
    }

    private fun encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** JS 字符串字面量（带引号与转义），避免手工拼 JSON 时被引号破坏。 */
    private fun quote(value: String): String = JsonPrimitive(value).toString()

    private fun withPlugin(
        file: File = scriptFile,
        block: suspend (QuickJs, Harness) -> Unit,
    ) {
        assumeTrue("bundled plugin missing at " + file.absolutePath, file.isFile)
        val script = file.readText()
        val harness = Harness()
        val js = QuickJs.create(Dispatchers.IO)
        try {
            harness.install(js)
            runBlocking {
                js.evaluate<Any?>(JsVoicePrelude.SOURCE + "\n" + script)
                block(js, harness)
            }
        } finally {
            runCatching { js.close() }
        }
    }

    private suspend fun runCallback(js: QuickJs, name: String, arg: String): JsonObject {
        val outcome = JsVoiceInvoker.call({ code -> js.evaluate<String>(code) }, name, arg, 10_000L)
        outcome.error?.let { message -> throw IllegalArgumentException(message) }
        val root = buildJsonObject {
            put("result", outcome.result ?: JsonNull)
            put("events", JsonArray(outcome.actions))
        }
        return root
    }

    private fun events(result: JsonObject) = result["events"]!!.jsonArray.map { it.jsonObject }

    /**
     * 插件发出的动作，与宿主 `handle()` 的口径一致：前奏事件 + 回调返回对象。
     * qwen-asr.js 只在 flash 的 start 里发 ready 事件，其余（ready/partial/final/error/ended）
     * 都是**回调返回值**，所以只盯着 events 会漏掉绝大多数动作。
     */
    private fun actions(result: JsonObject): List<JsonObject> {
        val list = events(result).toMutableList()
        (resultOf(result) as? JsonObject)?.let { list += it }
        return list
    }

    private fun resultOf(result: JsonObject) = result["result"]

    private fun fieldValue(result: JsonObject, name: String) =
        resultOf(result)?.jsonObject?.get(name)?.jsonPrimitive

    @Test
    fun bundledPluginDescribesItsSettings() = withPlugin { js, _ ->
        val meta = JsVoicePluginMeta.parse(js.evaluate<String>("__meta()"))!!
        assertEquals("1.6.3", meta.version)
        assertEquals("通义千问语音识别", meta.voiceAsr!!.label)
        val raw = js.evaluate<String>("__describe('zh-CN')")
        val fields = JsVoiceFields.parse(raw)
        assertEquals(
            listOf(
                "apiKey", "platform", "mode", "stripPunctuation", "stripCharacters",
                "streamEndpoint", "streamModel", "heartbeat", "flashEndpoint", "flashModel", "itn",
            ),
            fields.map { it.key },
        )
        val apiKey = fields.first { it.key == "apiKey" }
        assertTrue("apiKey must be a secret", apiKey.isSecret)
        assertEquals("平台 API 密钥", apiKey.label)
        // 密钥永不下发给脚本：普通值视图里不能出现它。
        assertFalse(JsVoiceFields.currentValues(fields, emptyMap()).containsKey("apiKey"))
        val itn = fields.first { it.key == "itn" }
        val all = itn.visibleWhen!!["all"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("mode", "platform"), all.map { it.getValue("key").jsonPrimitive.content })
        assertEquals("flash", all[0].getValue("value").jsonPrimitive.content)
        assertEquals("qwen-ai", all[1].getValue("value").jsonPrimitive.content)
    }

    @Test
    fun flashSessionTurnsReleaseIntoOneHttpTranscript() = withPlugin { js, harness ->
        val start = runCallback(
            js, "start",
            """{"mode":"flash","platform":"bailian","sampleRate":16000,"taskId":"t-1",""" +
                """"stripPunctuation":"true","itn":"false"}""",
        )
        // async start() 的返回值必须被 evaluate 等到，而不是拿到 "[object Promise]"。
        assertEquals("flash", resultOf(start)?.jsonPrimitive?.contentOrNull)
        assertTrue(events(start).any { it["ready"]?.jsonPrimitive?.booleanOrNull == true })

        val pcm = encode(ByteArray(3200) { 1 })
        val audio = runCallback(
            js, "audio",
            """{"platform":"bailian","sampleRate":16000,"pcm":"$pcm","socket":"flash"}""",
        )
        assertTrue(events(audio).isEmpty())

        val stop = runCallback(
            js, "stop",
            """{"platform":"bailian","sampleRate":16000,"socket":"flash","itn":"false",""" +
                """"flashModel":"qwen-audio-3.0-asr-flash"}""",
        )
        assertEquals("你好世界", fieldValue(stop, "final")?.contentOrNull)
        assertEquals(true, fieldValue(stop, "ended")?.booleanOrNull)
        assertTrue(actions(stop).any { it["final"]?.jsonPrimitive?.contentOrNull == "你好世界" })

        assertEquals(1, harness.posts.size)
        val (url, body) = harness.posts.single()
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", url)
        assertTrue(body.contains("data:audio/wav;base64,"))
        assertTrue(body.contains("qwen-audio-3.0-asr-flash"))
        assertTrue(body.contains("\"format\":\"wav\""))
    }

    @Test
    fun flashStopsAfterTheThirtySecondBudget() = withPlugin { js, _ ->
        runCallback(
            js, "start",
            """{"mode":"flash","platform":"bailian","sampleRate":1000,"taskId":"t-2"}""",
        )
        val chunk = encode(ByteArray(36000))
        suspend fun feed() = runCallback(
            js, "audio", """{"sampleRate":1000,"pcm":"$chunk","socket":"flash"}""",
        )
        feed()
        val failure = try {
            feed()
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("超过 30 秒上限必须报错，实际没有", failure != null)
        assertTrue(
            "错误文案应说明 30 秒上限，实际：" + failure!!.message,
            failure.message.orEmpty().contains("30 seconds"),
        )
    }

    @Test
    fun streamingSessionOpensSocketAndStreamsPcm() = withPlugin { js, harness ->
        val start = runCallback(
            js, "start",
            """{"mode":"streaming","platform":"bailian","sampleRate":16000,"taskId":"t-3",""" +
                """"heartbeat":"true","stripPunctuation":"true"}""",
        )
        assertEquals("socket-1", resultOf(start)?.jsonPrimitive?.contentOrNull)
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference", harness.connectUrl)
        // 宿主只拿字段名，密钥值不经 JS。
        assertEquals("apiKey", harness.connectSecretField)

        val runTask = Json.parseToJsonElement(harness.sent.single()).jsonObject
        assertEquals("run-task", runTask["header"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        val payload = runTask["payload"]!!.jsonObject
        val parameters = payload["parameters"]!!.jsonObject
        assertEquals("16000", parameters["sample_rate"]!!.jsonPrimitive.content)
        assertFalse("sample_rate 必须是数字，字符串会被服务端拒绝", parameters["sample_rate"]!!.jsonPrimitive.isString)
        assertEquals("qwen-audio-3.0-asr-flash-streaming", payload["model"]!!.jsonPrimitive.content)

        val pcm = encode(ByteArray(640) { 7 })
        runCallback(js, "audio", """{"sampleRate":16000,"pcm":"$pcm","socket":"socket-1"}""")
        assertEquals(pcm, harness.sentBinary.single())

        runCallback(js, "stop", """{"taskId":"t-3","socket":"socket-1"}""")
        val finish = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertEquals("finish-task", finish["header"]!!.jsonObject["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun websocketEventsBecomePartialFinalAndEnded() = withPlugin { js, _ ->
        val message = { payload: String ->
            """{"socket":"socket-1","message":""" + quote(payload) + """}"""
        }

        // 真实会话总是先 start 再 onMessage：state（去标点开关等）由 start 设置，
        // 不先跑 start 就等于测一个真实链路里不会出现的状态。
        runCallback(
            js, "start",
            """{"mode":"streaming","platform":"bailian","sampleRate":16000,"taskId":"t-5",""" +
                """"stripPunctuation":"true"}""",
        )

        val started = runCallback(
            js, "onMessage",
            message("""{"header":{"event":"task-started"},"payload":{}}"""),
        )
        assertTrue(actions(started).any { it["ready"]?.jsonPrimitive?.booleanOrNull == true })

        val partial = runCallback(
            js, "onMessage",
            message("""{"header":{"event":"result-generated"},""" +
                """"payload":{"output":{"sentence":{"text":"你好","sentence_end":false}}}}"""),
        )
        assertEquals("你好", actions(partial).first()["partial"]?.jsonPrimitive?.contentOrNull)

        val final = runCallback(
            js, "onMessage",
            message("""{"header":{"event":"result-generated"},""" +
                """"payload":{"output":{"sentence":{"text":"你好世界。","sentence_end":true}}}}"""),
        )
        assertEquals("你好世界", actions(final).first()["final"]?.jsonPrimitive?.contentOrNull)

        val finished = runCallback(
            js, "onMessage",
            message("""{"header":{"event":"task-finished"},"payload":{}}"""),
        )
        assertEquals(true, fieldValue(finished, "ended")?.booleanOrNull)

        val failed = runCallback(
            js, "onMessage",
            message("""{"header":{"event":"task-failed","error_message":"bad key"},"payload":{}}"""),
        )
        assertEquals("bad key", fieldValue(failed, "error")?.contentOrNull)
    }

    @Test
    fun settingsScreenCanDescribeWithoutNetwork() {
        // 设置页只想跑 configure()，不打网络：占位原生实现必须让前奏里的 __native 全部解析成功，
        // 否则 evaluate 会在 __n_locale 处抛 ReferenceError（真机上就是这个表现）。
        assumeTrue("bundled qwen-asr.js missing at " + scriptFile.absolutePath, scriptFile.isFile)
        val script = scriptFile.readText()
        val js = QuickJs.create(Dispatchers.IO)
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            runBlocking {
                js.evaluate<Any?>(JsVoicePrelude.SOURCE + "\n" + script)
                val fields = JsVoiceFields.parse(js.evaluate<String>("__describe('zh-CN')"))
                assertEquals(11, fields.size)
                assertTrue(fields.first { it.key == "apiKey" }.isSecret)
            }
        } finally {
            runCatching { js.close() }
        }
    }

    @Test
    fun bundledPluginDeclaresOnlyAllowlistedHosts() = withPlugin { js, _ ->
        // 宿主用这份声明核对清单：脚本声明了清单外的域时要当场说清，而不是等连接被拒。
        val hosts = Json.parseToJsonElement(js.evaluate<String>("__hosts()"))
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("dashscope.aliyuncs.com", "maas.qianwenaiapi.com", "cn-beijing.maas.qianwenaiapi.com"),
            hosts,
        )
    }

    @Test
    fun pluginWritesOnlyToHostsItDeclared() = withPlugin { js, harness ->
        // 插件把自定义地址原样交出来，白名单由宿主判定：这里钉住「宿主能拦」的两个前提——
        // 插件确实透传地址，且 JsVoiceHelpers 只放行声明过的域。
        runCallback(
            js, "start",
            """{"mode":"streaming","platform":"bailian","sampleRate":16000,"taskId":"t-4",""" +
                """"streamEndpoint":"wss://evil.example.com/api-ws/v1/inference"}""",
        )
        assertEquals("wss://evil.example.com/api-ws/v1/inference", harness.connectUrl)
        val declared = setOf("dashscope.aliyuncs.com", "maas.qianwenaiapi.com", "cn-beijing.maas.qianwenaiapi.com")
        assertFalse(JsVoiceHelpers.isAllowedUrl(harness.connectUrl, declared))
        assertTrue(JsVoiceHelpers.isAllowedUrl("wss://dashscope.aliyuncs.com/api-ws/v1/inference", declared))
    }

    // ---- 豆包（火山引擎）插件：二进制帧协议 ----

    /** 按火山引擎 bigmodel 协议拼一帧服务器响应：header || seq || payloadSize || payload。 */
    private fun volcServerFrame(payloadJson: String, final: Boolean, compress: Boolean = true): String {
        val body = payloadJson.toByteArray()
        val payload = if (compress) JsVoiceHelpers.gzip(body) else body
        val flags = 0x01 or (if (final) 0x02 else 0x00)
        val serialization = 0x01 shl 4
        val out = java.nio.ByteBuffer.allocate(12 + payload.size)
        out.put(0x11)
        out.put(((0x09 shl 4) or flags).toByte())
        out.put((serialization or if (compress) 0x01 else 0x00).toByte())
        out.put(0x00)
        out.putInt(if (final) -1 else 1)
        out.putInt(payload.size)
        out.put(payload)
        return encode(out.array())
    }

    private fun prefixBytes(frame: Pair<String, String>): List<Int> =
        decode(frame.first).map { it.toInt() and 0xff }

    @Test
    fun translateSignsTheTranslationPathAndExposesItsSettings() = withPlugin(translateFile) { js, harness ->
        val meta = JsVoicePluginMeta.parse(js.evaluate<String>("__meta()"))!!
        assertEquals("tencent-translate", meta.id)
        assertEquals(listOf("asr.cloud.tencent.com"), meta.hosts)
        assertEquals("1.0.0", meta.version)
        val fields = JsVoiceFields.parse(js.evaluate<String>("__describe('zh-CN')"))
        assertEquals(11, fields.size)
        assertTrue(fields.first { it.key == "secretKey" }.isSecret)
        val values = JsVoiceFields.currentValues(fields, mapOf("appId" to "1234567890", "secretId" to "AKIDTEST"))
        assertFalse(values.containsKey("secretKey"))
        runCallback(js, "start", buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString())
        val uri = java.net.URI(harness.connectUrl)
        assertEquals("/asr/speech_translate/1234567890", uri.path)
        val query = uri.rawQuery.split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("zh", query["source"])
        assertEquals("en", query["target"])
        assertEquals("hunyuan-translation-lite", query["trans_model"])
        assertEquals("1", query["voice_format"])
        assertEquals(3600L, query.getValue("expired").toLong() - query.getValue("timestamp").toLong())
        val canonical = "asr.cloud.tencent.com" + uri.path + "?" + query.filterKeys { it != "signature" }
            .toSortedMap().map { (key, value) -> "$key=$value" }.joinToString("&")
        assertEquals("secretKey" to canonical, harness.signatures.single())
        assertEquals(encode(JsVoiceCrypto.hmacSha1("secretKey", canonical) { "test-secret" }), query["signature"])
        assertTrue(harness.connectHeaders.isEmpty())
        assertFalse(harness.connectUrl.contains("test-secret"))
    }

    @Test
    fun translateBuffersUntilReadyAndAssemblesRevisedSentencesOnce() = withPlugin(translateFile) { js, harness ->
        runCallback(js, "start", """{"appId":"1234567890","secretId":"AKIDTEST","source":"zh","target":"en"}""")
        runCallback(js, "audio", """{"socket":"socket-1","pcm":"AQID"}""")
        assertTrue(harness.sentBinary.isEmpty())
        val ready = runCallback(js, "onMessage", """{"socket":"socket-1","message":"{\"code\":0}"}""")
        assertEquals(true, fieldValue(ready, "ready")?.booleanOrNull)
        runCallback(js, "audio", """{"socket":"socket-1","pcm":"BAUG"}""")
        assertEquals(listOf("AQID", "BAUG"), harness.sentBinary)
        for (packet in listOf(
            """{"code":0,"sentence_id":0,"result":{"source_text":"你好","target_text":"Hello"}}""",
            """{"code":0,"sentence_id":0,"result":{"source_text":"你好，","target_text":"Hello,"}}""",
            """{"code":0,"sentence_id":1,"result":{"source_text":"世界。","target_text":"world."}}""",
        )) {
            runCallback(js, "onMessage", buildJsonObject { put("socket", "socket-1"); put("message", packet) }.toString())
        }
        runCallback(js, "stop", """{"socket":"socket-1"}""")
        assertEquals("""{"type":"end"}""", harness.sent.single())
        val final = runCallback(js, "onMessage", """{"socket":"socket-1","message":"{\"code\":0,\"final\":1}"}""")
        assertEquals("Hello, world.", fieldValue(final, "final")?.contentOrNull)
        assertEquals(true, fieldValue(final, "ended")?.booleanOrNull)
    }

    @Test
    fun translateCanInsertSourceAndReportsProviderErrors() = withPlugin(translateFile) { js, _ ->
        runCallback(js, "start", """{"appId":"1234567890","secretId":"AKIDTEST","output":"source"}""")
        val packet = """{"code":0,"sentence_id":0,"result":{"source_text":"你好。","target_text":"Hello."},"final":1}"""
        val final = runCallback(js, "onMessage", buildJsonObject { put("message", packet) }.toString())
        assertEquals("你好。", fieldValue(final, "final")?.contentOrNull)
        val error = runCallback(js, "onMessage", """{"message":"{\"code\":4001,\"message\":\"quota exceeded\"}"}""")
        assertEquals("Tencent Cloud translation 4001: quota exceeded", fieldValue(error, "error")?.contentOrNull)
    }

    @Test
    fun translateRejectsUnsupportedLanguagePairsBeforeSigning() = withPlugin(translateFile) { js, harness ->
        val error = runCatching {
            runCallback(js, "start", """{"appId":"1234567890","secretId":"AKIDTEST","source":"ja","target":"ru"}""")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("目标语言"))
        assertTrue(harness.signatures.isEmpty())
        assertEquals("", harness.connectUrl)
    }

    @Test
    fun tencentDescribesCredentialsAndSignsTheCanonicalWebSocketUrl() = withPlugin(tencentFile) { js, harness ->
        val fields = JsVoiceFields.parse(js.evaluate<String>("__describe('zh-CN')"))
        assertEquals(12, fields.size)
        assertTrue(fields.first { it.key == "secretKey" }.isSecret)
        assertFalse(JsVoiceFields.currentValues(fields, emptyMap()).containsKey("secretKey"))
        val start = runCallback(
            js, "start",
            """{"appId":"1234567890","secretId":"AKIDTEST","sampleRate":16000,"hotwordList":"腾讯云|10"}""",
        )
        assertEquals("socket-1", resultOf(start)?.jsonPrimitive?.contentOrNull)
        assertFalse(actions(start).any { it["ready"]?.jsonPrimitive?.booleanOrNull == true })
        val uri = java.net.URI(harness.connectUrl)
        assertEquals("wss", uri.scheme)
        assertEquals("asr.cloud.tencent.com", uri.host)
        assertEquals("/asr/v2/1234567890", uri.path)
        val params = uri.rawQuery.split('&').associate { part ->
            val pieces = part.split('=', limit = 2)
            java.net.URLDecoder.decode(pieces[0], "UTF-8") to java.net.URLDecoder.decode(pieces[1], "UTF-8")
        }
        val canonical = uri.host + uri.path + "?" + params.filterKeys { it != "signature" }
            .toSortedMap().entries.joinToString("&") { (key, value) -> "$key=$value" }
        assertEquals("secretKey" to canonical, harness.signatures.single())
        assertEquals(3600L, params.getValue("expired").toLong() - params.getValue("timestamp").toLong())
        assertEquals("腾讯云|10", params["hotword_list"])
        assertEquals("16k_zh", params["engine_model_type"])
        assertEquals("1", params["voice_format"])
        assertEquals(
            encode(JsVoiceCrypto.hmacSha1("secretKey", canonical) { "test-secret" }),
            params["signature"],
        )
        assertEquals("", harness.connectSecretField)
        assertTrue(harness.connectHeaders.isEmpty())
        assertFalse(harness.connectUrl.contains("test-secret"))
    }

    @Test
    fun tencentWaitsForServerReadinessThenStreamsAudioAndFinishes() = withPlugin(tencentFile) { js, harness ->
        runCallback(js, "start", """{"appId":"1234567890","secretId":"AKIDTEST","sampleRate":16000}""")
        val first = encode(ByteArray(3200) { 1 })
        val second = encode(ByteArray(3200) { 2 })
        runCallback(js, "audio", """{"socket":"socket-1","pcm":"$first"}""")
        assertTrue(harness.sentBinary.isEmpty())
        val ready = runCallback(js, "onMessage", """{"socket":"socket-1","message":"{\"code\":0}"}""")
        assertTrue(actions(ready).any { it["ready"]?.jsonPrimitive?.booleanOrNull == true })
        runCallback(js, "audio", """{"socket":"socket-1","pcm":"$second"}""")
        assertEquals(listOf(first, second), harness.sentBinary)
        runCallback(js, "stop", """{"socket":"socket-1"}""")
        assertEquals("end", Json.parseToJsonElement(harness.sent.last()).jsonObject["type"]!!.jsonPrimitive.content)
        val packet = """{"code":0,"final":1,"result":{"voice_text_str":"腾讯云识别成功。","slice_type":2,"index":0}}"""
        val ended = runCallback(js, "onMessage", buildJsonObject {
            put("socket", "socket-1")
            put("message", packet)
        }.toString())
        assertEquals("腾讯云识别成功", fieldValue(ended, "final")?.contentOrNull)
        assertEquals(true, fieldValue(ended, "ended")?.booleanOrNull)
    }

    @Test
    fun doubaoPluginDescribesAuthModes() = withPlugin(doubaoFile) { js, _ ->
        val raw = js.evaluate<String>("__describe('zh-CN')")
        val fields = JsVoiceFields.parse(raw)
        assertEquals(
            listOf(
                "apiKey", "authMode", "appId", "accessToken", "resourceId", "mode",
                "streamEndpoint", "streamModel", "resultType", "flashEndpoint", "flashModel",
                "enableItn", "enablePunc", "enableDdc", "enableNonstream", "showUtterances",
                "stripTrailingPunc", "stripTrailingPuncChars",
            ),
            fields.map { it.key },
        )
        // 两条凭据互斥：互斥可见性正是宿主判断「哪个密钥必填」的依据。
        val apiKey = fields.first { it.key == "apiKey" }
        assertTrue("apiKey must be a secret", apiKey.isSecret)
        assertEquals("authMode", apiKey.visibleWhen!!.getValue("key").jsonPrimitive.content)
        assertEquals("apiKey", apiKey.visibleWhen!!.getValue("value").jsonPrimitive.content)
        val token = fields.first { it.key == "accessToken" }
        assertTrue(token.isSecret)
        assertEquals("legacy", token.visibleWhen!!.getValue("value").jsonPrimitive.content)
        assertEquals("legacy", fields.first { it.key == "appId" }.visibleWhen!!.getValue("value").jsonPrimitive.content)
        val values = JsVoiceFields.currentValues(fields, emptyMap())
        assertFalse(values.containsKey("apiKey"))
        assertFalse(values.containsKey("accessToken"))
        val resource = fields.first { it.key == "resourceId" }
        assertEquals("authMode", resource.defaultWhen!!.getValue("key").jsonPrimitive.content)
        assertEquals("volc.seedasr.sauc.duration", values["resourceId"])
        assertEquals("volc.bigasr.sauc.duration", JsVoiceFields.currentValues(fields, mapOf("authMode" to "legacy"))["resourceId"])
    }

    @Test
    fun doubaoDefaultSettingsInjectRawApiKeyAndDefaultResource() = withPlugin(doubaoFile) { js, harness ->
        val fields = JsVoiceFields.parse(js.evaluate<String>("__describe('zh-CN')"))
        val context = buildJsonObject {
            JsVoiceFields.currentValues(fields, emptyMap()).forEach { (key, value) -> put(key, value) }
        }
        runCallback(js, "start", context.toString())
        assertEquals("test-secret", harness.connectHeaders["X-Api-Key"])
        assertEquals("volc.seedasr.sauc.duration", harness.connectHeaders["X-Api-Resource-Id"])
        assertFalse(harness.connectHeaders.containsKey("Authorization"))
        assertFalse(harness.connectHeaders.containsKey("X-Api-Access-Key"))
        assertFalse(harness.connectHeaders.containsKey("X-Api-App-Key"))
    }

    @Test
    fun doubaoLegacyAuthInjectsAppIdAndRawAccessToken() = withPlugin(doubaoFile) { js, harness ->
        runCallback(
            js, "start",
            """{"authMode":"legacy","appId":"12345","mode":"streaming"}""",
        )
        assertEquals("accessToken", harness.connectSecretField)
        assertEquals("12345", harness.connectHeaders["X-Api-App-Key"])
        assertEquals("test-secret", harness.connectHeaders["X-Api-Access-Key"])
        assertEquals("volc.bigasr.sauc.duration", harness.connectHeaders["X-Api-Resource-Id"])
        assertFalse(harness.connectHeaders.containsKey("Authorization"))
        assertFalse(harness.connectHeaders.containsKey("X-Api-Key"))
    }

    @Test
    fun doubaoChangingToApiKeyReplacesTheLegacyResourceInTheActualRequest() = withPlugin(doubaoFile) { js, harness ->
        val fields = JsVoiceFields.parse(js.evaluate<String>("__describe('zh-CN')"))
        val values = JsVoiceFields.currentValues(
            fields, mapOf("authMode" to "legacy", "resourceId" to "volc.bigasr.sauc.duration"),
        ).toMutableMap()
        values["authMode"] = "apiKey"
        values.putAll(JsVoiceFields.defaultsAfterChange(fields, "authMode", values))
        runCallback(js, "start", buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString())
        assertEquals("volc.seedasr.sauc.duration", harness.connectHeaders["X-Api-Resource-Id"])
        assertEquals("test-secret", harness.connectHeaders["X-Api-Key"])
        assertFalse(harness.connectHeaders.containsKey("X-Api-Access-Key"))
    }

    @Test
    fun doubaoSingleShotWithoutResourceUsesSeedHourlyByDefault() = withPlugin(doubaoFile) { js, harness ->
        runCallback(js, "start", """{"authMode":"apiKey","mode":"flash"}""")
        assertTrue(harness.connectUrl.endsWith("/bigmodel_nostream"))
        assertEquals("volc.seedasr.sauc.duration", harness.connectHeaders["X-Api-Resource-Id"])
    }

    @Test
    fun doubaoExplicitResourceStillWorksAfterSelectingApiKey() = withPlugin(doubaoFile) { js, harness ->
        val fields = JsVoiceFields.parse(js.evaluate<String>("__describe('zh-CN')"))
        val values = JsVoiceFields.currentValues(
            fields, mapOf("authMode" to "apiKey", "resourceId" to "volc.seedasr.sauc.concurrent"),
        )
        runCallback(js, "start", buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString())
        assertEquals("volc.seedasr.sauc.concurrent", harness.connectHeaders["X-Api-Resource-Id"])
    }

    @Test
    fun doubaoStreamingStartSendsFullRequestFrame() = withPlugin(doubaoFile) { js, harness ->
        val start = runCallback(
            js, "start",
            """{"authMode":"apiKey","mode":"streaming","sampleRate":16000,"taskId":"t-d1",""" +
                """"resourceId":"volc.bigasr.sauc.duration","streamModel":"bigmodel"}""",
        )
        assertEquals("socket-1", resultOf(start)?.jsonPrimitive?.contentOrNull)
        assertTrue(events(start).any { it["ready"]?.jsonPrimitive?.booleanOrNull == true })
        assertEquals(
            "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
            harness.connectUrl,
        )
        // 新版控制台的密钥按 X-Api-Key 原样注入，不带 Bearer 前缀。
        assertEquals("apiKey", harness.connectSecretField)
        assertEquals(1, harness.frames.size)
        // 全量请求：protoVer=1/size=1 → 0x11，type=1/flags=0 → 0x10，JSON+Gzip → 0x11，0x00
        assertEquals(listOf(0x11, 0x10, 0x11, 0x00), prefixBytes(harness.frames.single()))
        val request = Json.parseToJsonElement(String(decode(harness.frames.single().second))).jsonObject
        assertEquals("foxy", request["user"]!!.jsonObject["uid"]!!.jsonPrimitive.content)
        val audio = request["audio"]!!.jsonObject
        assertEquals(16000, audio["rate"]!!.jsonPrimitive.int)
        assertEquals("pcm", audio["format"]!!.jsonPrimitive.content)
        val req = request["request"]!!.jsonObject
        assertEquals("bigmodel", req["model_name"]!!.jsonPrimitive.content)
        assertEquals(true, req["enable_itn"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(true, req["show_utterances"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("full", req["result_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun doubaoAudioAndStopSendGzipFrames() = withPlugin(doubaoFile) { js, harness ->
        runCallback(
            js, "start",
            """{"authMode":"apiKey","mode":"streaming","sampleRate":16000,"taskId":"t-d2"}""",
        )
        val pcm = encode(ByteArray(320) { 3 })
        runCallback(js, "audio", """{"sampleRate":16000,"pcm":"$pcm","socket":"socket-1"}""")
        // 音频包：type=2/flags=0 → 0x20，raw+Gzip → 0x01
        assertEquals(listOf(0x11, 0x20, 0x01, 0x00), prefixBytes(harness.frames[1]))
        assertEquals(pcm, harness.frames[1].second)
        runCallback(js, "stop", """{"socket":"socket-1"}""")
        // 末包：type=2/flags=2 → 0x22，负载为空串（宿主压空内容也不该崩）
        assertEquals(listOf(0x11, 0x22, 0x01, 0x00), prefixBytes(harness.frames[2]))
        assertEquals("", harness.frames[2].second)
        // 豆包插件靠「末包标志」结束音频流，服务端随后回终帧；它自己不发 close。
        assertFalse(harness.closed)
    }

    @Test
    fun doubaoBinaryServerFrameBecomesPartialAndFinal() = withPlugin(doubaoFile) { js, _ ->
        runCallback(
            js, "start",
            """{"authMode":"apiKey","mode":"streaming","sampleRate":16000,"taskId":"t-d3",""" +
                """"stripTrailingPunc":"true"}""",
        )
        val partialFrame = volcServerFrame("""{"result":{"text":"你好"}}""", final = false)
        val partial = runCallback(
            js, "onMessage",
            """{"socket":"socket-1","binaryBase64":"$partialFrame"}""",
        )
        assertEquals("你好", actions(partial).first()["partial"]?.jsonPrimitive?.contentOrNull)

        val finalFrame = volcServerFrame("""{"result":{"text":"你好世界。"}}""", final = true)
        val finished = runCallback(
            js, "onMessage",
            """{"socket":"socket-1","binaryBase64":"$finalFrame"}""",
        )
        // stripTrailingPunc 在 start 里落到插件状态，末句的句号要被去掉。
        assertEquals("你好世界", actions(finished).first()["final"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, fieldValue(finished, "ended")?.booleanOrNull)
    }

    @Test
    fun doubaoErrorFrameReportsCodeAndMessage() = withPlugin(doubaoFile) { js, _ ->
        runCallback(
            js, "start",
            """{"authMode":"apiKey","mode":"streaming","sampleRate":16000,"taskId":"t-d4"}""",
        )
        val detail = "quota exceeded".toByteArray()
        val out = java.nio.ByteBuffer.allocate(12 + detail.size)
        out.put(0x11)
        out.put(0xf0.toByte())
        out.put(0x11)
        out.put(0x00)
        out.putInt(45000001)
        out.putInt(detail.size)
        out.put(detail)
        val frame = encode(out.array())
        val failed = runCallback(
            js, "onMessage",
            """{"socket":"socket-1","binaryBase64":"$frame"}""",
        )
        assertEquals("45000001: quota exceeded", fieldValue(failed, "error")?.contentOrNull)
    }

    @Test
    fun doubaoLegacyAuthRequiresAppId() = withPlugin(doubaoFile) { js, _ ->
        val failure = try {
            runCallback(
                js, "start",
                """{"authMode":"legacy","mode":"streaming","sampleRate":16000,"taskId":"t-d5"}""",
            )
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("legacy 鉴权缺 App ID 必须当场失败，实际没有", failure != null)
        assertTrue(
            "错误文案应说明缺 App ID，实际：" + failure!!.message,
            failure.message.orEmpty().contains("App ID"),
        )
    }

    @Test
    fun doubaoOnlyDeclaresItsOwnHost() = withPlugin(doubaoFile) { js, _ ->
        val hosts = Json.parseToJsonElement(js.evaluate<String>("__hosts()"))
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("openspeech.bytedance.com"), hosts)
    }
}
