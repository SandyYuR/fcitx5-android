/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class JsAppHttpTest {
    @Test fun deniesUrlBeforeLoadingCredentials() = runBlocking {
        var secretReads = 0
        val http = JsAppHttp({ error("not authorized") }, { secretReads++; "test-secret" })
        val result = runCatching { http.request(buildJsonObject { put("url", "https://evil.example/"); put("credential", "apiKey") }) }
        assertTrue(result.isFailure)
        assertEquals(0, secretReads)
    }

    @Test fun nativeSecretInjectionAndHttpErrorsArePreserved() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("Bearer test-secret", chain.request().header("Authorization"))
            assertTrue(chain.request().body!!.contentType().toString().startsWith("application/json"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .header("Location", "https://unapproved.example/").body("redirect".toResponseBody()).build()
        }.build()
        val http = JsAppHttp({ it.toHttpUrl() }, { "test-secret" }, client)
        val result = Json.parseToJsonElement(http.request(buildJsonObject {
            put("url", "https://approved.example/v1/chat/completions")
            put("method", "POST"); put("credential", "apiKey"); put("body", "{}")
        })).jsonObject
        assertEquals(401, result["status"]!!.jsonPrimitive.int)
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, requests)
        assertFalse(result.toString().contains("test-secret"))
    }

    @Test fun revokedPermissionAfterCredentialReadPreventsSending() = runBlocking {
        var approved = true
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        val http = JsAppHttp({ check(approved) { "Permission revoked" }; it.toHttpUrl() },
            { approved = false; "test-secret" }, client)
        assertTrue(runCatching { http.request(buildJsonObject {
            put("url", "https://approved.example/"); put("credential", "apiKey")
        }) }.isFailure)
        assertEquals(0, requests)
    }

    @Test fun anthropicCredentialsAreInjectedWithoutBearerAndVersionHeaderIsPreserved() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("anthropic-secret", chain.request().header("x-api-key"))
            assertNull(chain.request().header("Authorization"))
            assertEquals("2023-06-01", chain.request().header("anthropic-version"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        val response = JsAppHttp({ it.toHttpUrl() }, { "anthropic-secret" }, client).request(buildJsonObject {
            put("url", "https://api.anthropic.com/v1/messages"); put("method", "POST")
            put("credential", "anthropicKey"); put("authHeader", "x-api-key"); put("authPrefix", "")
            put("headers", buildJsonObject { put("anthropic-version", "2023-06-01") }); put("body", "{}")
        })
        assertFalse(response.contains("anthropic-secret"))
    }

    @Test fun oversizedResponsesAreRejected() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("x".repeat(JsVoiceRuntime.RESPONSE_BYTES + 1).toResponseBody()).build()
        }.build()
        val http = JsAppHttp({ it.toHttpUrl() }, { null }, client)
        assertTrue(runCatching { http.request(buildJsonObject { put("url", "https://approved.example/") }) }.isFailure)
    }
    private fun tlsServer(): Pair<okhttp3.mockwebserver.MockWebServer, OkHttpClient> {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        return server to OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    @Test fun httpHostHeaderComesFromTheConfiguredUrlAndCannotBeOverridden() = runBlocking {
        val (server, client) = tlsServer()
        val endpoint = server.url("/v1/responses")
        val policy = JsAppEndpointPolicy(emptySet(), mapOf("customUrl" to endpoint.toString()))
        val http = JsAppHttp(policy::checkUrl, { "test-secret" }, client)
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            http.request(buildJsonObject {
                put("url", endpoint.toString()); put("method", "POST"); put("body", "{}")
            })
            val actual = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals(endpoint.host + ":" + server.port, actual.getHeader("Host") ?: actual.getHeader(":authority"))
            assertEquals("/v1/responses", actual.path)
            assertTrue(runCatching { http.request(buildJsonObject {
                put("url", endpoint.toString())
                put("headers", buildJsonObject { put("Host", "different.example") })
            }) }.isFailure)
            assertTrue(runCatching { http.request(buildJsonObject {
                put("url", endpoint.toString()); put("credential", "customKey")
                put("authHeader", "Host"); put("authPrefix", "")
            }) }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun realHttpRedirectIsNeverFollowed() = runBlocking {
        val (server, client) = tlsServer()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/must-not-follow")))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("unexpected"))
            val result = JsAppHttp({ it.toHttpUrl() }, { null }, client).request(buildJsonObject {
                put("url", server.url("/start").toString())
            })
            assertEquals(302, Json.parseToJsonElement(result).jsonObject["status"]!!.jsonPrimitive.int)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancellingSessionCancelsTheInFlightHttpCall() = runBlocking {
        val (server, client) = tlsServer()
        val cancelled = CompletableDeferred<Unit>()
        val observedClient = client.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.complete(Unit) }
        }).build()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val request = async(Dispatchers.IO) {
                JsAppHttp({ it.toHttpUrl() }, { null }, observedClient).request(buildJsonObject {
                    put("url", server.url("/slow").toString())
                })
            }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) })
            request.cancelAndJoin()
            withTimeout(5000) { cancelled.await() }
            assertTrue(request.isCancelled)
        } finally { server.shutdown() }
    }

    @Test fun requestTimeoutBoundsAStalledResponse() = runBlocking {
        val (server, client) = tlsServer()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val started = System.nanoTime()
            val result = runCatching {
                withTimeout(5000) {
                    JsAppHttp({ it.toHttpUrl() }, { null }, client).request(buildJsonObject {
                        put("url", server.url("/timeout").toString()); put("timeoutMs", 300)
                    })
                }
            }
            assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is java.io.IOException)
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

}
