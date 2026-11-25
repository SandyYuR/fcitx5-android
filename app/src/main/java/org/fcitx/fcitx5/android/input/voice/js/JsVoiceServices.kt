/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.utf8Size

/**
 * 一个插件通过 `foxy.service.register` 声明出来的服务。
 *
 * 与 Foxy 的差异只在归属：服务永远属于声明它的插件，调用方拿到的 [pluginId] 就是宿主解析位置
 * 与授权校验的依据，脚本自己报的 label 只用于展示。
 */
internal data class JsServiceDescriptor(
    val pluginId: String,
    val pluginLabel: String,
    val serviceId: String,
    val version: Int,
    val label: String,
    val description: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("pluginId", pluginId)
        put("pluginLabel", pluginLabel)
        put("serviceId", serviceId)
        // 数字而不是字符串：脚本用 item.version === 1 严格比较。
        put("version", JsonPrimitive(version))
        put("label", label)
        put("description", description)
    }
}

/**
 * 插件间文字服务（目前只有 `text.translate` 一个约定）的发现与调用。
 *
 * 发现：每个已安装脚本在自己的 QuickJS 里跑一次顶层代码，读 `__servicesJson()`。
 * 调用：为服务提供方单独起一个 QuickJS，**不**共用调用方的实例——两个插件共享堆就等于
 * 共享内存预算与全局变量，服务方一句 `var foxy` 就能改写调用方状态。
 *
 * 目录是进程内缓存：一次会话里频繁查询（设置页、start 前的解析）不该反复求值全部脚本。
 */
internal object JsVoiceServices {

    /** 服务调用预算。翻译是一次 HTTPS，比单帧音频回调宽松，但必须小于 stop 的 35s。 */
    const val CALL_TIMEOUT_MS = 20_000L

    /** 传给服务的输入上限，同时也是 QuickJS 之间复制一次的边界。 */
    private const val MAX_INPUT_BYTES = 256 * 1024

    private val lock = Mutex()

    @Volatile
    private var cached: List<JsServiceDescriptor>? = null

    /** 插件安装/删除/重载后必须调用，否则目录会一直指向已经读不到的脚本。 */
    fun invalidate() {
        cached = null
    }

    /** 读单个脚本声明的服务。脚本坏掉时返回空表，由调用方决定是「没有服务」还是报错。 */
    suspend fun discover(
        script: String,
        pluginId: String,
        pluginLabel: String,
        locale: String,
    ): List<JsServiceDescriptor> {
        val js = JsVoiceRuntime.create()
        return try {
            JsVoiceStubNatives.install(js, locale)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script)
            parseServices(js.evaluate<String>("__servicesJson()"), pluginId, pluginLabel)
        } finally {
            runCatching { js.close() }
        }
    }

    /**
     * 全部已安装插件声明的服务。
     *
     * [excludePluginId] 用来把调用方自己拿掉：Foxy 里服务方不会看到自己，否则一个插件能给自己
     * 签发「翻译服务提供方」并绕开「必须由别的插件提供」的语义。
     */
    suspend fun catalog(locale: String, excludePluginId: String? = null): List<JsServiceDescriptor> {
        val all = load(locale)
        return if (excludePluginId == null) all else all.filterNot { it.pluginId == excludePluginId }
    }

    private suspend fun load(locale: String): List<JsServiceDescriptor> {
        cached?.let { return it }
        return lock.withLock {
            cached?.let { return@withLock it }
            val out = mutableListOf<JsServiceDescriptor>()
            JsVoiceProviders.all().forEach { spec ->
                // 一个脚本的服务声明坏了不该拖垮整张目录：跳过它，别的插件照常可选。
                val found = runCatching {
                    discover(JsVoiceProviders.readScript(spec), spec.id, spec.label, locale)
                }.getOrNull().orEmpty()
                out += found
            }
            out.also { cached = it }
        }
    }

    private fun parseServices(raw: String?, pluginId: String, pluginLabel: String): List<JsServiceDescriptor> =
        runCatching { Json.parseToJsonElement(raw ?: "[]").jsonArray }.getOrNull()?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val serviceId = obj.text("id")
            if (serviceId.isBlank()) return@mapNotNull null
            val version = obj["version"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            JsServiceDescriptor(
                pluginId = pluginId,
                pluginLabel = pluginLabel,
                serviceId = serviceId,
                version = version,
                label = obj.text("label"),
                description = obj.text("description"),
            )
        }.orEmpty()

    /**
     * 在服务提供方自己的 QuickJS 里执行一次 `handle(context)`。
     *
     * 返回 JSON 文本：调用方把它当作结果对象的序列化形式，`{}` 表示空对象。
     * [http] 由宿主按提供方的域名授权构造；服务会话本身不允许再发起嵌套调用。
     */
    suspend fun invoke(
        script: String,
        locale: String,
        serviceId: String,
        version: Int,
        input: JsonElement,
        settings: Map<String, String>,
        catalogJson: String,
        http: (suspend (JsonObject) -> String)? = null,
    ): String {
        require(input.toString().utf8Size() <= MAX_INPUT_BYTES) { "Service input exceeds its limit" }
        val js: QuickJs = JsVoiceRuntime.create()
        return try {
            JsVoiceStubNatives.install(js, locale, JsHostSession.SERVICE)
            js.function("__n_servicesCatalog") { catalogJson }
            js.asyncFunction("__n_serviceCall") { _ ->
                throw IllegalStateException("Nested service calls are not supported")
            }
            if (http != null) {
                js.asyncFunction("__n_appHttp") { args -> http(parseOptions(text(args, 0))) }
            }
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script)
            val declared = parseServices(js.evaluate<String>("__servicesJson()"), "", "")
            if (declared.none { it.serviceId == serviceId && it.version == version }) {
                throw IllegalStateException("Service is not provided by this plugin: " + serviceId)
            }
            js.evaluationTimeoutMillis = CALL_TIMEOUT_MS
            val arg = buildJsonObject {
                put("serviceId", serviceId)
                put("version", JsonPrimitive(version))
                put("input", input)
                put("settings", buildJsonObject { settings.forEach { (key, value) -> put(key, value) } })
            }
            val outcome = JsVoiceInvoker.call(
                evaluate = { code -> js.evaluate<String>(code) },
                name = "service",
                argJson = arg.toString(),
                timeoutMs = CALL_TIMEOUT_MS,
            )
            outcome.error?.let { throw IllegalStateException(it) }
            val result = outcome.result
            if (result == null || result is JsonNull) "{}" else result.toString()
        } finally {
            runCatching { js.close() }
        }
    }

    private fun parseOptions(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw IllegalArgumentException("Invalid HTTP options")
        }

    private fun text(args: Array<Any?>, index: Int): String = args.getOrNull(index)?.toString().orEmpty()
}

/**
 * 一次语音会话里的服务授权视图。
 *
 * 「能调用谁」不是脚本自己说了算：候选来自服务目录，选择来自用户在设置里存的 serviceSelect 值，
 * 两者交叉出来的集合才允许调用。这样即使脚本把 `translationProvider` 写死成别人的 ID，
 * 只要用户没选过，调用就会在这里被拒。
 */
internal class JsVoiceServiceBinding(
    private val callerId: String,
    private val locale: String,
    private val http: OkHttpClient,
    private val secretOf: (pluginId: String, field: String) -> String?,
) {
    @Volatile
    private var descriptors: List<JsServiceDescriptor> = emptyList()

    @Volatile
    private var grants: Map<String, Set<String>> = emptyMap()

    /** 传给 `foxy.services.list()` 的目录，不含调用方自己。 */
    fun catalogJson(): String = JsonArray(descriptors.map { it.toJson() }).toString()

    /**
     * 按当前设置重算可调用集合。
     *
     * 空值语义与 Foxy 对齐：目录里恰好只有一个提供方时自动选它，多于一个则必须由用户
     * 在设置里明确选择——脚本因此可以「不配置也能用单插件」，又不会在多提供方时静默选错。
     */
    fun update(fields: List<JsVoiceField>, values: Map<String, String>, catalog: List<JsServiceDescriptor>) {
        descriptors = catalog.filterNot { it.pluginId == callerId }
        val next = mutableMapOf<String, Set<String>>()
        fields.filter { it.kind == JsVoiceField.KIND_SERVICE_SELECT }.forEach { field ->
            val candidates = descriptors.filter {
                it.serviceId == field.serviceId && it.version.toString() == field.serviceVersion
            }
            val chosen = values[field.key].orEmpty()
            val selected = when {
                chosen.isNotBlank() -> candidates.firstOrNull { it.pluginId == chosen }
                candidates.size == 1 -> candidates.first()
                else -> null
            } ?: return@forEach
            next[field.serviceId] = next[field.serviceId].orEmpty() + selected.pluginId
        }
        grants = next
    }

    /**
     * `foxy.services.call` 的宿主侧实现：校验授权、定位提供方插件、在它自己的 QuickJS 里执行。
     */
    suspend fun call(providerId: String, serviceId: String, versionText: String, inputJson: String): String {
        if (providerId == callerId) throw IllegalStateException("A plugin cannot call its own service")
        val version = versionText.trim().toIntOrNull()
            ?: throw IllegalArgumentException("Invalid service version")
        if (descriptors.none { it.pluginId == providerId && it.serviceId == serviceId && it.version == version }) {
            throw IllegalStateException("Service is not available: " + serviceId + " v" + version)
        }
        if (grants[serviceId]?.contains(providerId) != true) {
            throw IllegalStateException("Service provider is not selected in this plugin's settings")
        }
        val spec = JsVoiceProviders.specOf(providerId)
            ?: throw IllegalStateException("Service provider plugin is not installed")
        val input = runCatching { Json.parseToJsonElement(inputJson) }.getOrNull() ?: JsonNull
        val policy = JsVoiceNetworkPolicy(providerId, spec.hosts)
        val providerCatalog = JsonArray(
            descriptors.filterNot { it.pluginId == providerId }.map { it.toJson() }
        ).toString()
        val check: (String) -> HttpUrl = { policy.checkUrl(it) }
        return JsVoiceServices.invoke(
            script = JsVoiceProviders.readScript(spec),
            locale = locale,
            serviceId = serviceId,
            version = version,
            input = input,
            settings = JsVoiceSettingsStore.values(providerId),
            catalogJson = providerCatalog,
            http = { options ->
                JsAppHttp(check, { field -> secretOf(providerId, field) }, http).request(options)
            },
        )
    }
}
