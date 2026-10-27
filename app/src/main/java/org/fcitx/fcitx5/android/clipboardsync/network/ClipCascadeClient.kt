package org.fcitx.fcitx5.android.clipboardsync.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.utf8Size
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class ClipCascadeClient internal constructor(
    serverUrl: String,
    private val username: String,
    private val password: String,
    private val budget: ClipCascadePayloadBudget
) {
    constructor(serverUrl: String, username: String, password: String) :
        this(serverUrl, username, password, ClipCascadePayloadBudget.DEFAULT)

    companion object {
        private const val TAG = "FcitxClipboardSync"
        private const val LOGIN_PATH = "/login"
        private const val VALIDATE_SESSION_PATH = "/api/validate-session"
        private const val USER_INFO_PATH = "/api/user-info"
        private const val SOCKET_PATH = "/clipsocket"
        private const val SUBSCRIPTION_ID = "android-clipboard-sync"
        private const val SUBSCRIPTION_DESTINATION = "/user/queue/cliptext"
        private const val SEND_DESTINATION = "/app/cliptext"
        private const val AES_KEY_SIZE_BITS = 256

        /**
         * Longest wait for the STOMP CONNECTED frame after the websocket opens.
         *
         * Needed because the OkHttp read timeout is 0 (unbounded) for the websocket; see C16.
         */
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L

        /**
         * Bounds for the server-provided PBKDF2 iteration count; see C19.
         *
         * [MAX_HASH_ROUNDS] is enforced — it keeps a hostile or misconfigured server from pinning
         * the IO thread for minutes. [MIN_HASH_ROUNDS] is only a warning threshold: a server
         * configured below it is unusual but its value still has to be used verbatim, or the
         * derived key disagrees with every other client.
         */
        private const val MIN_HASH_ROUNDS = 100_000
        private const val MAX_HASH_ROUNDS = 2_000_000
        private const val GCM_NONCE_SIZE_BYTES = 16
        private const val GCM_TAG_SIZE_BYTES = 16

        /** Read timeout for the plain HTTP calls; see [httpClient]. */
        private const val HTTP_READ_TIMEOUT_SECONDS = 30L

        /**
         * Largest login / validate-session / user-info response body accepted.
         *
         * These are small JSON documents; reading them with an unbounded `string()` let a hostile
         * or broken server allocate without limit inside the IME process (see A9).
         */
        private const val MAX_HTTP_BODY_BYTES = 1L * 1024 * 1024
        private val sharedTransport = OkHttpClient()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val baseUrl = normalizeServerUrl(serverUrl)
    private val cookieJar = MemoryCookieJar()

    private val transport = sharedTransport.newBuilder()
        .addNetworkInterceptor(SyncTransportPolicy)
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /** HTTP login keeps its normal pool and bounded read timeout. */
    private val httpClient = transport.newBuilder()
        .readTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Separate guarded sockets: no HTTP connection can bypass the pre-assembly limit. */
    private val client = BoundedWebSocketTransport.client(transport,
        secure = baseUrl.startsWith("https://"), maxMessage = budget.maxReceivedFrameBytes)

    private val connected = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val connectSignal = CompletableDeferred<Unit>()
    private val closeSignal = CompletableDeferred<Throwable?>()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var encryptionKey: ByteArray? = null

    /**
     * Log in, open the websocket and wait for the STOMP CONNECTED frame.
     *
     * The wait is bounded: [connectSignal] can only be completed from an OkHttp callback, and
     * the client uses `readTimeout(0)` (unbounded, as a websocket must). A server that completes
     * the TCP/websocket handshake but never sends CONNECTED — restarting, half-open reverse
     * proxy, wrong service on the port — used to park the caller forever (see C16).
     */
    suspend fun connect(onMessage: (ClipCascadeClipboardData) -> Unit) {
        login()
        validateSession()
        deriveEncryptionKey()
        openSocket(onMessage)
        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                connectSignal.await()
            }
        } catch (e: TimeoutCancellationException) {
            close()
            throw IOException("ClipCascade STOMP handshake timed out after ${HANDSHAKE_TIMEOUT_MS}ms", e)
        } catch (t: Throwable) {
            // The socket is already open at this point, so anything that aborts the wait —
            // including plain cancellation — has to close it, or the connection and its listener
            // leak for the life of the process.
            close()
            throw t
        }
    }

    suspend fun sendClipboard(payload: String, type: String = "text", filename: String? = null) {
        val activeSocket = webSocket ?: throw IOException("ClipCascade websocket is not connected")
        if (!connected.get()) {
            throw IOException("ClipCascade websocket handshake is not complete")
        }

        val body = encodeOutgoingBody(
            ClipCascadeClipboardData(
                payload = payload,
                type = type,
                filename = filename
            )
        )
        val sent = activeSocket.send(
            encodeStompFrame(
                command = "SEND",
                headers = linkedMapOf("destination" to SEND_DESTINATION),
                body = body
            )
        )
        if (!sent) {
            throw IOException("Failed to queue ClipCascade message")
        }
    }

    fun close() {
        closeRequested.set(true)
        connected.set(false)
        webSocket?.send(encodeStompFrame("DISCONNECT"))
        webSocket?.close(1000, "disconnect")
        webSocket = null
        if (!closeSignal.isCompleted) {
            closeSignal.complete(null)
        }
    }

    suspend fun awaitClose(): Throwable? = closeSignal.await()

    fun isConnected(): Boolean = connected.get()

    suspend fun testConnection(): String {
        return try {
            connect { }
            "Connection Successful: ClipCascade session established"
        } finally {
            close()
        }
    }

    private suspend fun login() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$LOGIN_PATH")
            .post(
                FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .build()
            )
            .build()

        httpClient.newCall(request).execute().use { response ->
            val location = response.header("Location").orEmpty()
            if (!response.isRedirect && !response.isSuccessful) {
                throw IOException("ClipCascade login failed: ${response.code} ${response.message}")
            }
            if (location.contains("error", ignoreCase = true)) {
                throw IOException("ClipCascade login failed: invalid credentials")
            }
        }
    }

    private suspend fun validateSession() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$VALIDATE_SESSION_PATH")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ClipCascade session validation failed: ${response.code} ${response.message}")
            }
            val body = response.bodyStringAtMost()
            val validation = json.decodeFromString<ClipCascadeSessionValidationResponse>(body)
            if (!validation.valid) {
                throw IOException("ClipCascade session is not valid")
            }
        }
    }

    private suspend fun deriveEncryptionKey() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$USER_INFO_PATH")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ClipCascade user-info failed: ${response.code} ${response.message}")
            }
            val body = response.bodyStringAtMost()
            val userInfo = json.decodeFromString<ClipCascadeUserInfoResponse>(body)
            // The iteration count comes straight off the wire, and the derived key must match
            // what every other client computes, so the server value is used as given (see C19).
            // Two exceptions, both of which cannot be honoured at all:
            //  - 0 or negative: PBEKeySpec rejects it outright;
            //  - absurdly large: PBKDF2 would pin a thread for minutes, so cap it and let the
            //    resulting key mismatch surface as a decryption error rather than a freeze.
            // A value merely *below* MIN_HASH_ROUNDS is honoured with a warning: clamping it up
            // produced a key that silently disagreed with the desktop client, which is the very
            // failure C19 exists to remove.
            val reported = userInfo.hashRounds
            if (reported <= 0) {
                throw IOException("ClipCascade reported an invalid hash_rounds of $reported")
            }
            val rounds = reported.coerceAtMost(MAX_HASH_ROUNDS)
            if (rounds != reported) {
                Log.w(TAG, "ClipCascade hash_rounds $reported above the cap, using $rounds")
            } else if (rounds < MIN_HASH_ROUNDS) {
                Log.w(TAG, "ClipCascade hash_rounds $rounds is weak but honoured for compatibility")
            }
            encryptionKey = deriveKey(
                password = password,
                username = username,
                salt = userInfo.salt,
                rounds = rounds
            )
        }
    }

    private fun openSocket(onMessage: (ClipCascadeClipboardData) -> Unit) {
        val request = Request.Builder()
            .url(webSocketUrl())
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val connectSent = webSocket.send(
                    encodeStompFrame(
                        command = "CONNECT",
                        headers = linkedMapOf(
                            "accept-version" to "1.1",
                            "host" to "localhost"
                        )
                    )
                )
                if (!connectSent) {
                    fail(IOException("Failed to send ClipCascade CONNECT frame"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length <= 2 && text.isBlank()) return

                val frame = runCatching { parseStompFrame(text) }.getOrElse { error ->
                    Log.w(TAG, "ClipCascade invalid STOMP frame (${error.javaClass.simpleName})")
                    if (error is ClipCascadeMessageTooLargeException) fail(error)
                    return
                }

                when (frame.command.uppercase(Locale.ROOT)) {
                    "CONNECTED" -> {
                        val subscribed = webSocket.send(
                            encodeStompFrame(
                                command = "SUBSCRIBE",
                                headers = linkedMapOf(
                                    "id" to SUBSCRIPTION_ID,
                                    "destination" to SUBSCRIPTION_DESTINATION
                                )
                            )
                        )
                        if (!subscribed) {
                            fail(IOException("Failed to subscribe to ClipCascade clipboard queue"))
                            return
                        }
                        connected.set(true)
                        if (!connectSignal.isCompleted) {
                            connectSignal.complete(Unit)
                        }
                    }

                    "MESSAGE" -> {
                        val clipboardData = runCatching {
                            decodeIncomingBody(frame.body)
                        }.getOrElse { error ->
                            Log.w(TAG, "ClipCascade payload decode failed (${error.javaClass.simpleName})")
                            if (error is ClipCascadeMessageTooLargeException) fail(error)
                            return
                        }
                        onMessage(clipboardData)
                    }

                    "ERROR" -> {
                        fail(IOException("ClipCascade STOMP error: ${frame.headers["message"].orEmpty()}"))
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                if (!connectSignal.isCompleted) {
                    connectSignal.completeExceptionally(
                        IOException("ClipCascade websocket closed before handshake: $code $reason")
                    )
                }
                finishClose(
                    if (closeRequested.get()) null else IOException("ClipCascade websocket closed: $code $reason")
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                val error = IOException(
                    buildString {
                        append("ClipCascade websocket failure")
                        response?.let { append(": ${it.code} ${it.message}") }
                    },
                    t
                )
                fail(error)
            }
        })
    }

    private fun fail(error: Throwable) {
        connected.set(false)
        if (!connectSignal.isCompleted) {
            connectSignal.completeExceptionally(error)
        }
        finishClose(error)
        webSocket?.cancel()
        webSocket = null
    }

    private fun finishClose(error: Throwable?) {
        if (!closeSignal.isCompleted) {
            closeSignal.complete(error)
        }
    }

    private fun encodeOutgoingBody(data: ClipCascadeClipboardData): String {
        val key = encryptionKey
        budget.checkOutgoing(data, encrypted = key != null)
        val plainBody = json.encodeToString(data)
        budget.checkPlainBody(plainBody)
        if (key == null) return plainBody
        val encryptedPayload = encrypt(key, plainBody.toByteArray(StandardCharsets.UTF_8))
        return json.encodeToString(encryptedPayload).also(budget::checkOutgoingFrame)
    }

    private fun decodeIncomingBody(body: String): ClipCascadeClipboardData {
        budget.checkWireBody(body)
        val encryptedPayload = runCatching {
            json.decodeFromString<ClipCascadeEncryptedPayload>(body)
        }.getOrNull()

        val data: ClipCascadeClipboardData = if (encryptedPayload != null &&
            encryptedPayload.nonce.isNotBlank() &&
            encryptedPayload.ciphertext.isNotBlank() &&
            encryptedPayload.tag.isNotBlank()
        ) {
            val key = encryptionKey ?: throw IOException("ClipCascade encrypted payload received without key")
            val plaintext = decrypt(key, encryptedPayload)
            json.decodeFromString(String(plaintext, StandardCharsets.UTF_8))
        } else {
            budget.checkPlainBody(body)
            json.decodeFromString(body)
        }
        budget.checkData(data)
        return data
    }

    private fun deriveKey(password: String, username: String, salt: String, rounds: Int): ByteArray {
        // An empty salt means the server did not send one. Deriving anyway produces a key that
        // silently disagrees with every other client (see C19).
        if (salt.isBlank()) {
            throw IOException("ClipCascade user-info did not include a salt")
        }
        val saltBytes = (username + password + salt).toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), saltBytes, rounds, AES_KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } catch (e: Exception) {
            throw IOException("ClipCascade key derivation failed", e)
        }
    }

    private fun encrypt(key: ByteArray, plaintext: ByteArray): ClipCascadeEncryptedPayload {
        val nonce = ByteArray(GCM_NONCE_SIZE_BYTES)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plaintext)
        val ciphertextSize = sealed.size - GCM_TAG_SIZE_BYTES

        return ClipCascadeEncryptedPayload(
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(sealed, 0, ciphertextSize, Base64.NO_WRAP),
            tag = Base64.encodeToString(sealed, ciphertextSize, GCM_TAG_SIZE_BYTES, Base64.NO_WRAP)
        )
    }

    private fun decrypt(key: ByteArray, payload: ClipCascadeEncryptedPayload): ByteArray {
        if (payload.nonce.length > 128 || payload.tag.length > 128) {
            throw ClipCascadeMessageTooLargeException("ClipCascade nonce or tag is too large")
        }
        budget.checkBase64(payload.ciphertext, budget.maxPlainBodyBytes, "ciphertext")
        val nonce = Base64.decode(payload.nonce, Base64.DEFAULT)
        val tag = Base64.decode(payload.tag, Base64.DEFAULT)
        if (nonce.size !in 12..GCM_NONCE_SIZE_BYTES || tag.size != GCM_TAG_SIZE_BYTES) {
            throw IOException("ClipCascade invalid AES-GCM nonce or tag length")
        }
        val ciphertext = Base64.decode(payload.ciphertext, Base64.DEFAULT)
        if (ciphertext.size.toLong() > budget.maxPlainBodyBytes) {
            throw ClipCascadeMessageTooLargeException("ClipCascade ciphertext exceeds the plain body budget")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        // Keep the output private until doFinal authenticates the tag; avoid a second sealed
        // ciphertext+tag array. This also works with providers buffering all output until then.
        val plaintext = ByteArray(ciphertext.size)
        val written = cipher.update(ciphertext, 0, ciphertext.size, plaintext, 0)
        val size = written + cipher.doFinal(tag, 0, tag.size, plaintext, written)
        if (size != plaintext.size) throw IOException("ClipCascade invalid decrypted payload length")
        return plaintext
    }

    private fun webSocketUrl(): String {
        val httpUrl = baseUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid ClipCascade server url: $baseUrl")
        return httpUrl.newBuilder()
            .encodedPath(SOCKET_PATH)
            .build()
            .toString()
    }

    private fun normalizeServerUrl(serverUrl: String): String {
        return SyncServerAddress.normalize(serverUrl)
    }

    private fun encodeStompFrame(
        command: String,
        headers: LinkedHashMap<String, String> = linkedMapOf(),
        body: String = ""
    ): String {
        budget.checkOutgoingFrame(body)
        val capacity = command.length + body.length + headers.entries.sumOf { it.key.length + it.value.length + 2 } + 3
        val builder = StringBuilder(capacity)
        builder.append(command).append('\n')
        headers.forEach { (key, value) ->
            builder.append(key).append(':').append(value).append('\n')
        }
        builder.append('\n')
        builder.append(body)
        builder.append('\u0000')
        return builder.toString()
    }

    private fun parseStompFrame(raw: String): StompFrame {
        budget.checkFrame(raw)
        val lfEnd = raw.indexOf("\n\n")
        val crlfEnd = raw.indexOf("\r\n\r\n")
        val headerEnd = if (lfEnd >= 0 && (crlfEnd < 0 || lfEnd < crlfEnd)) lfEnd else crlfEnd
        if (headerEnd < 0 || headerEnd > budget.maxHeaderBytes) {
            throw IOException("ClipCascade missing or oversized STOMP header")
        }
        val separatorSize = if (headerEnd == crlfEnd) 4 else 2
        val header = raw.substring(0, headerEnd)
        if (header.utf8Size() > budget.maxHeaderBytes) throw IOException("Oversized STOMP header")
        val headerLines = header.split('\n').map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
        if (headerLines.isEmpty()) throw IOException("Missing STOMP command")
        if (headerLines.size > 65) throw IOException("Too many STOMP headers")
        val headers = linkedMapOf<String, String>()
        headerLines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator)] = line.substring(separator + 1)
        }
        headers["content-length"]?.let {
            val length = it.toLongOrNull() ?: throw IOException("Invalid STOMP content-length")
            if (length < 0 || length > budget.maxWireBodyBytes) throw IOException("Invalid STOMP content-length")
        }
        val bodyStart = headerEnd + separatorSize
        var bodyEnd = raw.length
        while (bodyEnd > bodyStart && raw[bodyEnd - 1] == '\u0000') bodyEnd--
        val body = raw.substring(bodyStart, bodyEnd)
        budget.checkWireBody(body)
        headers["content-length"]?.let {
            if (it.toLong() != body.utf8Size()) throw IOException("STOMP content-length mismatch")
        }
        return StompFrame(headerLines.first().trim(), headers, body)
    }

    private data class StompFrame(
        val command: String,
        val headers: Map<String, String>,
        val body: String
    )

    /** Bounded body read for the handshake responses; see [MAX_HTTP_BODY_BYTES]. */
    private fun Response.bodyStringAtMost(limit: Long = MAX_HTTP_BODY_BYTES): String {
        val body = this.body ?: return ""
        val declared = body.contentLength()
        if (declared > limit) {
            throw IOException("ClipCascade response of $declared bytes exceeds ${limit / 1024}KB")
        }
        val charset = body.contentType()?.charset() ?: Charsets.UTF_8
        val stream = body.byteStream()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw IOException("ClipCascade response exceeds ${limit / 1024}KB")
            }
            buffer.write(chunk, 0, read)
        }
        return String(buffer.toByteArray(), charset)
    }

    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        /**
         * Merge by cookie name.
         *
         * This used to replace the host's whole cookie list, so any response carrying a single
         * cookie dropped JSESSIONID — the session was lost and the client reconnected in a loop
         * (see G2). Expired cookies are dropped rather than stored.
         */
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            store.compute(url.host) { _, existing ->
                val merged = LinkedHashMap<String, Cookie>()
                existing?.forEach { merged[it.name] = it }
                val now = System.currentTimeMillis()
                cookies.forEach { cookie ->
                    if (cookie.expiresAt <= now) {
                        // A "delete this cookie" response; honour it instead of storing a
                        // cookie that loadForRequest would then have to filter out forever.
                        merged.remove(cookie.name)
                    } else {
                        merged[cookie.name] = cookie
                    }
                }
                merged.values.toList()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host].orEmpty()
                .filter { cookie -> cookie.expiresAt > now && cookie.matches(url) }
        }
    }
}
