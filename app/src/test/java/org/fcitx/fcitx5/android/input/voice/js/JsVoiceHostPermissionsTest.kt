/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsVoiceHostPermissionsTest {
    private val id = "js:permission-test"
    private val hosts = setOf("asr.example.com")
    private val policy get() = JsVoiceNetworkPolicy(id, hosts)

    // Network bindings return Promises. Exercise them through the same await/pump path as a plugin.
    private suspend fun invokeNative(js: QuickJs, code: String): String? {
        js.evaluate<Any?>("foxy.plugin.register({start: async function() { return ($code); }});")
        val result = JsVoiceInvoker.call({ js.evaluate<String>(it) }, "start", "{}", 5000)
        result.error?.let { error(it) }
        return result.result?.jsonPrimitive?.contentOrNull
    }

    @Before fun before() { JsVoiceHostPermissions.revoke(id); JsVoiceHostPermissions.revoke("js:other") }
    @After fun after() { before() }

    @Test fun oldInstallationsHaveNoImplicitConsentAndApprovalIsPerPluginAndHost() {
        assertFalse(JsVoiceHostPermissions.isApproved(id, hosts))
        assertNotNull(runCatching { policy.requireApproval() }.exceptionOrNull())
        JsVoiceHostPermissions.approve(id, hosts)
        policy.requireApproval()
        assertFalse(JsVoiceHostPermissions.isApproved("js:other", hosts))
        assertFalse(JsVoiceHostPermissions.isApproved(id, hosts + "new.example.com"))
        assertNotNull(runCatching { JsVoiceNetworkPolicy(id, hosts + "new.example.com").requireApproval() }.exceptionOrNull())
        JsVoiceHostPermissions.revoke(id)
        assertNotNull(runCatching { policy.requireApproval() }.exceptionOrNull())
    }

    @Test fun urlValidationUsesTheSameCanonicalHostAsTheRequest() {
        JsVoiceHostPermissions.approve(id, hosts)
        assertEquals("asr.example.com", policy.checkUrl("wss://ASR.EXAMPLE.COM/api").host)
        for (url in listOf(
            "https://asr.example.com@evil.example/x", "https://asr.example.com.evil.example/",
            "https://evil.example/", "http://asr.example.com/", "ws://asr.example.com/",
            "https://asr.example.com\\@evil.example/", "https://@asr.example.com/",
            "https://asr.example.com:bad/x", "https://asr.example.com%2e.evil.example/x",
        )) assertNotNull(url, runCatching { policy.checkUrl(url) }.exceptionOrNull())
    }

    @Test fun nativeHttpAndWebSocketRejectBeforeReadingAnySecretOrSendingAnyRequest() = runBlocking {
        var requests = 0
        var secrets = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("ok".toResponseBody()).build()
        }.build()
        val net = JsVoiceNet(client, policy, {}) { secrets++; "sensitive-test-value" }
        val js = QuickJs.create(Dispatchers.IO)
        try {
            net.install(js, "en")
            js.evaluate<Any?>(JsVoicePrelude.SOURCE)
            suspend fun attempt(code: String) {
                assertNotNull(code, runCatching { invokeNative(js, code) }.exceptionOrNull())
                assertEquals(0, requests)
                assertEquals(0, secrets)
            }
            attempt("__n_httpPost('https://asr.example.com/x', 'apiKey', '{}', '{}')")
            attempt("__n_wsConnect('wss://asr.example.com/x', 'apiKey', '', '', '{}')")
            attempt("__n_hmacSha1Base64('secretKey', 'payload')")
            JsVoiceHostPermissions.approve(id, hosts)
            attempt("__n_httpPost('https://evil.example/x', 'apiKey', '{}', '{}')")
            attempt("__n_wsConnect('wss://evil.example/x', 'apiKey', '', '', '{}')")
            assertEquals("ok", invokeNative(js, "__n_httpPost('https://asr.example.com/x', 'apiKey', '{}', '{}')"))
            assertEquals(1, requests)
            assertEquals(1, secrets)
            JsVoiceHostPermissions.revoke(id)
            assertNotNull(runCatching {
                invokeNative(js, "__n_httpPost('https://asr.example.com/x', 'apiKey', '{}', '{}')")
            }.exceptionOrNull())
            assertEquals(1, requests)
            assertEquals(1, secrets)
        } finally { net.closeAll(); js.close() }
    }

    @Test fun nativeHttpNeverFollowsARedirectToAnotherHost() = runBlocking {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        JsVoiceHostPermissions.approve(id, setOf("localhost"))
        val net = JsVoiceNet(client, JsVoiceNetworkPolicy(id, setOf("localhost")), {}) { "test-key" }
        val js = QuickJs.create(Dispatchers.IO)
        try {
            // A 307 would preserve the signed POST body and non-standard auth headers.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(307)
                .setHeader("Location", "https://127.0.0.1:${server.port}/stolen"))
            net.install(js, "en")
            js.evaluate<Any?>(JsVoicePrelude.SOURCE)
            val url = JsonPrimitive(server.url("/voice").newBuilder().host("localhost").build().toString())
            val error = runCatching { invokeNative(js, "__n_httpPost($url, 'apiKey', '{}', '{}')") }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("HTTP 307"))
            assertEquals(1, server.requestCount)
            assertEquals("Bearer test-key", server.takeRequest().getHeader("Authorization"))
        } finally { net.closeAll(); js.close(); server.shutdown() }
    }
}
