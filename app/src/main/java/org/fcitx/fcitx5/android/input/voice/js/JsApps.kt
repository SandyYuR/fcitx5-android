/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardSearchController
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginSettingsActivity
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginsActivity
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import java.util.Locale

internal object JsAppPermissions {
    private fun prefs() = appContext.getSharedPreferences("js_app_permissions", Context.MODE_PRIVATE)
    fun granted(plugin: String, app: String): Set<String> = prefs().getStringSet("$plugin/$app", emptySet()).orEmpty().toSet()
    fun grant(plugin: String, app: String, permissions: Set<String>) {
        check(prefs().edit().putStringSet("$plugin/$app", permissions).commit())
    }
    fun revoke(plugin: String) {
        val edit = prefs().edit()
        prefs().all.keys.filter { it.startsWith("$plugin/") }.forEach(edit::remove)
        check(edit.commit())
    }
}

/** App capabilities share plugin source/settings, but never enter the voice provider registry. */
internal object JsApps {
    private const val APP_WEBSOCKET_QUEUE_CAPACITY = 8
    @Volatile private var activeSession: Session? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private fun preferred() = JsAppSelection.preferred()

    /** Called before source/permission writes; safe while the store holds its IO lock. */
    fun onPluginSourceChanging(pluginId: String) {
        activeSession?.takeIf { it.spec.id == pluginId }?.invalidateSource()
    }

    /** Permission revocation has the same live-session boundary as replacing plugin source. */
    fun onPluginAuthorizationChanging(pluginId: String) {
        activeSession?.takeIf { it.spec.id == pluginId }?.invalidateSource()
    }

    /** Reconcile only after the catalog has rebuilt, outside the store's write lock. */
    fun onPluginsChanged() {
        activeSession?.invalidateSource()
        JsAppSelection.reconcile()
    }

    fun configure(context: Context) {
        val spec = preferred()?.first ?: return
        configure(context, spec.id)
    }

    fun configure(context: Context, pluginId: String) {
        val intent = if (JsVoiceProviders.specOf(pluginId) != null) {
            JsVoicePluginSettingsActivity.intent(context, pluginId)
        } else {
            context.toast(R.string.js_app_shortcut_missing)
            JsVoicePluginsActivity.intent(context)
        }
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    fun toggle(service: FcitxInputMethodService) {
        val (spec, app) = preferred() ?: run { service.toast(R.string.js_app_none); return }
        toggleTarget(service, spec.id, app.id, R.string.js_app_password)
    }

    fun toggleExact(service: FcitxInputMethodService, pluginId: String, appId: String) {
        toggleTarget(service, pluginId, appId, R.string.js_app_shortcut_password)
    }

    private fun toggleTarget(service: FcitxInputMethodService, pluginId: String, appId: String,
        passwordMessage: Int) {
        val current = activeSession
        if (current != null && current.service === service && current.spec.id == pluginId &&
            current.app.id == appId && !current.closed && current.dialog.isShowing) {
            current.dialog.dismiss()
            return
        }
        openTarget(service, pluginId, appId, passwordMessage)
    }

    fun openExact(service: FcitxInputMethodService, pluginId: String, appId: String) {
        openTarget(service, pluginId, appId, R.string.js_app_shortcut_password)
    }

    private fun openTarget(service: FcitxInputMethodService, pluginId: String, appId: String,
        passwordMessage: Int) {
        val spec = JsVoiceProviders.specOf(pluginId)
        val app = spec?.apps?.firstOrNull { it.id == appId }
        if (spec == null || app == null) {
            service.toast(R.string.js_app_shortcut_missing)
            return
        }
        if (!prepare(service, passwordMessage)) return
        Session(service, spec, app).start()
    }

    private fun prepare(service: FcitxInputMethodService, passwordMessage: Int): Boolean {
        // No plugin editor access from password fields, even after a previous grant.
        val type = service.currentInputEditorInfo?.inputType ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        val kind = type and InputType.TYPE_MASK_CLASS
        if ((kind == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            service.toast(passwordMessage); return false
        }
        if (VoiceInputProviderManager.isActive()) VoiceInputProviderManager.stop(service)
        ClipboardSearchController.stop()
        service.jsAppDismiss?.invoke()
        return true
    }

    private class Session(
        val service: FcitxInputMethodService,
        val spec: JsVoiceProviders.Spec,
        val app: JsPluginApp,
    ) {
        val token = service.editorSession.capture()
        val connection = service.applicationInputConnection
        val scope = CoroutineScope(service.lifecycleScope.coroutineContext + SupervisorJob(service.lifecycleScope.coroutineContext[Job]))
        val locale = Locale.getDefault().toLanguageTag()
        var js: QuickJs? = null
        @Volatile var closed = false
        @Volatile private var sourceCurrent = true
        var working = false
        val editor = connection?.let { JsAppEditor(it, {
            !closed && token?.isCurrent() == true && service.applicationInputConnection === connection
        }, ::requirePermission) }
        var fields = emptyList<JsVoiceField>()
        @Volatile var settings = emptyMap<String, String>()
        var configuredSecrets = emptyList<String>()
        val dialog = JsAppDialog(service, ::event, ::close, command = ::openCommand)

        /** WebSocket 入站帧先入队：OkHttp 回调线程不能直接碰引擎，消费协程负责串行投递。 */
        private val wsEvents = Channel<WsInbound>(APP_WEBSOCKET_QUEUE_CAPACITY)

        /** 同一引擎上的 JS 调用必须串行：对话框事件与 WebSocket 回调都从这里过。 */
        private val jsLock = Mutex()
        private val hostApprovals = JsHostApprovalCoordinator(scope)

        /**
         * App 会话的 WebSocket 传输。授权沿用 endpoint policy：connect 是同步的，没法在里边弹授权框，
         * 所以插件必须先 await foxy.websocket.authorize(url)，这里只校验「已授权」。
         */
        private val ws = JsWebSocketTransport(
            http = JsAppHttp.CLIENT,
            authorizeUrl = { url ->
                requirePermission("network.request")
                val parsed = JsAppEndpointPolicy(spec.hosts, settings).checkWebSocketUrl(url)
                require(parsed.host.trimEnd('.') in JsVoiceHostPermissions.approvedHosts(spec.id)) {
                    "Host is not authorized; call foxy.websocket.authorize first"
                }
                parsed
            },
            authorizeSend = { destination ->
                requirePermission("network.request")
                val host = destination.host.trimEnd('.')
                require(JsAppEndpointPolicy(spec.hosts, settings).allows(host) &&
                    host in JsVoiceHostPermissions.approvedHosts(spec.id)) {
                    "Network permission revoked"
                }
            },
            headers = { secretField, authHeader, authPrefix, headersJson ->
                require(secretField.isBlank() || fields.any { it.key == secretField && it.isSecret }) {
                    "Undeclared credential"
                }
                JsVoiceHeaders.build(
                    secretField = secretField,
                    secret = secretField.takeIf { it.isNotBlank() }?.let { JsVoiceSettingsStore.secret(spec.id, it) },
                    authHeader = authHeader,
                    authPrefix = authPrefix,
                    headersJson = headersJson,
                )
            },
            receive = { inbound ->
                val accepted = wsEvents.trySend(inbound).isSuccess
                if (!accepted && !closed) {
                    mainHandler.post {
                        if (!closed) dialog.showError("WebSocket input queue exceeded its limit")
                    }
                }
                accepted
            },
        )

        /**
         * App 会话的流式 HTTP。授权与普通请求共用 endpoint policy 和主机批准；stream() 同步返回
         * 流 ID，所以批准在协程里做，被拒绝时以 onError 通知插件。
         */
        private val streams = JsAppStreams(
            scope = scope,
            checkUrl = { url -> JsAppEndpointPolicy(spec.hosts, settings).checkUrl(url) },
            secret = { key ->
                require(fields.any { it.key == key && it.isSecret }) { "Undeclared credential" }
                JsVoiceSettingsStore.secret(spec.id, key)
            },
            approve = { parsed ->
                requirePermission("network.request")
                approveHost(parsed.host.trimEnd('.'))
            },
            authorizeSend = { parsed ->
                ensureCurrent()
                requirePermission("network.request")
                require(parsed.host.trimEnd('.') in JsVoiceHostPermissions.approvedHosts(spec.id)) {
                    "Network permission revoked"
                }
            },
            dispatch = { event, isActive -> dispatchStream(event, isActive) },
        )

        fun start() {
            if (connection == null || token?.isCurrent() != true) return
            activeSession = this
            service.finishComposing()
            service.resetJsAppEditorState()
            service.jsAppInputConnection = JsAppInputConnection.blocked(service)
            service.jsAppDismiss = dialog::dismiss
            service.showDialog(dialog)
            if (!dialog.isShowing) { close(); return }
            scope.launch {
                try {
                    if (!JsAppPermissions.granted(spec.id, app.id).containsAll(app.permissions)) {
                        val names = app.permissions.sorted().joinToString("\n") { permissionLabel(it) }
                        if (!dialog.confirm(app.label, service.getString(R.string.js_app_permission_message, names),
                                service.getString(R.string.js_app_allow), service.getString(android.R.string.cancel))) {
                            dialog.dismiss(); return@launch
                        }
                        synchronized(this@Session) {
                            ensureCurrent()
                            JsAppPermissions.grant(spec.id, app.id, app.permissions)
                        }
                    }
                    initialize()
                    event("open", JsonObject(emptyMap()))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { dialog.showError(error.message.orEmpty()) }
            }
        }

        suspend fun initialize() {
            val engine = JsVoiceRuntime.create()
            js = engine
            withContext(Dispatchers.IO) {
                JsVoiceStubNatives.install(engine, locale, JsHostSession.APP)
                // 覆盖占位实现：App 会话现在有真实 WebSocket，授权与端点校验走 endpoint policy。
                ws.install(engine)
                installJsAppDelay(engine) { withContext(Dispatchers.Main.immediate) { ensureCurrent() } }
                engine.asyncFunction("__n_appEditor") { args -> withContext(Dispatchers.Main.immediate) {
                    requireNotNull(editor).call(args.getOrNull(0).toString(), args.getOrNull(1).toString())
                } }
                engine.function("__n_appCredentials") {
                    requirePermission("credentials.list")
                    JsonArray(configuredSecrets.map(::JsonPrimitive)).toString()
                }
                engine.function("__n_appSettings") { args ->
                    val key = args.getOrNull(0).toString()
                    val value = args.getOrNull(1)?.toString().orEmpty()
                    writeSetting(key, value)
                    null
                }
                engine.asyncFunction("__n_appHttp") { args ->
                    requirePermission("network.request")
                    val options = Json.parseToJsonElement(args[0].toString()).jsonObject
                    val host = JsAppEndpointPolicy(spec.hosts, settings).checkUrl(options.text("url")).host.trimEnd('.')
                    check(approveHost(host)) { "Network permission denied" }
                    JsAppHttp(checkUrl = { url ->
                        checkHttpUrl(url, host)
                    }, secret = { key ->
                        require(fields.any { it.key == key && it.isSecret }) { "Undeclared credential" }
                        JsVoiceSettingsStore.secret(spec.id, key)
                    }).request(options)
                }
                engine.function("__n_appHttpStream") { args ->
                    requirePermission("network.request")
                    streams.start(Json.parseToJsonElement(args[0].toString()).jsonObject)
                }
                engine.function("__n_appHttpCancelStream") { args ->
                    streams.cancel(args.getOrNull(0).toString())
                    null
                }
                // foxy.websocket.authorize：App 的 WebSocket 连接是同步的，插件必须在 connect 前先拿到
                // 主机授权。返回 true/false 让插件自己决定要不要继续，而不是在这里抛错。
                engine.asyncFunction("__n_wsAuthorize") { args ->
                    requirePermission("network.request")
                    val host = JsVoiceHelpers.authorizeHost(args.getOrNull(0).toString())
                        ?: throw IllegalArgumentException("Invalid WebSocket host")
                    require(JsAppEndpointPolicy(spec.hosts, settings).allows(host)) {
                        "Endpoint must be declared or configured in plugin settings"
                    }
                    approveHost(host)
                }
                engine.asyncFunction("__n_appUi") { args -> withContext(Dispatchers.Main.immediate) {
                    ensureCurrent()
                    val method = args[0].toString()
                    val value = Json.parseToJsonElement(args[1].toString()).jsonObject
                    when (method) {
                        "update" -> dialog.render(value)
                        "setValue" -> dialog.setValue(value.text("id"), value.text("value"))
                        "setEnabled" -> dialog.setEnabled(value.text("id"), value["enabled"]?.jsonPrimitive?.booleanOrNull == true)
                        "setLoading" -> dialog.setBusy(value["loading"]?.jsonPrimitive?.booleanOrNull == true)
                        "setError" -> dialog.showError(value.text("message"))
                        "close" -> dialog.dismiss()
                        else -> error("Unsupported UI operation")
                    }
                    null
                } }
                JsVoiceRuntime.initialize(engine, JsVoicePrelude.SOURCE + "\n" + JsVoiceProviders.readScript(spec))
                val actual = requireNotNull(JsVoicePluginMeta.parse(engine.evaluate("__meta()")))
                require(actual.id == spec.id.removePrefix("js:") && actual.hosts.toSet() == spec.hosts &&
                    actual.apps.any { it.id == app.id && it.permissions == app.permissions }) { "Plugin declaration changed; import it again" }
                fields = JsVoiceFields.parse(engine.evaluate("__describe(" + JsonPrimitive(locale) + ")"))
                settings = JsVoiceFields.currentValues(fields, JsVoiceSettingsStore.values(spec.id))
                configuredSecrets = fields.filter { it.isSecret && JsVoiceSettingsStore.hasSecret(spec.id, it.key) }.map { it.key }
            }
            scope.launch { consumeWebSocket() }
        }

        /** Serialize individual QuickJS evaluations while allowing pending invocations to interleave. */
        private suspend fun invoke(
            engine: QuickJs,
            name: String,
            context: JsonObject,
            active: () -> Boolean = { true },
        ): JsVoiceInvoker.Outcome = JsVoiceInvoker.call(
            evaluate = { code ->
                jsLock.withLock {
                    if (js !== engine || closed || !sourceCurrent || !active()) {
                        throw CancellationException("App callback is no longer active")
                    }
                    withContext(Dispatchers.IO) { engine.evaluate<String>(code) }
                }
            },
            name = name,
            argJson = context.toString(),
            timeoutMs = 180_000,
            cleanupEvaluate = { code ->
                jsLock.withLock {
                    if (js !== engine || closed) null
                    else withContext(Dispatchers.IO) { engine.evaluate<String>(code) }
                }
            },
        )

        /**
         * 把入站帧翻成 foxy.websocket.onMessage/onBinary/onError/onClose 的调用。
         * 与语音会话的 plugin.onMessage 单入口不同：App 侧按种类分派，且没有处理器时静默丢弃。
         */
        private suspend fun consumeWebSocket() {
            for (inbound in wsEvents) {
                val engine = js ?: continue
                val context = buildJsonObject {
                    when (inbound) {
                        is WsInbound.Text -> { put("kind", "message"); put("payload", inbound.text) }
                        is WsInbound.Binary -> { put("kind", "binary"); put("payload", inbound.base64) }
                        is WsInbound.Failed -> { put("kind", "error"); put("payload", inbound.message) }
                        // foxy 的 onClose 第二个参数是 wasClean，不是关闭码。
                        is WsInbound.Closed -> { put("kind", "close"); put("payload", inbound.code == 1000) }
                    }
                    put("socket", inbound.socketId)
                }
                JsAppCallbackDispatch.run {
                    invoke(engine, "ws", context)
                }
            }
        }

        /**
         * 把流式事件翻成 foxy.http.onChunk/onEnd/onError 的调用。每个事件都 await 插件处理完
         * 才返回，读循环因此不会跑在插件前面；没有处理器时静默丢弃。
         */
        private suspend fun dispatchStream(event: StreamEvent, active: () -> Boolean) {
            val engine = js ?: return
            val context = buildJsonObject {
                when (event) {
                    is StreamEvent.Chunk -> {
                        put("kind", "chunk"); put("event", event.event); put("data", event.data)
                    }
                    is StreamEvent.End -> put("kind", "end")
                    is StreamEvent.Failed -> { put("kind", "error"); put("payload", event.message) }
                }
                put("id", event.streamId)
            }
            JsAppCallbackDispatch.run {
                invoke(engine, "stream", context, active)
            }
        }

        fun event(name: String, values: JsonObject) {
            if (closed || !sourceCurrent) return
            if (working) {
                if (name in setOf("close", "cancel")) dialog.dismiss()
                return
            }
            val engine = js ?: return
            working = true
            dialog.setBusy(true)
            scope.launch {
                try {
                    ensureCurrent()
                    val context = buildJsonObject {
                        settings.forEach { (key, value) -> put(key, value) }
                        put("event", name); put("values", values); put("appId", app.id); put("command", app.command)
                        put("settings", buildJsonObject { settings.forEach { (key, value) -> put(key, value) } })
                        put("configuredSecrets", JsonArray(if ("credentials.list" in app.permissions) configuredSecrets.map(::JsonPrimitive) else emptyList()))
                    }
                    val outcome = invoke(engine, "app", context)
                    ensureCurrent()
                    outcome.error?.let { error(it) }
                    val result = outcome.result as? JsonObject ?: error("Plugin returned no dialog")
                    if (result["close"]?.jsonPrimitive?.booleanOrNull == true) dialog.dismiss()
                    else dialog.render(result["dialog"] as? JsonObject ?: result)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (!closed) dialog.showError(error.message.orEmpty()) }
                finally { working = false; if (!closed) dialog.setBusy(false) }
            }
        }

        /**
         * 同一插件的主机授权对所有 App 共享：用户在这里点过允许，别的 App 的同一主机不再重复弹窗。
         * 用户拒绝时返回 false，由调用方决定是抛错还是让插件降级。
         */
        private suspend fun approveHost(host: String): Boolean {
            return hostApprovals.approve(
                host = host,
                isApproved = { host in JsVoiceHostPermissions.approvedHosts(spec.id) },
                prompt = {
                    withContext(Dispatchers.Main.immediate) {
                        ensureCurrent()
                        dialog.confirm(
                            service.getString(R.string.js_voice_network_title),
                            service.getString(R.string.js_app_endpoint_message, host),
                            service.getString(R.string.js_app_allow),
                            service.getString(android.R.string.cancel),
                        )
                    }
                },
                persist = {
                    synchronized(this@Session) {
                        ensureCurrent()
                        JsVoiceHostPermissions.approve(
                            spec.id,
                            JsVoiceHostPermissions.approvedHosts(spec.id) + host,
                        )
                    }
                },
            )
        }

        /**
         * 布局里的 `{"type":"app","command":"js.<id>"}`：解析成唯一匹配的 App 后切过去。
         * 解析失败（未注册或同 ID 出现在多个插件里）就提示改用编辑按钮里的精确绑定。
         */
        private fun openCommand(command: String) {
            val target = JsAppCommand.resolve(command, JsAppSelection.available().map { it.first.id to it.second })
            if (target == null) { service.toast(R.string.js_app_shortcut_missing); return }
            dialog.dismiss()
            openTarget(service, target.pluginId, target.appId, R.string.js_app_shortcut_password)
        }

        fun requirePermission(permission: String) {
            check(!closed && sourceCurrent && permission in app.permissions && permission in JsAppPermissions.granted(spec.id, app.id)) { "Permission denied: $permission" }
        }
        fun ensureCurrent() {
            check(!closed && sourceCurrent && token?.isCurrent() == true && service.applicationInputConnection === connection) { "Editor session ended" }
        }
        private fun writeSetting(key: String, value: String) = synchronized(this) {
            // Source invalidation shares this lock: an old callback cannot persist after replacement.
            ensureCurrent()
            require(JsVoiceFields.writable(fields, key)) { "Undeclared setting: $key" }
            JsVoiceFields.requireWritableValue(settings, key, value)
            JsVoiceSettingsStore.setValue(spec.id, key, value)
            settings = settings + (key to value)
        }
        private fun checkHttpUrl(url: String, approvedHost: String): okhttp3.HttpUrl {
            requirePermission("network.request")
            // Recompute eligibility after approval and credentials: another callback can change settings.
            val parsed = JsAppEndpointPolicy(spec.hosts, settings).checkUrl(url)
            require(parsed.host.trimEnd('.') == approvedHost &&
                approvedHost in JsVoiceHostPermissions.approvedHosts(spec.id)) { "Network permission revoked" }
            return parsed
        }
        fun invalidateSource() {
            synchronized(this) { sourceCurrent = false }
            scope.cancel()
            // Keep editor ownership intact until close restores any temporary composition.
            mainHandler.post {
                dialog.dismiss()
                close()
            }
        }
        fun close() {
            if (closed) return
            editor?.clearPreview()
            closed = true
            if (activeSession === this) {
                activeSession = null
                service.jsAppInputConnection = null
                service.jsAppDismiss = null
                service.resetJsAppEditorState()
            }
            ws.closeAll()
            wsEvents.close()
            streams.closeAll()
            scope.cancel()
            val engine = js
            js = null
            service.lifecycleScope.launch(Dispatchers.IO + NonCancellable) { runCatching { engine?.close() } }
        }
        fun permissionLabel(permission: String): String = service.getString(when (permission) {
            "editor.read_selection" -> R.string.js_app_read_selection
            "editor.read_text" -> R.string.js_app_read_text
            "editor.composition" -> R.string.js_app_composition
            "editor.commit" -> R.string.js_app_commit
            "network.request" -> R.string.js_app_network
            else -> R.string.js_app_credentials
        })
    }
}
