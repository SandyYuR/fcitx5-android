package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

class InputEngineApiTest {
    private fun engine(handler: (Method, Array<out Any?>) -> Any?): InputEngine = Proxy.newProxyInstance(
        InputEngine::class.java.classLoader, arrayOf(InputEngine::class.java)
    ) { _, method, args -> handler(method, args ?: emptyArray()) } as InputEngine

    @Test fun presentationAcknowledgementReachesTheEngineWithItsOriginalFrameTime() = runBlocking {
        val calls = mutableListOf<Pair<Long, Long>>()
        val api = InputEngineApi(engine { method, args ->
            check(method.name == "statusHintPresented")
            calls += (args[0] as Long) to (args[1] as Long)
            Unit
        })
        api.statusHintPresented(17, 1234)
        assertEquals(listOf(17L to 1234L), calls)
    }

    @Test fun cachedReadsDelegateWithoutExposingTheLifecycleInterface() {
        val api: FcitxAPI = InputEngineApi(engine { method, _ ->
            when (method.name) {
                "getInputSession" -> 42L
                "getAcceptsInput" -> true
                else -> error(method.name)
            }
        })
        assertEquals(42L, api.inputSession)
        assertTrue(api.acceptsInput)
        assertFalse(api is InputEngine)
    }

    @Test fun acknowledgementStillWaitsForTheSuspendingOwnerOperation() = runBlocking {
        val entered = CompletableDeferred<Continuation<Unit>>()
        var completed = false
        val api = InputEngineApi(engine { method, args ->
            check(method.name == "statusHintPresented")
            @Suppress("UNCHECKED_CAST")
            entered.complete(args[2] as Continuation<Unit>)
            COROUTINE_SUSPENDED
        })
        val task = launch { api.statusHintPresented(19, 2000); completed = true }
        val continuation = withTimeout(5000) { entered.await() }
        assertFalse(completed)
        continuation.resume(Unit)
        task.join()
        assertTrue(completed)
    }
}
