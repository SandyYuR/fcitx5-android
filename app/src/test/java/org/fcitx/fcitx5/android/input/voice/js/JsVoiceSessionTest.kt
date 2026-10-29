package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsVoiceSessionTest {
    @Before fun approveFixtureHosts() {
        listOf("js:review-http", "js:response", "js:review").forEach {
            JsVoiceHostPermissions.approve(it, setOf("example.com"))
        }
    }

    @Test fun unapprovedSessionFailsBeforeLoadingScriptAndCanRestartAfterApproval() {
        val id = "js:approval-session"
        JsVoiceHostPermissions.revoke(id)
        val loads = AtomicInteger()
        val provider = JsVoiceInputProvider(id, {
            loads.incrementAndGet()
            """foxy.plugin.register({hosts:["example.com"], configure(){},
                start(){foxy.voice.ready();}, stop(){return {ended:true};}});"""
        }, setOf("example.com"), locale = "en", http = OkHttpClient())
        try {
            val denied = Callback()
            provider.startSession(denied)
            assertTrue(denied.failed.await(5, TimeUnit.SECONDS))
            assertEquals(0, loads.get())
            JsVoiceHostPermissions.approve(id, setOf("example.com"))
            val approved = Callback()
            provider.startSession(approved)
            assertTrue(approved.ready.await(5, TimeUnit.SECONDS))
            assertEquals(1, loads.get())
            provider.endStream()
            assertTrue(approved.finished.await(5, TimeUnit.SECONDS))
            assertEquals(0, approved.errors.get())
            assertEquals(1, approved.ended.get())
            assertEquals(1, denied.errors.get())
        } finally { provider.stopSession(); JsVoiceHostPermissions.revoke(id) }
    }

    @Test fun cancellingSessionMustCancelHttpCall() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("{}".toResponseBody())
                .build()
        }.build()
        val provider = JsVoiceInputProvider(
            "js:review-http", {
                """foxy.plugin.register({hosts:["example.com"], configure(){},
                    start(){foxy.voice.ready();return "local";}, audio(){},
                    async stop(){await foxy.http.post("https://example.com/recognize", "", "{}", "{}");return {ended:true};},
                    onMessage(){}});"""
            }, setOf("example.com"), locale = "en", http = http)
        val callback = Callback()
        provider.startSession(callback)
        try {
            assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
            provider.endStream()
            assertTrue("HTTP request did not start", entered.await(5, TimeUnit.SECONDS))
            provider.cancelSession()
            kotlinx.coroutines.delay(100)
            assertTrue("Cancelling a voice session must cancel its active HTTP call", call.get().isCanceled())
        } finally {
            release.countDown()
            provider.stopSession()
        }
    }

    @Test fun undecryptableSecretMustNotBeShownAsConfigured() {
        val prefs = org.fcitx.fcitx5.android.utils.appContext.getSharedPreferences("js_voice_plugins", 0)
        val key = JsVoiceSettingsStore.secretKey("js:review", "apiKey")
        prefs.edit().putString(key, android.util.Base64.encodeToString(ByteArray(64), android.util.Base64.NO_WRAP)).commit()
        assertNull("Fixture must be undecryptable", JsVoiceSettingsStore.secret("js:review", "apiKey"))
        assertEquals(JsVoiceSettingsStore.SecretState.UNAVAILABLE, JsVoiceSettingsStore.secretState("js:review", "apiKey"))
        assertFalse("Undecryptable secret must require re-entry", JsVoiceSettingsStore.hasSecret("js:review", "apiKey"))
    }

    @Test fun asynchronousResponseProducesFinalAndAnOversizedResponseEndsTheSession() {
        for (oversized in listOf(false, true)) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body((if (oversized) "x".repeat(JsVoiceRuntime.RESPONSE_BYTES + 1) else "recognized").toResponseBody())
                    .build()
            }.build()
            val provider = JsVoiceInputProvider("js:response", {
                """foxy.plugin.register({hosts:["example.com"], configure(){},
                    start(){foxy.voice.ready();}, audio(){},
                    async stop(){const text = await foxy.http.post("https://example.com/asr", "", "{}", "{}");
                        return {final:text, ended:true};}});"""
            }, setOf("example.com"), locale = "en", http = http)
            val cb = Callback()
            try {
                provider.startSession(cb)
                assertTrue(cb.ready.await(5, TimeUnit.SECONDS))
                provider.endStream()
                assertTrue(cb.finished.await(5, TimeUnit.SECONDS))
                assertEquals(if (oversized) 1 else 0, cb.errors.get())
                assertEquals(if (oversized) null else "recognized", cb.finalText)
            } finally { provider.stopSession(); http.dispatcher.executorService.shutdownNow() }
        }
    }

    private class Callback : IVoiceInputCallback.Stub() {
        val ready = CountDownLatch(1)
        val errors = AtomicInteger()
        val failed = CountDownLatch(1)
        val ended = AtomicInteger()
        val finished = CountDownLatch(1)
        @Volatile var finalText: String? = null
        override fun onReady() { ready.countDown() }
        override fun onVolumeLevel(rms: Int) {}
        override fun onPartialResult(text: String?) {}
        override fun onSegmentFinal(text: String?) { finalText = text }
        override fun onSessionEnded() { ended.incrementAndGet(); finished.countDown() }
        override fun onError(code: Int, message: String?) { errors.incrementAndGet(); failed.countDown(); finished.countDown() }
    }

    @Test fun audioCallbackTimeoutMustNotifyManager() = runBlocking {
        val provider = JsVoiceInputProvider(
            "js:review", {
                """foxy.plugin.register({hosts:["example.com"], configure(){}, start(){foxy.voice.ready();return "local";},
                    audio(){return new Promise(function(){});}, stop(){return {ended:true};}, onMessage(){}});"""
            }, setOf("example.com"), locale = "en", http = OkHttpClient())
        val callback = Callback()
        provider.startSession(callback)
        try {
            assertTrue("Provider did not start", callback.ready.await(5, TimeUnit.SECONDS))
            provider.feedAudio(ByteArray(3200), 0, 3200, 0)
            assertTrue("Timeout must notify the manager", callback.failed.await(6, TimeUnit.SECONDS))
            assertEquals("Timeout must notify the manager to stop recording", 1, callback.errors.get())
        } finally { provider.stopSession() }
    }
}
