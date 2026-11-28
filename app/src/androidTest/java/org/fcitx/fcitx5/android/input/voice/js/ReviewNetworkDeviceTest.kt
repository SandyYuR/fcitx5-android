/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.fcitx.fcitx5.android.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real device sockets only; no interceptors, permissive TLS, accounts, or editor contents. */
class ReviewNetworkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun defaultTlsGetRequiresApprovalAndVersionReplacementRevokesIt() = runBlocking {
        requireDebugPackage()
        val id = "review-network-" + UUID.randomUUID().toString()
        val plugin = "js:$id"
        val app = JsPluginApp("network", "Network fixture", setOf("network.request"))
        val appKey = plugin + "/" + app.id
        val host = "example.com"
        val meta = JsVoicePluginMeta(id, "Network device fixture", "1", listOf(host),
            emptyList(), emptyList(), listOf(app))
        val cache = File(context.cacheDir, id).apply { check(mkdirs()) }
        val imported = context.getSharedPreferences("js_voice_imported_plugins", Context.MODE_PRIVATE)
        val hosts = context.getSharedPreferences("js_voice_host_permissions", Context.MODE_PRIVATE)
        val apps = context.getSharedPreferences("js_app_permissions", Context.MODE_PRIVATE)
        // Only these fresh, UUID-owned keys may be written. Never clear a preference file.
        check(!imported.contains("p:$id") && !hosts.contains(plugin) && !apps.contains(appKey))
        var saved = false
        try {
            fun source(version: String) = "foxy.plugin.register({id:'$id',name:'Network device fixture'," +
                "version:'$version',hosts:['example.com'],features:[]});"
            val first = source("1")
            File(cache, "fixture-v1.js").writeText(first)
            assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "fixture-v1.js", 1L), first))
            saved = true
            val firstDigest = checkNotNull(JsVoicePluginStore.recordOf(id)).scriptDigest
            val network = JsVoiceNetworkPolicy(plugin, setOf(host))
            val endpoint = JsAppEndpointPolicy(setOf(host), emptyMap())
            val http = JsAppHttp(checkUrl = { url ->
                val destination = endpoint.checkUrl(url)
                network.checkUrl(url)
                check("network.request" in JsAppPermissions.granted(plugin, app.id)) { "App permission denied" }
                destination
            }, secret = { error("This fixture must never load a credential") })
            val options = buildJsonObject { put("url", "https://example.com/"); put("timeoutMs", 30_000) }
            // The exact same production request is denied before any network Call can be built.
            val unapproved = runCatching { http.request(options) }.exceptionOrNull()
            assertTrue("Unapproved host must be rejected", unapproved is IllegalStateException)
            JsVoiceHostPermissions.approve(plugin, setOf(host))
            JsAppPermissions.grant(plugin, app.id, app.permissions)
            // Deliberately omit the client parameter: production defaults and system trust apply.
            val response = Json.parseToJsonElement(withTimeout(35_000) { http.request(options) }).jsonObject
            assertEquals(200, response.getValue("status").jsonPrimitive.int)
            assertTrue(response.getValue("ok").jsonPrimitive.boolean)
            val body = response.getValue("body").jsonPrimitive.content
            assertTrue("Unexpected HTTPS response", body.contains("Example Domain"))
            evidence("public-default-tls", buildJsonObject {
                put("url", "https://example.com/"); put("method", "GET")
                put("status", response.getValue("status")); put("bodyBytes", body.toByteArray().size)
                put("defaultClient", true); put("credentials", false)
            })
            val second = source("2")
            File(cache, "fixture-v2.js").writeText(second)
            assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta.copy(version = "2"), "fixture-v2.js", 2L), second))
            val stored = checkNotNull(JsVoicePluginStore.recordOf(id))
            assertEquals("2", stored.meta.version)
            assertNotEquals(firstDigest, stored.scriptDigest)
            assertTrue(JsVoiceHostPermissions.approvedHosts(plugin).isEmpty())
            assertTrue(JsAppPermissions.granted(plugin, app.id).isEmpty())
            val obsolete = runCatching { http.request(options) }.exceptionOrNull()
            assertTrue("Old approval survived source replacement", obsolete is IllegalStateException)
            evidence("version-revocation", buildJsonObject {
                put("version", stored.meta.version); put("hostGrantRevoked", true); put("appGrantRevoked", true)
            })
        } finally {
            withContext(NonCancellable) {
                val cleanups = listOf(
                    runCatching { if (saved) check(JsVoicePluginStore.delete(id)) { "Cannot remove fixture source" } },
                    runCatching { check(hosts.edit().remove(plugin).commit()) },
                    runCatching { check(apps.edit().remove(appKey).commit()) },
                    runCatching { check(imported.edit().remove("p:$id").commit()) },
                    runCatching {
                        check(cache.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                        check(cache.deleteRecursively())
                    },
                )
                cleanups.forEach { it.getOrThrow() }
                assertFalse(imported.contains("p:$id"))
                assertFalse(hosts.contains(plugin))
                assertFalse(apps.contains(appKey))
            }
        }
    }

    @Test fun cancellationClosesARealSocketWhileTlsHandshakeIsBlocked() = runBlocking {
        requireDebugPackage()
        val plugin = "js:review-cancel-" + UUID.randomUUID().toString()
        val hosts = context.getSharedPreferences("js_voice_host_permissions", Context.MODE_PRIVATE)
        check(!hosts.contains(plugin))
        val accepted = CompletableDeferred<Unit>()
        val peerClosed = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val callFinished = CompletableDeferred<Unit>()
        val socket = AtomicReference<Socket?>()
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val server = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = server.launch {
            try {
                listener.accept().use { peer ->
                    socket.set(peer)
                    peer.soTimeout = 15_000
                    // Read actual ClientHello bytes, then withhold ServerHello until cancellation.
                    val buffer = ByteArray(4096)
                    check(peer.getInputStream().read(buffer) > 0) { "TLS ClientHello missing" }
                    accepted.complete(Unit)
                    while (peer.getInputStream().read(buffer) >= 0) Unit
                    peerClosed.complete(Unit)
                }
            } catch (error: Throwable) {
                if (error is SocketException && accepted.isCompleted) peerClosed.complete(Unit)
                else {
                    accepted.completeExceptionally(error)
                    peerClosed.completeExceptionally(error)
                }
            }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) { canceled.complete(Unit) }
                override fun callFailed(call: Call, ioe: java.io.IOException) { callFinished.complete(Unit) }
                override fun callEnd(call: Call) { callFinished.complete(Unit) }
            }).build()
        var request: Deferred<String>? = null
        try {
            JsVoiceHostPermissions.approve(plugin, setOf("127.0.0.1"))
            val policy = JsVoiceNetworkPolicy(plugin, setOf("127.0.0.1"))
            request = async(Dispatchers.IO) {
                JsAppHttp(policy::checkUrl, { error("No fixture credentials") }, client).request(buildJsonObject {
                    put("url", "https://127.0.0.1:" + listener.localPort + "/blocked")
                    put("timeoutMs", 30_000)
                })
            }
            withTimeout(10_000) { accepted.await() }
            assertFalse("Fixture must still be blocked before cancellation", request.isCompleted)
            withTimeout(5_000) { request.cancelAndJoin() }
            withTimeout(5_000) { canceled.await(); callFinished.await(); peerClosed.await(); worker.join() }
            assertTrue(request.isCancelled)
            withTimeout(5_000) {
                while (client.dispatcher.runningCallsCount() != 0 || client.dispatcher.queuedCallsCount() != 0) delay(10)
            }
            evidence("cancel-blocked-tls-socket", buildJsonObject {
                put("tcpAccepted", true); put("tlsClientHelloReceived", true); put("callCanceled", true)
                put("callFinished", true); put("peerObservedClose", true)
                put("runningCalls", 0); put("queuedCalls", 0); put("customTrust", false)
            })
        } finally {
            withContext(NonCancellable) {
                val cleanups = listOf(
                    runCatching { request?.cancelAndJoin() },
                    runCatching { client.dispatcher.cancelAll() },
                    runCatching { socket.getAndSet(null)?.close() },
                    runCatching { listener.close() },
                    runCatching { withTimeout(5_000) { worker.join() }; server.cancel() },
                    runCatching {
                        client.connectionPool.evictAll()
                        client.dispatcher.executorService.shutdown()
                        check(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
                    },
                    runCatching { check(hosts.edit().remove(plugin).commit()) },
                )
                cleanups.forEach { it.getOrThrow() }
                assertFalse(hosts.contains(plugin))
            }
        }
    }

    private fun requireDebugPackage() {
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug")) { "Run only the Debug package" }
    }

    private fun evidence(name: String, detail: JsonObject) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("reviewNetworkEvidence", buildJsonObject { put("case", name); put("detail", detail) }.toString())
        })
    }
}
