package org.fcitx.fcitx5.android.clipboardsync.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.resumeWithException

class OneClipEventClient internal constructor(serverUrl: String,
    private val httpClient: OkHttpClient, private val client: OkHttpClient,
    private val handshakeTimeoutMs: Long) {
    constructor(serverUrl: String) : this(serverUrl, sharedHttpClient, sharedSseClient, 15_000)
    companion object {
        private const val ROOT_PATH = "/"
        private const val CURRENT_PATH = "/api/current"
        private const val EVENTS_PATH = "/api/events"
        private val sharedHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(SyncTransportPolicy)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        private val sharedSseClient = sharedHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val baseUrl = normalizeServerUrl(serverUrl)
    private val connected = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val connectStarted = AtomicBoolean(false)
    private val callLock = Any()
    private val connectSignal = CompletableDeferred<Unit>()
    private val closeSignal = CompletableDeferred<Throwable?>()

    @Volatile
    private var activeCall: Call? = null

    suspend fun connect(onEvent: (OneClipEventData) -> Unit) {
        check(connectStarted.compareAndSet(false, true)) { "OneClip connection already started" }
        try {
            warmupSession()
            openEventStream(onEvent)
            try { withTimeout(handshakeTimeoutMs) { connectSignal.await() } }
            catch (e: TimeoutCancellationException) { throw IOException("OneClip SSE handshake timed out", e) }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    suspend fun awaitClose(): Throwable? = closeSignal.await()

    fun isConnected(): Boolean = connected.get()

    fun close() {
        val call = synchronized(callLock) {
            closeRequested.set(true)
            connected.set(false)
            activeCall.also { activeCall = null }
        }
        call?.cancel()
        if (!connectSignal.isCompleted) connectSignal.completeExceptionally(IOException("OneClip connection closed"))
        if (!closeSignal.isCompleted) {
            closeSignal.complete(null)
        }
    }

    private fun track(call: Call) = synchronized(callLock) {
        if (closeRequested.get()) { call.cancel(); throw IOException("OneClip connection closed") }
        activeCall = call
    }

    private fun release(call: Call) = synchronized(callLock) {
        if (activeCall === call) activeCall = null
    }

    private suspend fun warmupSession() = withContext(Dispatchers.IO) {
        request(ROOT_PATH)
        request(CURRENT_PATH)
    }

    private fun openEventStream(onEvent: (OneClipEventData) -> Unit) {
        val request = Request.Builder()
            .url(url(EVENTS_PATH))
            .get()
            .build()
        val call = client.newCall(request)
        track(call)

        thread(
            name = "OneClipEventStream",
            isDaemon = true
        ) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("OneClip SSE failed: ${response.code} ${response.message}")
                    }
                    synchronized(callLock) {
                        if (closeRequested.get()) throw IOException("OneClip connection closed")
                        connected.set(true)
                        if (!connectSignal.isCompleted) connectSignal.complete(Unit)
                    }

                    val source = response.body?.source()
                        ?: throw IOException("OneClip SSE body is empty")
                    val dataLines = mutableListOf<String>()

                    while (!closeRequested.get()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) {
                            dispatchEvent(dataLines, onEvent)
                            dataLines.clear()
                            continue
                        }
                        if (line.startsWith("data:")) {
                            dataLines += line.removePrefix("data:").trimStart()
                        }
                    }
                }

                if (!closeRequested.get()) {
                    fail(IOException("OneClip SSE disconnected"))
                } else {
                    finishGracefully()
                }
            } catch (error: Exception) {
                if (closeRequested.get() || isCanceled(error)) {
                    finishGracefully()
                } else {
                    fail(error)
                }
            } finally {
                release(call)
            }
        }
    }

    private fun dispatchEvent(
        dataLines: List<String>,
        onEvent: (OneClipEventData) -> Unit
    ) {
        if (dataLines.isEmpty()) return
        val payload = dataLines.joinToString("\n")
        val event = runCatching { json.decodeFromString<OneClipEventData>(payload) }
            .getOrElse { return }
        onEvent(event)
    }

    private suspend fun request(path: String) {
        val request = Request.Builder()
            .url(url(path))
            .get()
            .build()
        val call = httpClient.newCall(request)
        track(call)
        try {
            awaitResponse(call).use { response ->
                if (!response.isSuccessful) throw IOException("OneClip warmup failed for $path: ${response.code} ${response.message}")
            }
        } finally { release(call) }
    }

    private suspend fun awaitResponse(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun fail(error: Throwable) {
        connected.set(false)
        if (!connectSignal.isCompleted) {
            connectSignal.completeExceptionally(error)
        }
        if (!closeSignal.isCompleted) {
            closeSignal.complete(error)
        }
    }

    private fun finishGracefully() {
        connected.set(false)
        if (!connectSignal.isCompleted) {
            connectSignal.completeExceptionally(IOException("OneClip SSE closed before ready"))
        }
        if (!closeSignal.isCompleted) {
            closeSignal.complete(null)
        }
    }

    private fun url(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return baseUrl + normalizedPath
    }

    private fun normalizeServerUrl(url: String): String {
        return SyncServerAddress.normalize(url)
    }

    private fun isCanceled(error: Exception): Boolean {
        return error is IOException && error.message?.contains("canceled", ignoreCase = true) == true
    }
}
