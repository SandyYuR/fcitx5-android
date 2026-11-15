/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that an async App callback can be completed by an interleaved network callback. */
class JsVoiceInvokerInterleavingTest {
    @Test fun cancellingWhileInitialEvaluationAwaitsNativeWorkReleasesItsInvocation() = runBlocking {
        val js = JsVoiceRuntime.create()
        val lock = Mutex()
        val enteredEditor = CompletableDeferred<Unit>()
        val stoppedEditor = CompletableDeferred<Unit>()
        val editorResult = CompletableDeferred<String>()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            js.asyncFunction("__n_appEditor") {
                enteredEditor.complete(Unit)
                try { editorResult.await() }
                finally { stoppedEditor.complete(Unit) }
            }
            JsVoiceRuntime.initialize(
                js,
                JsVoicePrelude.SOURCE + "\n" + """
                    foxy.plugin.register({id:'cancel-native',version:'1',hosts:[],features:[]});
                    foxy.app.register({id:'tool',permissions:[],onOpen:async function() {
                      var text = await foxy.editor.getSelection();
                      return {dialog:{type:'text',text:text}};
                    }});
                """.trimIndent(),
            )
            suspend fun evaluate(code: String): String? = lock.withLock { js.evaluate<String>(code) }
            val pending = async {
                JsVoiceInvoker.call(::evaluate, "app", "{\"appId\":\"tool\"}", 5_000)
            }
            try {
                withTimeout(2_000) { enteredEditor.await() }
                pending.cancelAndJoin()
                // Host async bindings belong to QuickJS's scope, so their owner cancels or
                // completes them independently. The invocation must already be gone while the
                // native is still waiting, and a late native result must not recreate its slot.
                assertEquals("0", evaluate("String(Object.keys(__pending).length)"))
                editorResult.complete("late selection")
                withTimeout(2_000) { stoppedEditor.await() }
                assertEquals("0", evaluate("String(Object.keys(__pending).length)"))
            } finally {
                pending.cancelAndJoin()
            }
        } finally {
            js.close()
        }
    }

    @Test fun webSocketCallbackCanAwaitNativeUiAndEditorBeforeCompletingTheApp() = runBlocking {
        val js = JsVoiceRuntime.create()
        val lock = Mutex()
        val enteredEditor = CompletableDeferred<Unit>()
        val editorResult = CompletableDeferred<String>()
        val uiUpdates = mutableListOf<String>()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            js.asyncFunction("__n_appEditor") {
                enteredEditor.complete(Unit)
                editorResult.await()
            }
            js.asyncFunction("__n_appUi") { args ->
                delay(4)
                uiUpdates += args[1].toString()
                null
            }
            JsVoiceRuntime.initialize(
                js,
                JsVoicePrelude.SOURCE + "\n" + """
                    var __resolveApp;
                    foxy.plugin.register({id:'native-interleave',version:'1',hosts:[],features:[]});
                    foxy.app.register({id:'tool',permissions:[],onOpen:function() {
                      return new Promise(function(resolve) { __resolveApp = resolve; });
                    }});
                    foxy.websocket.onMessage(async function(socket,text) {
                      var selected = await foxy.editor.getSelection();
                      await foxy.ui.dialog.setValue('result', selected + text);
                      __resolveApp({dialog:{type:'text',text:selected + text}});
                      return 'handled';
                    });
                """.trimIndent(),
            )
            suspend fun evaluate(code: String): String? = lock.withLock { js.evaluate<String>(code) }
            val app = async {
                JsVoiceInvoker.call(::evaluate, "app", "{\"appId\":\"tool\"}", 2_000)
            }
            try {
                withTimeout(2_000) {
                    while (evaluate("typeof __resolveApp") != "function") delay(4)
                }
                val callback = async {
                    JsVoiceInvoker.call(
                        ::evaluate,
                        "ws",
                        "{\"kind\":\"message\",\"socket\":\"1\",\"payload\":\" reply\"}",
                        2_000,
                    )
                }
                try {
                    withTimeout(2_000) { enteredEditor.await() }
                    // evaluate() waits for host async bindings, which retain the engine lock until
                    // their independent host work finishes. Do not make that work depend on a
                    // second evaluation (including a probe of __pending) needing the same lock.
                    assertFalse(app.isCompleted)
                    assertFalse(callback.isCompleted)
                    editorResult.complete("selected")
                    assertEquals("handled", callback.await().result!!.jsonPrimitive.content)
                    val result = app.await().result as JsonObject
                    assertEquals("selected reply", (result["dialog"] as JsonObject)["text"]!!.jsonPrimitive.content)
                    assertEquals(listOf("{\"id\":\"result\",\"value\":\"selected reply\"}"), uiUpdates)
                    assertEquals("0", evaluate("String(Object.keys(__pending).length)"))
                } finally {
                    callback.cancelAndJoin()
                }
            } finally {
                app.cancelAndJoin()
            }
        } finally {
            js.close()
        }
    }

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
