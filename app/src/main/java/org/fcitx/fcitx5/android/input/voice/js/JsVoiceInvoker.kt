/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * 驱动一次插件回调：前奏的 `__run` + `__pump` 两拍。
 *
 * 不能只求值 `__run(...)` 就完事。quickjs-kt 的 `evaluate` 只等待**求值那一刻仍未落地**的
 * Promise：插件回调（尤其 flash 模式）通常在一次微任务里就 resolve，于是 `evaluate` 把 Promise
 * 对象本身当字符串返回（`Promise { <state>: "fulfilled" }`），而不是它的值。
 *
 * 所以桥拆成两步：`__run` 立刻返回「结果」或「还在等」的标记；随后每拍一次 `__pump`，
 * 每次求值都会推动 QuickJS 的微任务队列，等待中的 await 链因此得以继续，直到回调真正落地。
 *
 * 抽成纯 Kotlin 对象的理由：provider 与 JVM 契约测试必须走**同一段**调用逻辑，否则测试钉住的
 * 是测试自己的实现。
 */
internal object JsVoiceInvoker {

    /** 一次回调的结果：回调返回值、期间发出的动作（ready/partial/final/error/ended）、以及抛出的错误。 */
    class Outcome(val result: JsonElement?, val actions: List<JsonObject>, val error: String?)

    /** 两拍之间的间隔。字节操作同步完成；网络通过可取消的异步绑定等待。 */
    const val POLL_DELAY_MS = 4L
    private val nextInvocationId = AtomicLong()

    suspend fun call(
        evaluate: suspend (String) -> String?,
        name: String,
        argJson: String,
        timeoutMs: Long,
        cleanupEvaluate: suspend (String) -> String? = evaluate,
    ): Outcome {
        // evaluate() can suspend on a host async binding after __run has already stored its
        // Promise. Know its ID before evaluating so cancellation can also clean that first phase.
        val invocationId = "host-" + nextInvocationId.incrementAndGet()
        var pendingId: String? = invocationId
        try {
            return withTimeout(timeoutMs) {
                val actions = mutableListOf<JsonObject>()
                var reply = read(evaluate(runCode(name, argJson, invocationId)), actions)
                reply.errorOrNull()?.let { return@withTimeout Outcome(null, actions, it) }
                pendingId = reply.pendingId()
                while (pendingId != null) {
                    delay(POLL_DELAY_MS)
                    reply = read(evaluate(pumpCode(pendingId)), actions)
                    pendingId = reply.pendingId()
                    reply.errorOrNull()?.let { return@withTimeout Outcome(null, actions, it) }
                }
                Outcome(reply["result"], actions, null)
            }
        } finally {
            pendingId?.let { id ->
                withContext(NonCancellable) {
                    runCatching { cleanupEvaluate(cancelCode(id)) }
                }
            }
        }
    }

    /**
     * JS 表达式：`__run("<name>", <argJson>, <optional invocationId>)`。
     *
     * 各实参都用 [JsonPrimitive] 渲染，这样引号、反斜杠、换行由 JSON 编码器负责转义，
     * 不会因为插件文本里出现 `"` 或 `\` 拼出语法错误。
     */
    fun runCode(name: String, argJson: String, invocationId: String? = null): String =
        "__run(" + JsonPrimitive(name) + ", " + JsonPrimitive(argJson) +
            (invocationId?.let { ", " + JsonPrimitive(it) } ?: "") + ")"

    private fun pumpCode(invocationId: String): String = "__pump(" + JsonPrimitive(invocationId) + ")"

    private fun cancelCode(invocationId: String): String =
        "__cancelInvocation(" + JsonPrimitive(invocationId) + ")"

    private fun read(raw: String?, actions: MutableList<JsonObject>): JsonObject {
        val text = raw ?: error("JS bridge returned no reply")
        val root = Json.parseToJsonElement(text).jsonObject
        root["events"]?.jsonArray?.forEach { actions += it.jsonObject }
        return root
    }

    private fun JsonObject.pendingId(): String? = this["pending"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.errorOrNull(): String? = this["error"]?.jsonPrimitive?.contentOrNull
}
