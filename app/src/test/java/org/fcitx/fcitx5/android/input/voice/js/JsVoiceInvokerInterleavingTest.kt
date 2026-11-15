/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that an async App callback can be completed by an interleaved network callback. */
class JsVoiceInvokerInterleavingTest {
    @Test fun pendingAppInvocationCanInterleaveAStreamCallback() = runBlocking {
        val js = JsVoiceRuntime.create()
        val lock = Mutex()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            JsVoiceRuntime.initialize(
                js,
                JsVoicePrelude.SOURCE + "\n" + """
                    var __resolveApp;
                    foxy.plugin.register({id:'interleave',version:'1',hosts:[],features:[]});
                    foxy.app.register({id:'tool',label:'Tool',permissions:[],onOpen:function() {
                      return new Promise(function(resolve) { __resolveApp = resolve; });
                    }});
                    foxy.http.onChunk(function(id,event,data) {
                      __resolveApp({dialog:{type:'text',text:data}});
                      return Promise.resolve('handled');
                    });
                """.trimIndent(),
            )
            suspend fun evaluate(code: String): String? = lock.withLock { js.evaluate<String>(code) }
            val app = async {
                JsVoiceInvoker.call(
                    ::evaluate,
                    "app",
                    "{\"appId\":\"tool\"}",
                    2_000,
                )
            }
            try {
                withTimeout(2_000) {
                    while (lock.withLock { js.evaluate<String>("typeof __resolveApp") } != "function") delay(4)
                }
                val callback = JsVoiceInvoker.call(
                    ::evaluate,
                    "stream",
                    "{\"kind\":\"chunk\",\"id\":\"1\",\"event\":\"delta\",\"data\":\"done\"}",
                    2_000,
                )
                assertEquals("handled", callback.result!!.jsonPrimitive.content)
                val result = app.await().result as JsonObject
                assertEquals("done", result["dialog"]!!.let { it as JsonObject }["text"]!!.jsonPrimitive.content)
                assertEquals("0", lock.withLock { js.evaluate<String>("String(Object.keys(__pending).length)") })
            } finally {
                app.cancelAndJoin()
            }
        } finally {
            js.close()
        }
    }

    @Test fun timedOutAndCancelledInvocationsReleaseTheirPendingSlots() = runBlocking {
        val js = JsVoiceRuntime.create()
        val lock = Mutex()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            JsVoiceRuntime.initialize(
                js,
                JsVoicePrelude.SOURCE + "\n" + """
                    foxy.plugin.register({id:'pending',version:'1',hosts:[],features:[]});
                    foxy.app.register({id:'tool',label:'Tool',permissions:[],onOpen:function() {
                      return new Promise(function() {});
                    }});
                """.trimIndent(),
            )
            suspend fun evaluate(code: String): String? = lock.withLock { js.evaluate<String>(code) }
            val timedOut = runCatching {
                JsVoiceInvoker.call(::evaluate, "app", "{\"appId\":\"tool\"}", 50)
            }.exceptionOrNull()
            assertTrue(timedOut is kotlinx.coroutines.TimeoutCancellationException)
            assertEquals("0", evaluate("String(Object.keys(__pending).length)"))

            val pending = async {
                JsVoiceInvoker.call(::evaluate, "app", "{\"appId\":\"tool\"}", 5_000)
            }
            withTimeout(1_000) {
                while (evaluate("String(Object.keys(__pending).length)") != "1") delay(4)
            }
            pending.cancelAndJoin()
            assertEquals("0", evaluate("String(Object.keys(__pending).length)"))
        } finally {
            js.close()
        }
    }
}
