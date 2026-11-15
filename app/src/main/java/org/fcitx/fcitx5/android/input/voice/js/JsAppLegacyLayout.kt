/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.serialization.json.*

/**
 * Foxy 的旧式平铺对话框：顶层直接给 `title` + `fields` + `buttons`，没有 `layout` 树。
 *
 * 上游保留这套格式只为兼容老插件（README 明说 "The old flat `fields` / `buttons` format
 * remains supported for compatibility"）。本宿主内部只有声明式 `layout`，所以在渲染前把它
 * 翻译成等价的 column，避免老插件直接撞上 "Dialog must declare a layout"。
 *
 * 规则与 Foxy 的解析保持一致：`type` 为 `select` 的字段渲染成下拉、`multiline` 渲染成多行输入、
 * 其余一律单行输入；`label` 缺省用 `id`；字段最多 12 个、按钮最多 8 个。
 * 按钮保留原事件 ID，并使用本宿主的完整按钮换行组件，避免窄屏或大字号丢失后部操作。
 */
internal object JsAppLegacyLayout {
    private const val MAX_FIELDS = 12
    private const val MAX_BUTTONS = 8

    /** 有 `layout` 时返回 null（不需要翻译）；既没有 layout 也没有可渲染内容时同样返回 null。 */
    fun convert(spec: JsonObject, values: Map<String, String> = emptyMap()): JsonObject? {
        if (spec["layout"] is JsonObject) return null
        val title = spec.text("title")
        val fields = (spec["fields"] as? JsonArray).orEmpty().take(MAX_FIELDS)
        val buttons = (spec["buttons"] as? JsonArray).orEmpty().take(MAX_BUTTONS)
        if (title.isBlank() && fields.isEmpty() && buttons.isEmpty()) return null
        val children = buildJsonArray {
            if (title.isNotBlank()) add(buildJsonObject {
                put("type", "text"); put("text", title.take(400)); put("size", 15); put("bold", true)
            })
            fields.forEach { element ->
                val field = element as? JsonObject ?: return@forEach
                val id = field.text("id").takeIf { it.isNotBlank() } ?: return@forEach
                val type = field.text("type")
                add(buildJsonObject {
                    put("type", if (type == "select") "select" else "input")
                    put("id", id)
                    put("label", field.text("label").ifBlank { id })
                    // Foxy 用当前值补齐缺省的 value；显式写了 value（哪怕是空串）就以它为准。
                    put("value", if (field["value"] is JsonPrimitive) field.text("value") else values[id].orEmpty())
                    if (type == "select") {
                        put("options", field["options"] as? JsonArray ?: buildJsonArray {})
                    } else {
                        put("placeholder", field.text("placeholder"))
                        put("multiline", type == "multiline")
                    }
                })
            }
            if (buttons.isNotEmpty()) add(buildJsonObject {
                put("type", "actions")
                put("children", buildJsonArray {
                    buttons.forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        val id = item.text("id").takeIf { it.isNotBlank() } ?: return@forEach
                        add(buildJsonObject {
                            put("type", "button"); put("id", id)
                            put("label", item.text("label").ifBlank { id })
                        })
                    }
                })
            })
        }
        return buildJsonObject { put("type", "column"); put("gap", 6); put("children", children) }
    }
}
