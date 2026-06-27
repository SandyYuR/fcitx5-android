/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor

import android.content.res.Configuration
import android.os.Looper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ImeWebEditorBridgeServer {
    data class Session(
        val host: String,
        val port: Int
    ) {
        val editorUrl: String get() = "http://$host:$port/editor"
        val apiBaseUrl: String get() = "http://$host:$port"
    }

    private const val UPSTREAM_EDITOR_BASE = "https://fxliang.github.io/f5a-see-me/"
    private const val UPSTREAM_EDITOR_INDEX = "${UPSTREAM_EDITOR_BASE}index.html"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private val ioExecutor = Executors.newCachedThreadPool()
    private val autoStopScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private const val AUTO_STOP_HOURS = 3L
    @Volatile
    private var autoStopFuture: ScheduledFuture<*>? = null
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }
    private val httpClient = OkHttpClient.Builder().build()
    private val running = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var session: Session? = null

    @Volatile
    private var cachedEditorHtml: String? = null
    @Volatile
    private var cachedAt: Long = 0L

    @Synchronized
    fun start(): Session {
        session?.let { if (running.get()) return it }
        val (socket, port) = bindServerSocket()
        val host = detectBestHostAddress() ?: "127.0.0.1"
        serverSocket = socket
        session = Session(host = host, port = port)
        running.set(true)
        ioExecutor.execute { acceptLoop(socket) }
        scheduleAutoStop()
        return session!!
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        session = null
        cancelAutoStop()
    }

    fun currentSession(): Session? = session?.takeIf { running.get() }

    private fun scheduleAutoStop() {
        cancelAutoStop()
        autoStopFuture = autoStopScheduler.schedule({
            if (running.get()) stop()
        }, AUTO_STOP_HOURS, TimeUnit.HOURS)
    }

    private fun cancelAutoStop() {
        autoStopFuture?.cancel(false)
        autoStopFuture = null
    }

    private fun bindServerSocket(): Pair<ServerSocket, Int> {
        val candidatePorts = sequenceOf(18888, 18889, 18890) + (18900..18950).asSequence()
        candidatePorts.forEach { port ->
            runCatching {
                val socket = ServerSocket(port)
                socket.reuseAddress = true
                return socket to port
            }
        }
        throw IllegalStateException("Cannot bind local web-editor bridge server port")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            ioExecutor.execute {
                runCatching { handleClient(client) }
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = parseRequest(input) ?: return writeResponse(output, 400, "Bad Request", "text/plain", "bad request")
        val path = request.path.substringBefore('?')
        val query = parseQuery(request.path.substringAfter('?', ""))
        if (request.method == "OPTIONS") {
            return writeResponse(output, 204, "No Content", "text/plain", "", extraHeaders = corsHeaders())
        }
        try {
            when {
                request.method == "GET" && (path == "/" || path == "/editor") -> {
                    val html = getEditorBootstrapHtml()
                    writeResponse(output, 200, "OK", "text/html; charset=utf-8", html, extraHeaders = corsHeaders())
                }
                request.method == "GET" && path == "/api/v1/meta" -> {
                    val payload = buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("server", JsonPrimitive("f5a-ime-bridge"))
                        put("apiVersion", JsonPrimitive(1))
                        put("profile", JsonPrimitive(currentLayoutProfile()))
                        put("activeTheme", JsonPrimitive(ThemeManager.activeTheme.name))
                    }
                    writeJson(output, payload)
                }
                request.method == "GET" && path == "/api/v1/layout" -> {
                    val profile = UserConfigFiles.normalizeTextKeyboardLayoutProfile(query["profile"] ?: "")
                        ?: currentLayoutProfile()
                    val file = UserConfigFiles.textKeyboardLayoutJson(profile)
                        ?: throw IllegalStateException("Cannot resolve layout file")
                    val jsonText = if (file.exists()) file.readText() else "{}\n"
                    writeJson(output, buildJsonObject {
                        put("profile", JsonPrimitive(profile))
                        put("json", JsonPrimitive(jsonText))
                    })
                }
                request.method == "GET" && path == "/api/v1/layout/profiles" -> {
                    val current = currentLayoutProfile()
                    val profiles = UserConfigFiles.listTextKeyboardLayoutProfiles()
                    val profileArray = kotlinx.serialization.json.JsonArray(
                        profiles.map { JsonPrimitive(it) }
                    )
                    writeJson(output, buildJsonObject {
                        put("currentProfile", JsonPrimitive(current))
                        put("profiles", profileArray)
                    })
                }
                request.method == "PUT" && path == "/api/v1/layout" -> {
                    val body = parseBodyJson(request)
                    val profile = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
                        body["profile"]?.jsonPrimitive?.contentOrNull ?: query["profile"].orEmpty()
                    ) ?: currentLayoutProfile()
                    val rawJson = body["json"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("layout json is required")
                    val normalized = LayoutQrTransferCodec.normalizeLayoutJson(rawJson)
                    val file = UserConfigFiles.textKeyboardLayoutJson(profile)
                        ?: throw IllegalStateException("Cannot resolve layout file")
                    file.parentFile?.mkdirs()
                    file.writeText(normalized)
                    ConfigProviders.ensureWatching()
                    writeJson(output, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("profile", JsonPrimitive(profile))
                    })
                }
                request.method == "GET" && path == "/api/v1/popup" -> {
                    val file = UserConfigFiles.popupPresetJson() ?: throw IllegalStateException("Cannot resolve popup file")
                    val jsonText = if (file.exists()) file.readText() else "{}\n"
                    writeJson(output, buildJsonObject {
                        put("json", JsonPrimitive(jsonText))
                    })
                }
                request.method == "PUT" && path == "/api/v1/popup" -> {
                    val body = parseBodyJson(request)
                    val rawJson = body["json"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("popup json is required")
                    val element = json.parseToJsonElement(rawJson).jsonObject
                    val file = UserConfigFiles.popupPresetJson() ?: throw IllegalStateException("Cannot resolve popup file")
                    file.parentFile?.mkdirs()
                    file.writeText(json.encodeToString(element) + "\n")
                    ConfigProviders.ensureWatching()
                    writeJson(output, buildJsonObject { put("ok", JsonPrimitive(true)) })
                }
                request.method == "GET" && path == "/api/v1/theme" -> {
                    val allThemes = ThemeManager.getAllThemes()
                    val themesElement = kotlinx.serialization.json.JsonArray(
                        allThemes
                            .filterIsInstance<Theme.Custom>()
                            .map { themeToWebThemeElement(it) }
                    )
                    val active = ThemeManager.activeTheme
                    val activeThemeElement = themeToWebThemeElement(active)
                    writeJson(output, buildJsonObject {
                        put("theme", activeThemeElement)
                        put("themes", themesElement)
                        put("activeThemeName", JsonPrimitive(active.name))
                    })
                }
                request.method == "PUT" && path == "/api/v1/theme" -> {
                    val body = parseBodyJson(request)
                    val themeNode = body["theme"] ?: throw IllegalArgumentException("theme is required")
                    val themeRaw = when {
                        themeNode is JsonPrimitive && themeNode.isString -> themeNode.content
                        else -> themeNode.toString()
                    }
                    val theme = json.decodeFromString(CustomThemeSerializer, themeRaw)
                    val save = {
                        ThemeManager.saveTheme(theme)
                        ThemeManager.setNormalModeTheme(theme)
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        save()
                    } else {
                        val done = java.util.concurrent.CountDownLatch(1)
                        var failure: Throwable? = null
                        android.os.Handler(Looper.getMainLooper()).post {
                            runCatching { save() }.onFailure { failure = it }
                            done.countDown()
                        }
                        done.await()
                        failure?.let { throw it }
                    }
                    writeJson(output, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("name", JsonPrimitive(theme.name))
                    })
                }
                request.method == "GET" && path == "/api/v1/theme/package" -> {
                    val name = query["name"].orEmpty()
                    val target = ThemeManager.getTheme(name).takeIf { name.isNotBlank() }
                        ?: ThemeManager.activeTheme
                    val custom = target as? Theme.Custom
                        ?: throw IllegalArgumentException("custom theme is required")
                    val bytes = ByteArrayOutputStream().use { stream ->
                        ThemeFilesManager.exportTheme(custom, stream).getOrThrow()
                        stream.toByteArray()
                    }
                    writeBinaryResponse(
                        output = output,
                        statusCode = 200,
                        statusText = "OK",
                        contentType = "application/zip",
                        bytes = bytes,
                        extraHeaders = corsHeaders()
                    )
                }
                request.method == "PUT" && path == "/api/v1/theme/package" -> {
                    val bytes = request.body ?: throw IllegalArgumentException("theme package is required")
                    val importedName = query["name"]?.takeIf { it.isNotBlank() }
                    val (_, importedTheme, _) = ThemeFilesManager.importTheme(bytes.inputStream(), importedName).getOrThrow()
                    val save = {
                        ThemeManager.refreshThemes()
                        val theme = ThemeManager.getTheme(importedTheme.name) ?: importedTheme
                        ThemeManager.setNormalModeTheme(theme)
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        save()
                    } else {
                        val done = java.util.concurrent.CountDownLatch(1)
                        var failure: Throwable? = null
                        android.os.Handler(Looper.getMainLooper()).post {
                            runCatching { save() }.onFailure { failure = it }
                            done.countDown()
                        }
                        done.await()
                        failure?.let { throw it }
                    }
                    writeJson(output, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("name", JsonPrimitive(importedTheme.name))
                    })
                }
                request.method == "GET" && path == "/api/v1/theme/prefs" -> {
                    val prefs = ThemeManager.prefs
                    val keyboardPrefs = AppPrefs.getInstance().keyboard
                    val resources = org.fcitx.fcitx5.android.utils.appContext.resources
                    val metrics = resources.displayMetrics
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val keyboardHeightPercent = if (isLandscape) {
                        keyboardPrefs.keyboardHeightPercentLandscape.getValue()
                    } else {
                        keyboardPrefs.keyboardHeightPercent.getValue()
                    }
                    val keyboardSidePadding = if (isLandscape) {
                        keyboardPrefs.keyboardSidePaddingLandscape.getValue()
                    } else {
                        keyboardPrefs.keyboardSidePadding.getValue()
                    }
                    val keyboardBottomPadding = if (isLandscape) {
                        keyboardPrefs.keyboardBottomPaddingLandscape.getValue()
                    } else {
                        keyboardPrefs.keyboardBottomPadding.getValue()
                    }
                    val keyboardHeightPx = metrics.heightPixels * keyboardHeightPercent / 100
                    val density = metrics.density
                    writeJson(output, buildJsonObject {
                        put("borderEnabled", JsonPrimitive(prefs.keyBorder.getValue()))
                        put("borderOutline", JsonPrimitive(prefs.keyBorderStroke.getValue()))
                        put("gboardStyle", JsonPrimitive(prefs.specialKeyOvalShape.getValue()))
                        put("keyHGap", JsonPrimitive(prefs.keyHorizontalMargin.getValue()))
                        put("keyVGap", JsonPrimitive(prefs.keyVerticalMargin.getValue()))
                        put("keyRadius", JsonPrimitive(prefs.keyRadius.getValue()))
                        put("punctPos", JsonPrimitive(prefs.punctuationPosition.getValue().toWebValue()))
                        put("screenWidthPx", JsonPrimitive(metrics.widthPixels))
                        put("screenHeightPx", JsonPrimitive(metrics.heightPixels))
                        put("density", JsonPrimitive(density))
                        put("densityDpi", JsonPrimitive(metrics.densityDpi))
                        put("orientation", JsonPrimitive(if (isLandscape) "landscape" else "portrait"))
                        put("keyboardHeightPercent", JsonPrimitive(keyboardHeightPercent))
                        put("keyboardHeightPx", JsonPrimitive(keyboardHeightPx))
                        put("keyboardSidePaddingDp", JsonPrimitive(keyboardSidePadding))
                        put("keyboardSidePaddingPx", JsonPrimitive((keyboardSidePadding * density).toInt()))
                        put("keyboardBottomPaddingDp", JsonPrimitive(keyboardBottomPadding))
                        put("keyboardBottomPaddingPx", JsonPrimitive((keyboardBottomPadding * density).toInt()))
                        // Kawaii bar height (40dp) sits above the keyboard view in the IME layout.
                        // Web preview needs this to offset key background positions correctly.
                        put("kawaiiBarHeightPx", JsonPrimitive((40 * density).toInt()))
                    })
                }
                request.method == "PUT" && path == "/api/v1/theme/prefs" -> {
                    val body = parseBodyJson(request)
                    val prefs = ThemeManager.prefs
                    body["borderEnabled"]?.jsonPrimitive?.booleanOrNull?.let { prefs.keyBorder.setValue(it) }
                    body["borderOutline"]?.jsonPrimitive?.booleanOrNull?.let { prefs.keyBorderStroke.setValue(it) }
                    body["gboardStyle"]?.jsonPrimitive?.booleanOrNull?.let { prefs.specialKeyOvalShape.setValue(it) }
                    body["keyHGap"]?.jsonPrimitive?.intOrNull?.let { prefs.keyHorizontalMargin.setValue(it) }
                    body["keyVGap"]?.jsonPrimitive?.intOrNull?.let { prefs.keyVerticalMargin.setValue(it) }
                    body["keyRadius"]?.jsonPrimitive?.intOrNull?.let { prefs.keyRadius.setValue(it) }
                    body["punctPos"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                        punctuationPositionFromWebValue(raw)?.let { prefs.punctuationPosition.setValue(it) }
                    }
                    writeJson(output, buildJsonObject { put("ok", JsonPrimitive(true)) })
                }
                request.method == "DELETE" && path == "/api/v1/theme" -> {
                    val name = query["name"]?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("name query parameter is required")
                    val delete = {
                        ThemeManager.deleteTheme(name)
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        delete()
                    } else {
                        val done = java.util.concurrent.CountDownLatch(1)
                        var failure: Throwable? = null
                        android.os.Handler(Looper.getMainLooper()).post {
                            runCatching { delete() }.onFailure { failure = it }
                            done.countDown()
                        }
                        done.await()
                        failure?.let { throw it }
                    }
                    writeJson(output, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("name", JsonPrimitive(name))
                    })
                }
                request.method == "DELETE" && path == "/api/v1/theme/package" -> {
                    val name = query["name"]?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("name query parameter is required")
                    val delete = {
                        ThemeManager.deleteTheme(name)
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        delete()
                    } else {
                        val done = java.util.concurrent.CountDownLatch(1)
                        var failure: Throwable? = null
                        android.os.Handler(Looper.getMainLooper()).post {
                            runCatching { delete() }.onFailure { failure = it }
                            done.countDown()
                        }
                        done.await()
                        failure?.let { throw it }
                    }
                    writeJson(output, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("name", JsonPrimitive(name))
                    })
                }
                request.method == "GET" && !path.startsWith("/api/") -> {
                    proxyUpstreamAsset(request.path, output)
                }
                else -> {
                    writeResponse(output, 404, "Not Found", "text/plain; charset=utf-8", "not found", extraHeaders = corsHeaders())
                }
            }
        } catch (t: Throwable) {
            writeResponse(
                output = output,
                statusCode = 500,
                statusText = "Internal Server Error",
                contentType = "application/json; charset=utf-8",
                body = """{"ok":false,"error":"${escapeForJson(t.message ?: t.javaClass.simpleName)}"}""",
                extraHeaders = corsHeaders()
            )
        }
    }

    private fun escapeForJson(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun themeToWebThemeElement(theme: Theme): JsonObject {
       val safeDir = theme.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
       fun ensureDir(path: String) = if (path.startsWith("$safeDir/")) path else "$safeDir/$path"
       val background = when (theme) {
           is Theme.Custom -> theme.backgroundImage?.let {
               buildJsonObject {
                   put("croppedFilePath", JsonPrimitive(ensureDir(it.croppedFilePath)))
                   put("srcFilePath", JsonPrimitive(ensureDir(it.srcFilePath)))
                   put("brightness", JsonPrimitive(it.brightness))
                   put("cropRect", JsonNull)
                   put("cropRotation", JsonPrimitive(it.cropRotation))
                   put("blurRadius", JsonPrimitive(it.blurRadius))
               }
           }
           else -> null
       }
        return buildJsonObject {
            put("name", JsonPrimitive(theme.name))
            put("isDark", JsonPrimitive(theme.isDark))
            put("builtin", JsonPrimitive(theme !is Theme.Custom))
            put("backgroundColor", JsonPrimitive(theme.backgroundColor))
            put("barColor", JsonPrimitive(theme.barColor))
            put("keyboardColor", JsonPrimitive(theme.keyboardColor))
            put("keyBackgroundColor", JsonPrimitive(theme.keyBackgroundColor))
            put("keyTextColor", JsonPrimitive(theme.keyTextColor))
            put("candidateTextColor", JsonPrimitive(theme.candidateTextColor))
            put("candidateLabelColor", JsonPrimitive(theme.candidateLabelColor))
            put("candidateCommentColor", JsonPrimitive(theme.candidateCommentColor))
            put("altKeyBackgroundColor", JsonPrimitive(theme.altKeyBackgroundColor))
            put("altKeyTextColor", JsonPrimitive(theme.altKeyTextColor))
            put("accentKeyBackgroundColor", JsonPrimitive(theme.accentKeyBackgroundColor))
            put("accentKeyTextColor", JsonPrimitive(theme.accentKeyTextColor))
            put("keyPressHighlightColor", JsonPrimitive(theme.keyPressHighlightColor))
            put("keyShadowColor", JsonPrimitive(theme.keyShadowColor))
            put("popupBackgroundColor", JsonPrimitive(theme.popupBackgroundColor))
            put("popupTextColor", JsonPrimitive(theme.popupTextColor))
            put("spaceBarColor", JsonPrimitive(theme.spaceBarColor))
            put("dividerColor", JsonPrimitive(theme.dividerColor))
            put("clipboardEntryColor", JsonPrimitive(theme.clipboardEntryColor))
            put("genericActiveBackgroundColor", JsonPrimitive(theme.genericActiveBackgroundColor))
            put("genericActiveForegroundColor", JsonPrimitive(theme.genericActiveForegroundColor))
            if (theme.waterRippleColor != null) {
                put("waterRippleColor", JsonPrimitive(theme.waterRippleColor!!))
            }
            if (background != null) {
                put("backgroundImage", background)
            }
        }
    }

    private fun currentLayoutProfile(): String {
        return UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
    }

    private fun parseBodyJson(request: HttpRequest): JsonObject {
        val raw = request.body?.toString(Charsets.UTF_8).orEmpty().trim()
        if (raw.isEmpty()) return buildJsonObject { }
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun writeJson(output: BufferedOutputStream, payload: JsonElement) {
        writeResponse(
            output = output,
            statusCode = 200,
            statusText = "OK",
            contentType = "application/json; charset=utf-8",
            body = json.encodeToString(payload),
            extraHeaders = corsHeaders()
        )
    }

    private fun getEditorBootstrapHtml(): String {
        val now = System.currentTimeMillis()
        cachedEditorHtml?.takeIf { now - cachedAt < CACHE_TTL_MS }?.let { return it }
        val upstream = fetchUpstreamEditorHtml()
        val rewritten = rewriteEditorHtml(upstream)
        cachedEditorHtml = rewritten
        cachedAt = now
        return rewritten
    }

    private fun fetchUpstreamEditorHtml(): String {
        val req = Request.Builder().url(UPSTREAM_EDITOR_INDEX).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Failed to fetch web-editor index: ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("Empty web-editor index")
        }
    }

    private fun proxyUpstreamAsset(pathWithQuery: String, output: BufferedOutputStream) {
        val normalizedPath = pathWithQuery.trim().let {
            if (it.isEmpty() || it == "/") "index.html" else it.removePrefix("/")
        }
        val url = "$UPSTREAM_EDITOR_BASE$normalizedPath"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                writeResponse(
                    output = output,
                    statusCode = resp.code,
                    statusText = resp.message.ifBlank { "Upstream Error" },
                    contentType = "text/plain; charset=utf-8",
                    body = "upstream ${resp.code}",
                    extraHeaders = corsHeaders()
                )
                return
            }
            val body = resp.body?.bytes() ?: ByteArray(0)
            val contentType = resp.header("Content-Type")
                ?.takeIf { it.isNotBlank() }
                ?: guessContentType(pathWithQuery)
            writeBinaryResponse(
                output = output,
                statusCode = 200,
                statusText = "OK",
                contentType = contentType,
                bytes = body,
                extraHeaders = corsHeaders()
            )
        }
    }

    private fun rewriteEditorHtml(rawHtml: String): String {
        val rewrittenHtml = rawHtml
            .replace(Regex("""\b(src|href)=["']\./([^"']+)["']""")) {
                """${it.groupValues[1]}="/${it.groupValues[2]}""""
            }
        val injection = """<script>window.__F5A_IME_API_BASE__ = window.location.origin;</script>"""
        return if (rawHtml.contains("</head>", ignoreCase = true)) {
            rewrittenHtml.replace("</head>", "$injection\n</head>", ignoreCase = true)
        } else {
            "$injection\n$rewrittenHtml"
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&')
            .mapNotNull {
                val key = it.substringBefore('=', "").trim()
                if (key.isEmpty()) return@mapNotNull null
                val value = it.substringAfter('=', "")
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }.toMap()
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: ByteArray?
    )

    private fun parseRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
            if (headerBytes.size() > 64 * 1024) return null
        }
        val headerText = headerBytes.toByteArray().toString(Charsets.UTF_8)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val path = requestLine[1]
        val headers = lines.drop(1).mapNotNull {
            val idx = it.indexOf(':')
            if (idx <= 0) null else it.substring(0, idx).trim().lowercase() to it.substring(idx + 1).trim()
        }.toMap()
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(bytes, offset, contentLength - offset)
                if (read <= 0) break
                offset += read
            }
            bytes.copyOf(offset)
        } else null
        return HttpRequest(method = method, path = path, body = body)
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeBinaryResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        bytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun guessContentType(pathWithQuery: String): String {
        val path = pathWithQuery.substringBefore('?').lowercase()
        return when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".js") -> "application/javascript; charset=utf-8"
            path.endsWith(".json") -> "application/json; charset=utf-8"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET,PUT,DELETE,OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type,Accept"
    )

    private fun detectBestHostAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull { it.startsWith("10.") || it.startsWith("172.") || it.startsWith("192.168.") }
            ?.let { return it }
        return interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull()
    }

    private fun ThemePrefs.PunctuationPosition.toWebValue(): String = when (this) {
        ThemePrefs.PunctuationPosition.Bottom -> "bottom"
        ThemePrefs.PunctuationPosition.TopRight -> "top-right"
        ThemePrefs.PunctuationPosition.TopCenter -> "top-center"
        ThemePrefs.PunctuationPosition.None -> "none"
    }

    private fun punctuationPositionFromWebValue(value: String): ThemePrefs.PunctuationPosition? = when (value) {
        "bottom" -> ThemePrefs.PunctuationPosition.Bottom
        "top-right" -> ThemePrefs.PunctuationPosition.TopRight
        "top-center" -> ThemePrefs.PunctuationPosition.TopCenter
        "none" -> ThemePrefs.PunctuationPosition.None
        else -> null
    }
}
