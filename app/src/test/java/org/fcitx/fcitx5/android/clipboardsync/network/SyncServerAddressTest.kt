/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class SyncServerAddressTest {
    @Test fun omittedSchemesUseHttpsForHostsPathsAndIpv6() {
        assertEquals("https://sync.example.com", SyncServerAddress.normalize(" sync.example.com/ "))
        assertEquals("https://localhost:5033/prefix", SyncServerAddress.normalize("localhost:5033/prefix/"))
        assertEquals("https://[::1]:5033", SyncServerAddress.normalize("[::1]:5033"))
    }
    @Test fun explicitHttpAndWebSocketSchemesRetainTheirChosenTransport() {
        assertEquals("http://localhost:5033", SyncServerAddress.normalize("http://localhost:5033/"))
        assertEquals("https://example.com", SyncServerAddress.normalize("HTTPS://EXAMPLE.COM/"))
        assertEquals("http://example.com", SyncServerAddress.normalize("ws://example.com"))
        assertEquals("https://example.com", SyncServerAddress.normalize("wss://example.com"))
    }
    @Test fun malformedOrUnsupportedAddressesFailBeforeConstructingRequests() {
        for (value in listOf("", " ", "ftp://example.com", "localhost:70000", "https://")) {
            assertThrows(value, IOException::class.java) { SyncServerAddress.normalize(value) }
        }
    }
    @Test fun fragmentsAreNotMistakenForServerPaths() {
        assertEquals("https://example.com/root", SyncServerAddress.normalize("example.com/root#fragment"))
    }

    private fun certificates(): Pair<HandshakeCertificates, HandshakeCertificates> {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        return HandshakeCertificates.Builder().heldCertificate(certificate).build() to
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    }
    private fun client(trust: HandshakeCertificates) = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
        .addNetworkInterceptor(SyncTransportPolicy).callTimeout(5, TimeUnit.SECONDS).build()

    @Test fun anHttps307CannotSendLoginFormDataToAnHttpRedirect() {
        val (serverCertificate, trust) = certificates()
        MockWebServer().use { clear -> MockWebServer().use { secure ->
            secure.useHttps(serverCertificate.sslSocketFactory(), false)
            clear.enqueue(MockResponse().setBody("must not receive the login"))
            secure.enqueue(MockResponse().setResponseCode(307).addHeader("Location", clear.url("/leak")))
            val client = client(trust)
            try {
                val request = Request.Builder().url(secure.url("/login"))
                    .post(FormBody.Builder().add("password", "fixture-password").build()).build()
                val error = assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
                assertTrue(error.message.orEmpty().contains("HTTPS to HTTP"))
                assertTrue(secure.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().contains("fixture-password"))
                assertNull("No body or headers may reach the plaintext destination", clear.takeRequest(200, TimeUnit.MILLISECONDS))
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        } }
    }

    @Test fun explicitlyChosenHttpCanStillUpgradeToHttps() {
        val (serverCertificate, trust) = certificates()
        MockWebServer().use { clear -> MockWebServer().use { secure ->
            secure.useHttps(serverCertificate.sslSocketFactory(), false)
            secure.enqueue(MockResponse().setBody("ok"))
            clear.enqueue(MockResponse().setResponseCode(302).addHeader("Location", secure.url("/secure")))
            val client = client(trust)
            try { client.newCall(Request.Builder().url(clear.url("/start")).build()).execute().use {
                assertEquals("ok", it.body!!.string()); assertTrue(it.request.url.isHttps)
            } } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        } }
    }
}
