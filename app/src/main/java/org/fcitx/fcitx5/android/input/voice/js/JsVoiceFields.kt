/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.utf8Size

/**
 * 插件 `configure(ui, locale)` 声明出来的一项设置。
 *
 * 这是**纯数据**，不依赖 Android：设置界面按它渲染控件，会话启动时按它合并用户已保存的值
 * 拼成 `start(context)` 的入参。两者共用同一份声明，才不会出现「设置页改了但引擎没生效」。
 */
internal data class JsVoiceField(
    /** text / secret / select / switch / number */
    val kind: String,
    val key: String,
    val label: String,
    val description: String,
    val defaultValue: String,
    val options: List<JsVoiceFieldOption> = emptyList(),
    val allowCustom: Boolean = false,
    val visibleWhen: JsonObject? = null,
    /** Default chosen by another ordinary setting: {key: "authMode", values: {apiKey: "..."}}. */
    val defaultWhen: JsonObject? = null,
) {
    val isSecret: Boolean get() = kind == KIND_SECRET

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_SECRET = "secret"
        const val KIND_SELECT = "select"
        const val KIND_SWITCH = "switch"
        const val KIND_NUMBER = "number"
    }
}

internal data class JsVoiceFieldOption(val label: String, val value: String)

internal object JsVoiceFields {
    const val MAX_WRITABLE_VALUE_BYTES = 64 * 1024
    const val MAX_WRITABLE_VALUES_BYTES = 256 * 1024

    /**
     * 解析 `__describe()` 返回的声明数组。
     *
     * 容错优先：插件是第三方代码，出现不认识的 kind、缺字段、非数组时宁可跳过该项，
     * 也不让整页设置崩掉——设置页打不开的插件等于不可用。
     */
    fun parse(json: String): List<JsVoiceField> {
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let(::parseField)
        }
    }

    private fun parseField(obj: JsonObject): JsVoiceField? {
        val key = obj["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val options = (obj["options"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonObject)?.let { option ->
                val value = option["value"]?.jsonPrimitive?.contentOrNull ?: return@let null
                val label = option["label"]?.jsonPrimitive?.contentOrNull ?: value
                JsVoiceFieldOption(label, value)
            }
        }.orEmpty()
        return JsVoiceField(
            kind = kind,
            key = key,
            label = obj["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: key,
            description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            defaultValue = obj["default"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            options = options,
            allowCustom = obj["allowCustom"]?.jsonPrimitive?.booleanOrNull == true,
            visibleWhen = obj["visibleWhen"] as? JsonObject,
            defaultWhen = obj["defaultWhen"] as? JsonObject,
        )
    }

    /**
     * `foxy.settings.set` 能写哪些键：只有插件自己声明过的普通项。
     *
     * 密钥项永远不可写——它的值只在宿主建立连接时解密使用，插件不该有机会覆盖；
     * 未声明的键也不接受，否则插件能往偏好文件里塞任意键值。
     */
    fun writable(fields: List<JsVoiceField>, key: String): Boolean {
        val declared = fields.filter { it.key == key }
        return declared.isNotEmpty() && declared.all { field ->
            field.kind in setOf(
                JsVoiceField.KIND_TEXT,
                JsVoiceField.KIND_SELECT,
                JsVoiceField.KIND_SWITCH,
                JsVoiceField.KIND_NUMBER,
            )
        }
    }

    /** Bound plugin-driven persistence outside QuickJS's own heap budget. */
    fun requireWritableValue(values: Map<String, String>, key: String, value: String) {
        require(value.utf8Size() <= MAX_WRITABLE_VALUE_BYTES) { "Setting value is too large" }
        val updated = values + (key to value)
        val total = updated.entries.sumOf { (storedKey, storedValue) ->
            storedKey.utf8Size() + storedValue.utf8Size()
        }
        require(total <= MAX_WRITABLE_VALUES_BYTES) { "Plugin settings exceed their limit" }
    }

    /** 递归判断 `visibleWhen`：`{key,value}` 与 `{all:[...]}` 两种形态。 */
    fun isVisible(field: JsVoiceField, values: Map<String, String>): Boolean =
        matches(field.visibleWhen, values)

    private fun matches(condition: JsonObject?, values: Map<String, String>): Boolean {
        if (condition == null) return true
        val all = condition["all"]
        if (all is JsonArray) {
            return all.all { element -> matches(element as? JsonObject, values) }
        }
        val key = condition["key"]?.jsonPrimitive?.contentOrNull ?: return true
        val expected = condition["value"]?.jsonPrimitive?.contentOrNull ?: return true
        // 取不到被依赖的值时保持可见：藏起一个用户根本没法让它出现的设置，
        // 比多显示一项更难排查（例如 visibleWhen 依赖的是密钥字段，值永不下发）。
        val actual = values[key] ?: return true
        return actual == expected
    }

    /**
     * 把已保存的值与声明默认值合并成「当前值」视图。
     *
     * 只收普通项：密钥绝不进入这份 map，也就绝不会被塞进 `start(context)`——
     * 插件拿到的只有 `foxy.websocket.connect(url, "apiKey")` 里的**字段名**，
     * 值由宿主在建立连接时注入。
     */
    fun currentValues(fields: List<JsVoiceField>, stored: Map<String, String>): Map<String, String> {
        val values = LinkedHashMap<String, String>(fields.size)
        fields.forEach { field ->
            if (field.isSecret) return@forEach
            values[field.key] = stored[field.key] ?: field.defaultValue
        }
        // Resolve after collecting every control value, regardless of declaration order.
        // An explicitly saved resource still wins when opening settings or starting a session.
        fields.filterNot { it.isSecret || stored.containsKey(it.key) }.forEach { field ->
            dependentDefault(field, values)?.let { values[field.key] = it }
        }
        return values
    }

    /** Reset dependent defaults only after the user explicitly changes their controlling field. */
    fun defaultsAfterChange(fields: List<JsVoiceField>, changedKey: String, values: Map<String, String>): Map<String, String> =
        buildMap {
            fields.filterNot { it.isSecret }.forEach { field ->
                val control = (field.defaultWhen?.get("key") as? JsonPrimitive)?.contentOrNull
                if (control == changedKey) dependentDefault(field, values)?.let { put(field.key, it) }
            }
        }

    private fun dependentDefault(field: JsVoiceField, values: Map<String, String>): String? {
        val condition = field.defaultWhen ?: return null
        val key = (condition["key"] as? JsonPrimitive)?.contentOrNull ?: return null
        val options = condition["values"] as? JsonObject ?: return null
        val selected = values[key] ?: return null
        return (options[selected] as? JsonPrimitive)?.contentOrNull
    }
}
