/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import android.util.Log
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.input.keyboard.*
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.core.FcitxKeyMapping

// Import Macro types explicitly
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.KeyRef

/**
 * JSON 转换工具类，用于键盘布局数据与 JSON 之间的双向转换。
 *
 * 主要功能：
 * - 解析：[parseKeyJsonArray], [parseLayoutRows], [parseOptionalFloat]
 * - 转换：[keyDefToJson], [convertToSaveJson], [convertToJsonProperty]
 * - 工具：[removeJsonComments], [resolveDisplayText]
 */
object LayoutJsonUtils {

    private const val TAG = "LayoutJsonUtils"
    const val LAYER_SUBMODE_PREFIX = "__layer__:"

    /**
     * 排列形态变体条目的保留前缀，形如 `"__variant__:split"`。
     *
     * 分体排列刻意做成"一个子布局条目"，而不是布局里多一个字段：这样它自动获得子布局
     * 已有的全部能力——编辑器的浏览与编辑、`__meta__`、保存时的 JSON 往返、QR 分享，
     * 都不需要另写一套。
     *
     * 与 [LAYER_SUBMODE_PREFIX] 同理，用保留前缀而不是让用户看得见的方案名，避免与真实
     * 子模式标签冲突。取值见 [org.fcitx.fcitx5.android.input.keyboard.LayoutVariant.jsonKey]。
     */
    const val VARIANT_SUBMODE_PREFIX = "__variant__:"

    /**
     * 一个布局条目的身份：属于哪个布局文件条目、哪个**子布局**、哪种**排列**。
     *
     * 排列挂在子布局上而不是整个布局上——用户要的是"给倉頡五代单独配一套分体排列"，
     * 而不是"给 rime 配一套、所有方案共用"。于是磁盘上的结构是每个子布局内部各自
     * 带一份分体排列：
     *
     * ```json
     * "rime": {
     *   "default": [...],
     *   "__variant__:split": [...],              // 默认子布局的分体排列
     *   "倉頡五代": {
     *     "default": [...],
     *     "__variant__:split": [...]             // 倉頡五代 自己的分体排列
     *   }
     * }
     * ```
     *
     * 编辑器内部把所有条目压平在 [LayoutDataManager.entries] 里，靠条目键区分：
     * `rime`、`rime:倉頡五代`、`rime:__variant__:split`、`rime:倉頡五代:__variant__:split`。
     * **本类是这套键的唯一权威解析/生成处**，别处请一律调用它，不要自己 substring。
     */
    data class EntryKey(
        val layoutName: String,
        /** 子布局标签；null 表示布局本体（「默认」子布局）。 */
        val subLayout: String?,
        val variant: LayoutVariant
    ) {
        /** 该身份在 [entries] 里的键。 */
        fun toEntryKey(): String {
            val head = subLayout?.let { "$layoutName:$it" } ?: layoutName
            val jsonKey = variant.jsonKey ?: return head
            return "$head:$VARIANT_SUBMODE_PREFIX$jsonKey"
        }

        val isSplit: Boolean get() = variant.isSplit

        /** 是否是分体排列条目（而非子布局本身）。 */
        val isSplitEntry: Boolean get() = variant.isSplit
    }

    private fun splitLayoutNameFrom(head: String): Pair<String, String?>? {
        if (head.isEmpty()) return null
        val separator = head.indexOf(':')
        if (separator < 0) return head to null
        val layoutName = head.substring(0, separator)
        val subLayout = head.substring(separator + 1)
        if (layoutName.isEmpty() || subLayout.isEmpty()) return null
        return layoutName to subLayout
    }

    /**
     * 解析条目键；结构不符合预期时返回 null（调用方应视为"无法识别的条目"并跳过）。
     *
     * 布局名与子布局标签都不允许含 `:`，但**保留前缀自带冒号**（`__layer__:x`、
     * `__variant__:split`），所以不能简单地按最后一个冒号切。这里的做法是先把排列那一段
     * 摘掉，再在剩下的部分里按**第一个**冒号分成"布局名 / 子布局"。
     *
     * 认不出的排列键（例如手写的 `__variant__:横屏`）按**普通子布局**处理，与加这个功能
     * 之前的行为一致——这样它至少能被原样读进来、原样写回去，而不是在保存时被静默删掉。
     */
    fun parseEntryKey(key: String): EntryKey? {
        val marker = ":$VARIANT_SUBMODE_PREFIX"
        val markerIndex = key.indexOf(marker)
        if (markerIndex >= 0) {
            val variantJsonKey = key.substring(markerIndex + marker.length)
            val known = LayoutVariant.fromJsonKey(variantJsonKey)
            if (known != null) {
                val (layoutName, subLayout) = splitLayoutNameFrom(key.substring(0, markerIndex)) ?: return null
                return EntryKey(layoutName, subLayout, known)
            }
        }
        val (layoutName, subLayout) = splitLayoutNameFrom(key) ?: return null
        return EntryKey(layoutName, subLayout, LayoutVariant.Docked)
    }

    /** 生成条目键（[EntryKey.toEntryKey] 的便捷写法）。 */
    fun entryKeyOf(layoutName: String, subLayout: String?, variant: LayoutVariant): String =
        EntryKey(layoutName, subLayout, variant).toEntryKey()

    private val KEY_FIELD_ORDER = listOf(
        "type",
        "main",
        "alt",
        "displayText",
        "label",
        "altLabel",
        "subLabel",
        "swipeLabel",
        "swipeUpLabel",
        "swipeDownLabel",
        "sym",
        "weight",
        "rowHeightPercent",
        "splitAfter",
        // 空白占位键是否完全不画键底。放在颜色字段之前，读起来是一句"这个键透明吗，
        // 不透明的话是什么颜色"。
        "transparent",

        "tap",
        "swipeUp",
        "swipeDown",
        "swipe",
        "longPress",
        "composeOverride",
        "independentColor",
        "textColor",
        "textColorMonet",
        "altTextColor",
        "altTextColorMonet",
        "backgroundColor",
        "backgroundColorMonet",
        "shadowColor",
        "shadowColorMonet"
    )

    fun toLayerSubModeLabel(childName: String): String = "$LAYER_SUBMODE_PREFIX$childName"

    fun isLayerSubModeLabel(label: String): Boolean = label.startsWith(LAYER_SUBMODE_PREFIX)

    fun childNameFromLayerLabel(label: String): String =
        label.removePrefix(LAYER_SUBMODE_PREFIX)

    /** 分体排列条目的子布局标签，形如 `__variant__:split`。 */
    fun toVariantSubModeLabel(variantKey: String): String = "$VARIANT_SUBMODE_PREFIX$variantKey"

    fun isVariantSubModeLabel(label: String): Boolean = label.startsWith(VARIANT_SUBMODE_PREFIX)

    /** 变体键（`split`）；不是变体标签时返回 null。 */
    fun variantKeyFromLabel(label: String): String? =
        label.removePrefix(VARIANT_SUBMODE_PREFIX).takeIf { isVariantSubModeLabel(label) }

    /**
     * 该子布局标签是否是"机器生成、不该出现在方案下拉框里"的条目。
     *
     * 层子布局（`__layer__:`）与分体排列（`__variant__:`）都属于此列：它们是布局结构的
     * 一部分，不是用户可切换的输入方案。编辑器的下拉框、方案标签收集、以及网页编辑器
     * 的目标白名单都应当用本函数过滤，否则用户会看到一串 `__variant__:split` 这样的选项。
     */
    fun isReservedSubModeLabel(label: String): Boolean =
        isLayerSubModeLabel(label) || isVariantSubModeLabel(label)

    fun baseLayoutNameFromEntryKey(key: String): String =
        parseEntryKey(key)?.layoutName ?: key.substringBefore(':')

    /**
     * 条目对应的**子布局**标签：布局本体（「默认」子布局）返回 null，其余返回标签。
     *
     * 排列那一段已经被去掉：`rime:倉頡五代:__variant__:split` 的子布局是 `倉頡五代`
     * 而不是 `倉頡五代:__variant__:split`——分体排列是子布局的一个属性，不是另一个子布局。
     */
    fun subLayoutLabelFromEntryKey(key: String, baseName: String): String? {
        val parsed = parseEntryKey(key)
        if (parsed != null && parsed.layoutName == baseName) return parsed.subLayout
        // 结构不符合预期（手工造的畸形键）：退回旧的按前缀切的做法，至少不丢内容。
        if (key == baseName || key == "$baseName:default") return null
        val rest = key.removePrefix("$baseName:")
        return rest.takeIf { !isVariantSubModeLabel(it) }
    }

    fun subModeLabelFromEntryKey(key: String, baseName: String): String =
        subLayoutLabelFromEntryKey(key, baseName) ?: "default"

    /**
     * [keys] 里属于 [baseName] 这个布局的**子布局**标签（输入方案、层子布局）。
     *
     * 分体排列条目（`rime:__variant__:split`、`rime:倉頡五代:__variant__:split`）**不算**
     * 子布局：它是某个子布局的一个属性。把它混进来，用户会在方案下拉框里看到一个删不掉、
     * 点了没反应的"方案"，而迁移逻辑还会把它当成一个方案去改写 displayText。
     *
     * 层子布局（`__layer__:x`）**算**子布局：它确实是一份独立的行集合，迁移与清理
     * 都应当认它。要在方案下拉框里隐藏它，请另外用 [isReservedSubModeLabel] 过滤。
     */
    fun subLayoutLabelsOf(keys: Iterable<String>, baseName: String): List<String> =
        keys.mapNotNull { key ->
            val parsed = parseEntryKey(key) ?: return@mapNotNull null
            if (parsed.layoutName != baseName || parsed.isSplitEntry) return@mapNotNull null
            parsed.subLayout
        }.distinct()

    /** [keys] 里是否存在属于 [baseName] 的子布局（层子布局算，分体排列条目不算）。 */
    fun hasSubLayouts(keys: Iterable<String>, baseName: String): Boolean =
        subLayoutLabelsOf(keys, baseName).isNotEmpty()

    // ==================== 解析功能 ====================

    /**
     * 从 JSON 字符串中移除 // 注释。
     *
     * 改进的引号处理：正确识别转义引号 (\")
     *
     * @param jsonStr 原始 JSON 字符串
     * @return 移除注释后的 JSON 字符串
     */
    fun removeJsonComments(jsonStr: String): String {
        return jsonStr.lines()
            .joinToString("\n") { line ->
                val commentIdx = line.indexOf("//")
                if (commentIdx >= 0) {
                    val beforeComment = line.substring(0, commentIdx)
                    // 更准确的引号计数：考虑转义引号
                    var quoteCount = 0
                    var i = 0
                    while (i < beforeComment.length) {
                        if (beforeComment[i] == '"' && (i == 0 || beforeComment[i - 1] != '\\')) {
                            quoteCount++
                        }
                        i++
                    }
                    if (quoteCount % 2 == 0) line.substring(0, commentIdx) else line
                } else line
            }
    }

    /**
     * 解析可选的 Float 值。
     *
     * 支持以下格式：
     * - JsonPrimitive(Number) → Float
     * - JsonPrimitive(String) → 尝试解析为 Float
     * - JsonNull → null
     * - null → null
     *
     * @param element JSON 元素
     * @return 解析后的 Float 值，或 null
     */
    fun parseOptionalFloat(element: JsonElement?): Float? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString) {
            primitive.content
                .trim()
                .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                ?.toFloatOrNull()
        } else {
            primitive.floatOrNull ?: primitive.doubleOrNull?.toFloat()
        }
    }

    fun parseOptionalInt(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString) {
            primitive.content
                .trim()
                .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                ?.let { raw ->
                    when {
                        raw.startsWith("#") -> raw.removePrefix("#").toLongOrNull(16)?.toInt()
                        raw.startsWith("0x", ignoreCase = true) -> raw.removePrefix("0x").toLongOrNull(16)?.toInt()
                        else -> raw.toLongOrNull()?.toInt()
                    }
                }
        } else {
            primitive.intOrNull ?: primitive.longOrNull?.toInt()
        }
    }

    /**
     * 解析 displayText 字段。
     *
     * 根据当前子模式标签和名称，从 displayText 中解析出正确的显示文本。
     *
     * 优先级：
     * 1. 匹配 schemaId
     * 2. 匹配 subModeLabel
     * 3. 匹配 subModeName
     * 4. 匹配 "default"
     * 5. 匹配空字符串键 ""
     * 6. 返回 default 参数
     *
     * @param displayText displayText JSON 元素
     * @param schemaId 当前方案 ID（从 subMode icon 推导）
     * @param subModeLabel 当前子模式标签
     * @param subModeName 当前子模式名称
     * @param default 默认值
     * @return 解析后的显示文本
     */
    fun resolveDisplayText(
        displayText: JsonElement?,
        schemaId: String,
        subModeLabel: String,
        subModeName: String,
        default: String
    ): String {
        return when {
            displayText == null -> default
            displayText is JsonPrimitive -> displayText.content
            displayText is JsonObject -> resolveDisplayTextMap(displayText, schemaId, subModeLabel, subModeName) ?: default
            else -> default
        }
    }

    private fun resolveDisplayTextMap(
        map: JsonObject,
        schemaId: String,
        subModeLabel: String,
        subModeName: String
    ): String? {
        fun valueOf(key: String): String? {
            if (key.isBlank()) return null
            return map[key]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
        }

        // 与文本键盘子模式匹配一致：schemaId → subModeLabel → subModeName → default → ""
        return valueOf(schemaId)
            ?: valueOf(subModeLabel)
            ?: valueOf(subModeName)
            ?: map["default"]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
            ?: map[""]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
    }

    /**
     * 解析单个 KeyJson 对象。
     *
     * @param obj JSON 对象
     * @return 解析后的 KeyJson，如果 type 缺失则返回 null
     */
    fun parseKeyJson(obj: JsonObject): KeyJson? {
        return parseKeyJsonInternal(obj, allowComposeOverride = true)
    }

    private fun parseKeyJsonInternal(
        obj: JsonObject,
        allowComposeOverride: Boolean,
        isComposeOverride: Boolean = false
    ): KeyJson? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        val composeOverride = if (allowComposeOverride) {
            obj["composeOverride"]?.jsonObject?.let { overrideObj ->
                val normalized = if ("type" in overrideObj) {
                    overrideObj
                } else {
                    JsonObject(overrideObj + ("type" to JsonPrimitive(type)))
                }
                parseKeyJsonInternal(normalized, allowComposeOverride = false, isComposeOverride = true)
            }
        } else {
            null
        }
        val independentColor = if (isComposeOverride) {
            obj["independentColor"]?.jsonPrimitive?.booleanOrNull
        } else null
        return KeyJson(
            type = type,
            // contentOrNull, not content: JsonNull.content is the literal string "null",
            // which would otherwise be rendered and committed as text on the key.
            main = obj["main"]?.jsonPrimitive?.contentOrNull,
            alt = obj["alt"]?.jsonPrimitive?.contentOrNull,
            displayText = obj["displayText"],  // AlphabetKey 和 MacroKey 共用
            label = obj["label"]?.jsonPrimitive?.contentOrNull,
            altLabel = obj["altLabel"]?.jsonPrimitive?.contentOrNull,
            longPressLabel = obj["longPressLabel"]?.jsonPrimitive?.contentOrNull,
            subLabel = obj["subLabel"]?.jsonPrimitive?.contentOrNull,
            swipeLabel = obj["swipeLabel"]?.jsonPrimitive?.contentOrNull,
            swipeUpLabel = obj["swipeUpLabel"]?.jsonPrimitive?.contentOrNull,
            swipeDownLabel = obj["swipeDownLabel"]?.jsonPrimitive?.contentOrNull,
            sym = obj["sym"]?.jsonPrimitive?.contentOrNull?.let { resolveKeysym(it) },
            weight = parseOptionalFloat(obj["weight"]),
            rowHeightPercent = parseOptionalFloat(obj["rowHeightPercent"]),
            splitAfter = obj["splitAfter"]?.jsonPrimitive?.booleanOrNull,
            transparent = obj["transparent"]?.jsonPrimitive?.booleanOrNull,
            textColor = parseOptionalInt(obj["textColor"]),
            textColorMonet = obj["textColorMonet"]?.jsonPrimitive?.contentOrNull,
            altTextColor = parseOptionalInt(obj["altTextColor"]),
            altTextColorMonet = obj["altTextColorMonet"]?.jsonPrimitive?.contentOrNull,
            backgroundColor = parseOptionalInt(obj["backgroundColor"]),
            backgroundColorMonet = obj["backgroundColorMonet"]?.jsonPrimitive?.contentOrNull,
            shadowColor = parseOptionalInt(obj["shadowColor"]),
            shadowColorMonet = obj["shadowColorMonet"]?.jsonPrimitive?.contentOrNull,
            tap = obj["tap"]?.jsonObject?.let { parseMacroAction(it) },
            swipeUp = obj["swipeUp"]?.jsonObject?.let { parseMacroAction(it) },
            swipeDown = obj["swipeDown"]?.jsonObject?.let { parseMacroAction(it) },
            swipe = obj["swipe"]?.jsonObject?.let { parseMacroAction(it) },
            longPress = obj["longPress"]?.jsonObject?.let { parseMacroAction(it) },
            independentColor = independentColor,
            composeOverride = composeOverride
        )
    }

    /**
     * 解析 KeyRef（按键引用）
     * @param obj JSON 对象，包含 "fcitx" 或 "android" 字段
     * @return 解析后的 KeyRef
     */
    fun parseKeyRef(obj: JsonObject): KeyRef {
        // 优先解析 fcitx 类型
        obj["fcitx"]?.jsonPrimitive?.content?.let { return KeyRef.Fcitx(it) }
        
        // 解析 android 类型（只接受整数键码）
        obj["android"]?.let { androidValue ->
            val keyCode = androidValue.jsonPrimitive.intOrNull
                ?: androidValue.jsonPrimitive.content.toIntOrNull()
            if (keyCode != null) {
                return KeyRef.Android(keyCode)
            }
        }
        
        // 如果 android 值不是整数，可能是数据错误（如 {"android": "Left"}）
        // 尝试将其作为 fcitx 键名处理
        obj["android"]?.jsonPrimitive?.content?.let {
            return KeyRef.Fcitx(it)
        }
        
        throw IllegalArgumentException("Invalid KeyRef: $obj")
    }

    /**
     * 解析 MacroStep（宏步骤）
     * @param obj JSON 对象，包含 "type" 字段和其他参数字段
     * @return 解析后的 MacroStep
     */
    fun parseMacroStep(obj: JsonObject): MacroStep {
        val type = obj["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing step type")
        return when (type) {
            "down" -> MacroStep.Down(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "up" -> MacroStep.Up(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "tap" -> MacroStep.Tap(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "text" -> MacroStep.Text(obj["text"]?.jsonPrimitive?.content ?: "")
            "edit" -> MacroStep.Edit(obj["action"]?.jsonPrimitive?.content ?: "copy")
            "app" -> MacroStep.AppAction(obj["id"]?.jsonPrimitive?.content ?: "theme")
            "layer" -> MacroStep.LayerSwitch(
                mode = parseLayerSwitchMode(obj["mode"]?.jsonPrimitive?.content),
                target = obj["target"]?.jsonPrimitive?.content ?: ""
            )
            "shortcut" -> {
                val modifiers = obj["modifiers"]?.jsonArray?.map { parseKeyRef(it.jsonObject) }
                    ?: obj["modifier"]?.jsonObject?.let { listOf(parseKeyRef(it)) }
                    ?: throw IllegalArgumentException("Missing modifiers for shortcut")
                val key = obj["key"]?.jsonObject?.let { parseKeyRef(it) }
                    ?: throw IllegalArgumentException("Missing key for shortcut")
                MacroStep.Shortcut(modifiers, key)
            }
            else -> throw IllegalArgumentException("Unknown step type: $type")
        }
    }

    private fun parseLayerSwitchMode(raw: String?): KeyAction.LayerSwitchMode {
        return when (raw?.lowercase()) {
            "osl" -> KeyAction.LayerSwitchMode.OSL
            "back" -> KeyAction.LayerSwitchMode.BACK
            else -> KeyAction.LayerSwitchMode.TO
        }
    }

    private fun layerSwitchModeToString(mode: KeyAction.LayerSwitchMode): String {
        return when (mode) {
            KeyAction.LayerSwitchMode.TO -> "to"
            KeyAction.LayerSwitchMode.OSL -> "osl"
            KeyAction.LayerSwitchMode.BACK -> "back"
        }
    }

    /**
     * 解析 MacroAction（宏动作）
     * @param obj JSON 对象，包含 "macro" 字段
     * @return 解析后的 MacroAction
     */
    fun parseMacroAction(obj: JsonObject): MacroAction {
        // parseMacroStep/parseKeyRef throw on malformed input. This function is on the
        // runtime keyboard path (parseKeyJsonArray -> parseKeyJson -> here), so a single
        // bad step must not take down the whole layout: drop it and log instead.
        val steps = obj["macro"]?.jsonArray?.mapNotNull { element ->
            runCatching { parseMacroStep(element.jsonObject) }
                .onFailure { Log.w(TAG, "Skipping invalid macro step: " + it.message) }
                .getOrNull()
        } ?: emptyList()
        return MacroAction(steps)
    }

    /**
     * 解析一行按键数据。
     *
     * @param rowArray 包含按键的 JSON 数组
     * @param showLangSwitch 是否显示语言切换键（用于过滤 LanguageKey）
     * @return 解析后的 KeyJson 列表
     */
    fun parseKeyJsonArray(rowArray: JsonArray, showLangSwitch: Boolean = true): List<KeyJson> {
        return rowArray.mapNotNull { element ->
            // A malformed layout row may contain non-object elements (hand-edited or
            // imported files). Skip them instead of throwing, otherwise every attempt to
            // show the keyboard crashes. Mirrors parseLayoutRows' editor-side behavior.
            val obj = element as? JsonObject ?: run {
                Log.w(TAG, "Skipping non-object key element: " + element::class.simpleName)
                return@mapNotNull null
            }
            val type = obj["type"]?.jsonPrimitive?.content ?: ""
            // 如果 showLangSwitch 为 false，跳过 LanguageKey
            if (type == "LanguageKey" && !showLangSwitch) {
                return@mapNotNull null
            }
            parseKeyJson(obj)
        }
    }

    /**
     * 从 JsonArray 解析布局行数据为 Map 表示。
     *
     * @param rowsArray JSON 数组，包含布局行数据
     * @return 解析后的布局行列表
     */
    fun parseLayoutRows(rowsArray: JsonArray): List<List<Map<String, Any?>>> {
        val rows = mutableListOf<List<Map<String, Any?>>>()
        for (i in rowsArray.indices) {
            val rowArray = rowsArray[i].jsonArray
            val row = mutableListOf<Map<String, Any?>>()
            for (j in rowArray.indices) {
                val rowElement = rowArray[j]
                if (rowElement is JsonNull) continue
                if (rowElement !is JsonObject) {
                    Log.w(TAG, "Skipping invalid key element at row $i, col $j: ${rowElement::class.simpleName}")
                    continue
                }

                val keyJson = rowElement
                val keyMap = mutableMapOf<String, Any?>()
                keyJson.entries.forEach { (key, value) ->
                    keyMap[key] = normalizeKeyValue(key, toAny(value))
                }
                row.add(keyMap)
            }
            rows.add(row)
        }
        return rows
    }

    /**
     * 旧版**单槽**划动配置（`swipe` / `altLabel` / `swipeLabel`）没有物理方向，读取、
     * 迁移与编辑时都按同一条规则解析，保证三处结果一致：
     *
     * - 键上**只有**上划一侧的槽位（上划动作或上滑标签）时，旧字段跟到上滑槽；
     * - 其余情况（两侧都有、都没有、只有下划一侧）一律落到下滑槽。
     *
     * 之所以要「跟随同侧槽位」：标签摆在上面、动作却在下面（或反过来）会让用户看到的方向
     * 与实际触发方向不符；跟随同侧后「标签显示在哪边，哪边就是触发方向」恒成立。
     *
     * ⚠️ **绝不能让主题的「标点位置」或「符号划动方向」参与解析**。前者的语义只是摆放
     * 字母键的标点副标签，后者的语义是「符号隐藏时用哪个方向唤出符号」；让它们决定划动
     * 方向会出现在换一次主题/设置后，用户配好的下滑键被整体翻成上滑、副标签也跑到上方。
     */
    fun legacySwipeTargetsUp(
        hasUpMacro: Boolean,
        hasDownMacro: Boolean,
        hasUpLabel: Boolean,
        hasDownLabel: Boolean
    ): Boolean = (hasUpMacro || hasUpLabel) && !(hasDownMacro || hasDownLabel)

    /**
     * 将旧版单一 swipe 槽位迁移为物理上划或下划槽位。
     *
     * 迁移只处理支持方向宏的键型；SpaceKey 保留旧专用滑动语义。**只有带方向线索的键才会
     * 迁移**（有划动标签，或已有 swipeUp/swipeDown 任一侧），目标方向由 [legacySwipeTargetsUp]
     * 决定（默认下滑）；没有方向线索、也没有标签的旧 `swipe` 原样保留，运行时继续按
     * 「符号划动方向（符号隐藏时）」决定——钉死到某一侧会悄悄丢掉另一个方向的触发。
     *
     * 宏字段和标签字段分别判断：各自已有方向字段时删除对应旧字段，否则迁移旧字段，
     * 避免部分新配置覆盖另一类仍需保留的旧数据。
     */
    fun migrateDirectionalSwipeFields(
        entries: MutableMap<String, MutableList<MutableList<MutableMap<String, Any?>>>>
    ): Boolean {
        val directionalTypes = setOf(
            "CapsKey", "LayoutSwitchKey", "SymbolKey", "ReturnKey", "BackspaceKey", "MacroKey"
        )
        var changed = false

        fun migrateKey(key: MutableMap<String, Any?>, inheritedType: String? = null): Boolean {
            val type = key["type"] as? String ?: inheritedType
            var keyChanged = false
            if (type in directionalTypes) {
                val legacyLabelField = if (type == "MacroKey") "altLabel" else "swipeLabel"
                val hasNewMacro = key.containsKey("swipeUp") || key.containsKey("swipeDown")
                val hasNewLabel = key.containsKey("swipeUpLabel") ||
                    key.containsKey("swipeDownLabel")
                val hasLegacyLabel = !((key[legacyLabelField] as? String).isNullOrEmpty())
                if (hasLegacyLabel || hasNewMacro || hasNewLabel) {
                    val targetsUp = legacySwipeTargetsUp(
                        hasUpMacro = key.containsKey("swipeUp"),
                        hasDownMacro = key.containsKey("swipeDown"),
                        hasUpLabel = key.containsKey("swipeUpLabel"),
                        hasDownLabel = key.containsKey("swipeDownLabel")
                    )
                    if (hasNewMacro) {
                        key.remove("swipe")?.let { keyChanged = true }
                    } else if (key.containsKey("swipe")) {
                        val targetMacro = if (targetsUp) "swipeUp" else "swipeDown"
                        key[targetMacro] = key.remove("swipe")
                        keyChanged = true
                    }
                    if (hasNewLabel) {
                        key.remove(legacyLabelField)?.let { keyChanged = true }
                    } else if (key.containsKey(legacyLabelField)) {
                        val targetLabel = if (targetsUp) "swipeUpLabel" else "swipeDownLabel"
                        key[targetLabel] = key.remove(legacyLabelField)
                        keyChanged = true
                    }
                }
            }

            val composeOverride = key["composeOverride"] as? Map<*, *>
            if (composeOverride != null) {
                val nested = composeOverride.entries
                    .associate { (name, value) -> name.toString() to value }
                    .toMutableMap()
                if (migrateKey(nested, type)) {
                    key["composeOverride"] = nested
                    keyChanged = true
                }
            }
            return keyChanged
        }

        entries.values.forEach { rows ->
            rows.forEach { row ->
                row.forEach { key ->
                    if (migrateKey(key)) changed = true
                }
            }
        }
        return changed
    }
    /**
     * 规范化键值，特别处理 weight 字段。
     *
     * @param key 键名
     * @param value 原始值
     * @return 规范化后的值
     */
    private fun normalizeKeyValue(key: String, value: Any?): Any? {
        if (key != "weight" &&
            key != "rowHeightPercent" &&
            key != "sym" &&
            key != "textColor" &&
            key != "altTextColor" &&
            key != "backgroundColor" &&
            key != "shadowColor"
        ) return value
        return when (value) {
            null -> null
            is Number -> if (key == "weight" || key == "rowHeightPercent") value.toFloat() else value.toInt()
            is String -> {
                value.trim()
                    .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                    ?.let {
                        if (key == "weight" || key == "rowHeightPercent") {
                            it.toFloatOrNull()
                        } else if (key == "sym") {
                            resolveKeysym(it)
                        } else {
                            when {
                                it.startsWith("#") -> it.removePrefix("#").toLongOrNull(16)?.toInt()
                                it.startsWith("0x", ignoreCase = true) -> it.removePrefix("0x").toLongOrNull(16)?.toInt()
                                else -> it.toLongOrNull()?.toInt()
                            }
                        }
                    }
            }
            else -> null
        }
    }

    /**
     * 将按键名解析为 Fcitx keysym（大小写不敏感），兼容以下格式：
     * - 十进制数字（如 "65458"）
     * - 十六进制（"0xffb2" 或 "#ffb2"）
     * - X11/Fcitx 按键名（如 "KP_2"、"KP_Add"、"plus"、"BackSpace"）
     *
     * 纯数字与运算符标签按数字键盘语义解析到 KP_* keysym。
     */
    fun resolveKeysym(input: String?): Int? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        // 先查按键名表：纯数字标签（如 "2"）按数字键盘语义解析为 KP_*，
        // 避免 "2" 被误当成十进制 keysym 2（0x02，控制符）。
        SYM_NAME_TO_CODE[trimmed]?.let { return it }
        SYM_NAME_TO_CODE[trimmed.lowercase()]?.let { return it }
        trimmed.toIntOrNull()?.let { return it }
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.removePrefix("0x").toIntOrNull(16)?.let { return it }
        }
        if (trimmed.startsWith("#")) {
            trimmed.removePrefix("#").toIntOrNull(16)?.let { return it }
        }
        return null
    }

    /**
     * 将 Fcitx keysym 转换为人类可读的规范按键名（如 65458 → "KP_2"）。
     * 未知的 keysym 原样返回整数，保证 round-trip 兼容。
     */
    fun keysymToName(sym: Int): Any? = SYM_CODE_TO_NAME[sym] ?: sym

    private val SYM_CODE_TO_NAME: Map<Int, String> = buildMap {
        for (i in 0..9) {
            put(FcitxKeyMapping.FcitxKey_KP_0 + i, "KP_$i")
        }
        put(FcitxKeyMapping.FcitxKey_KP_Add, "KP_Add")
        put(FcitxKeyMapping.FcitxKey_KP_Subtract, "KP_Subtract")
        put(FcitxKeyMapping.FcitxKey_KP_Multiply, "KP_Multiply")
        put(FcitxKeyMapping.FcitxKey_KP_Divide, "KP_Divide")
        put(FcitxKeyMapping.FcitxKey_KP_Decimal, "KP_Decimal")
        put(FcitxKeyMapping.FcitxKey_KP_Separator, "KP_Separator")
        put(FcitxKeyMapping.FcitxKey_KP_Equal, "KP_Equal")
        put(FcitxKeyMapping.FcitxKey_KP_Enter, "KP_Enter")
    }

    private val SYM_NAME_TO_CODE: Map<String, Int> = buildMap {
        for (i in 0..9) {
            put(i.toString(), FcitxKeyMapping.FcitxKey_KP_0 + i)
            put("kp_$i", FcitxKeyMapping.FcitxKey_KP_0 + i)
        }
        for (c in 'a'..'z') {
            put(c.toString(), FcitxKeyMapping.FcitxKey_a + (c - 'a'))
            put(c.uppercaseChar().toString(), FcitxKeyMapping.FcitxKey_a + (c - 'a'))
        }
        put("+", FcitxKeyMapping.FcitxKey_KP_Add)
        put("-", FcitxKeyMapping.FcitxKey_KP_Subtract)
        put("*", FcitxKeyMapping.FcitxKey_KP_Multiply)
        put("/", FcitxKeyMapping.FcitxKey_KP_Divide)
        put(",", FcitxKeyMapping.FcitxKey_KP_Separator)
        put(".", FcitxKeyMapping.FcitxKey_KP_Decimal)
        put("=", FcitxKeyMapping.FcitxKey_KP_Equal)
        put("kp_add", FcitxKeyMapping.FcitxKey_KP_Add)
        put("kp_subtract", FcitxKeyMapping.FcitxKey_KP_Subtract)
        put("kp_multiply", FcitxKeyMapping.FcitxKey_KP_Multiply)
        put("kp_divide", FcitxKeyMapping.FcitxKey_KP_Divide)
        put("kp_decimal", FcitxKeyMapping.FcitxKey_KP_Decimal)
        put("kp_separator", FcitxKeyMapping.FcitxKey_KP_Separator)
        put("kp_equal", FcitxKeyMapping.FcitxKey_KP_Equal)
        put("kp_enter", FcitxKeyMapping.FcitxKey_KP_Enter)
        // 常用 X11/Fcitx 规范键名（小写）
        put("space", FcitxKeyMapping.FcitxKey_space)
        put("numbersign", FcitxKeyMapping.FcitxKey_numbersign)
        put("apostrophe", FcitxKeyMapping.FcitxKey_apostrophe)
        put("asterisk", FcitxKeyMapping.FcitxKey_asterisk)
        put("plus", FcitxKeyMapping.FcitxKey_plus)
        put("comma", FcitxKeyMapping.FcitxKey_comma)
        put("minus", FcitxKeyMapping.FcitxKey_minus)
        put("period", FcitxKeyMapping.FcitxKey_period)
        put("slash", FcitxKeyMapping.FcitxKey_slash)
        put("semicolon", FcitxKeyMapping.FcitxKey_semicolon)
        put("equal", FcitxKeyMapping.FcitxKey_equal)
        put("at", FcitxKeyMapping.FcitxKey_at)
        put("bracketleft", FcitxKeyMapping.FcitxKey_bracketleft)
        put("bracketright", FcitxKeyMapping.FcitxKey_bracketright)
        put("backslash", FcitxKeyMapping.FcitxKey_backslash)
        put("grave", FcitxKeyMapping.FcitxKey_grave)
        put("return", FcitxKeyMapping.FcitxKey_Return)
        put("backspace", FcitxKeyMapping.FcitxKey_BackSpace)
        put("tab", FcitxKeyMapping.FcitxKey_Tab)
        put("escape", FcitxKeyMapping.FcitxKey_Escape)
        put("up", FcitxKeyMapping.FcitxKey_Up)
        put("down", FcitxKeyMapping.FcitxKey_Down)
        put("left", FcitxKeyMapping.FcitxKey_Left)
        put("right", FcitxKeyMapping.FcitxKey_Right)
        put("home", FcitxKeyMapping.FcitxKey_Home)
        put("end", FcitxKeyMapping.FcitxKey_End)
        put("page_up", FcitxKeyMapping.FcitxKey_Page_Up)
        put("page_down", FcitxKeyMapping.FcitxKey_Page_Down)
        put("insert", FcitxKeyMapping.FcitxKey_Insert)
        put("delete", FcitxKeyMapping.FcitxKey_Delete)
    }

    /**
     * 将 JsonElement 递归转换为 Any? 类型。
     *
     * 转换规则：
     * - JsonObject → Map<String, Any?>
     * - JsonArray → List<Any?>
     * - JsonPrimitive(String) → String
     * - JsonPrimitive(Boolean) → Boolean
     * - JsonPrimitive(Number) → Number
     * - JsonNull → null
     *
     * @param element JSON 元素
     * @return 转换后的 Any? 值
     */
    fun toAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.toMap().mapValues { it.value.let { v -> toAny(v) } }
        is JsonArray -> element.map { toAny(it) }
        is JsonPrimitive -> {
            if (element.isString) element.content
            else element.booleanOrNull ?: element.intOrNull ?: element.doubleOrNull
        }
        is JsonNull -> null
    }

    // ==================== 转换功能 ====================

    /**
     * 数据类，表示解析后的按键 JSON 数据。
     *
     * @property type 按键类型
     * @property main 主要字符（AlphabetKey）
     * @property alt 备选字符（AlphabetKey）
     * @property displayText 显示文本（支持子模式）
     * @property label 标签（LayoutSwitchKey, SymbolKey, MacroKey）
     * @property altLabel 备选标签（MacroKey）
     * @property subLabel 子标签（LayoutSwitchKey）
    * @property swipeLabel 划动标签（支持 swipe 的普通键）
     * @property weight 权重
     * @property tap 点击宏（MacroKey）
     * @property swipe 划动宏（MacroKey）
     * @property longPress 长按宏（MacroKey）
     */
    data class KeyJson(
        val type: String,
        val main: String? = null,
        val alt: String? = null,
        val displayText: JsonElement? = null,  // AlphabetKey 和 MacroKey 共用
        val label: String? = null,  // MacroKey/SymbolKey/LayoutSwitchKey 使用
        val altLabel: String? = null,  // MacroKey 使用
        val longPressLabel: String? = null,  // MacroKey 使用
        val subLabel: String? = null,  // LayoutSwitchKey 使用
        val swipeLabel: String? = null,  // 旧版非 Macro 划动提示
         val swipeUpLabel: String? = null,
         val swipeDownLabel: String? = null,
        val sym: Int? = null,  // NumPadKey 使用（Fcitx keysym）
        val weight: Float? = null,
        val textColor: Int? = null,
        val textColorMonet: String? = null,
        val altTextColor: Int? = null,
        val altTextColorMonet: String? = null,
        val backgroundColor: Int? = null,
        val backgroundColorMonet: String? = null,
        val shadowColor: Int? = null,
        val shadowColorMonet: String? = null,
        val tap: MacroAction? = null,  // MacroKey 使用
        val swipeUp: MacroAction? = null,
         val swipeDown: MacroAction? = null,
         val swipe: MacroAction? = null,  // 旧版单一划动宏
        val longPress: MacroAction? = null,  // MacroKey 使用
        val independentColor: Boolean? = null,
        val rowHeightPercent: Float? = null,
        /** 分体键盘的手动分界：本键之后把这一行断开（见 `KeyDef.splitAfter`）。 */
        val splitAfter: Boolean? = null,
        /**
         * 空白占位键是否完全不绘制键底（见 `KeyDef.Appearance.transparentBackground`）。
         *
         * `null` 表示字段缺失，按该类型的默认值处理：PlaceholderKey 默认透明。
         * 之所以要能显式写 `false`，是因为"填了字符+颜色"的装饰占位键需要一块真实的
         * 底色，那时透明反过来才是错的。
         */
        val transparent: Boolean? = null,
        val composeOverride: KeyJson? = null
    )

    private fun putDirectionalSwipeFieldsForKey(
        json: MutableMap<String, Any?>,
        keyDef: KeyDef
    ) {
        when (keyDef) {
            is CapsKey -> putDirectionalSwipeFields(
                json, keyDef.swipeLabel, keyDef.swipeUpLabel, keyDef.swipeDownLabel,
                keyDef.swipe, keyDef.swipeUp, keyDef.swipeDown
            )
            is LayoutSwitchKey -> putDirectionalSwipeFields(
                json, keyDef.swipeLabel, keyDef.swipeUpLabel, keyDef.swipeDownLabel,
                keyDef.swipe, keyDef.swipeUp, keyDef.swipeDown
            )
            is SymbolKey -> putDirectionalSwipeFields(
                json, keyDef.swipeLabel, keyDef.swipeUpLabel, keyDef.swipeDownLabel,
                keyDef.swipe, keyDef.swipeUp, keyDef.swipeDown
            )
            is ReturnKey -> putDirectionalSwipeFields(
                json, keyDef.swipeLabel, keyDef.swipeUpLabel, keyDef.swipeDownLabel,
                keyDef.swipe, keyDef.swipeUp, keyDef.swipeDown
            )
            is BackspaceKey -> putDirectionalSwipeFields(
                json, keyDef.swipeLabel, keyDef.swipeUpLabel, keyDef.swipeDownLabel,
                keyDef.swipe, keyDef.swipeUp, keyDef.swipeDown
            )
            is MacroKey -> putDirectionalSwipeFields(
                json, null, keyDef.swipeUpLabel, keyDef.swipeDownLabel, keyDef.swipe,
                keyDef.swipeUp, keyDef.swipeDown
            )
            else -> Unit
        }
    }
    private fun putDirectionalSwipeFields(
        json: MutableMap<String, Any?>,
        legacyLabel: String?,
        upLabel: String?,
        downLabel: String?,
        legacyMacro: MacroAction?,
        upMacro: MacroAction?,
        downMacro: MacroAction?
    ) {
        if (upLabel != null || downLabel != null) json.remove("swipeLabel")
        if (upMacro != null || downMacro != null) json.remove("swipe")
        upLabel?.let { json["swipeUpLabel"] = it }
        downLabel?.let { json["swipeDownLabel"] = it }
        if (upLabel == null && downLabel == null) {
            legacyLabel?.let { json["swipeLabel"] = it }
        }
        upMacro?.let { json["swipeUp"] = macroActionToJson(it) }
        downMacro?.let { json["swipeDown"] = macroActionToJson(it) }
        if (upMacro == null && downMacro == null) {
            legacyMacro?.let { json["swipe"] = macroActionToJson(it) }
        }
    }
    /**
     * 将 KeyDef 转换为 JSON 地图用于保存。
     *
     * @param keyDef 键盘定义对象
     * @return JSON 地图，包含 type、main、alt、weight 等字段
     */
    fun keyDefToJson(keyDef: KeyDef): MutableMap<String, Any?> {
        val type = when (keyDef) {
            is AlphabetKey -> "AlphabetKey"
            is CapsKey -> "CapsKey"
            is LayoutSwitchKey -> "LayoutSwitchKey"
            is CommaKey -> "CommaKey"
            is LanguageKey -> "LanguageKey"
            is SpaceKey -> "SpaceKey"
            is SymbolKey -> "SymbolKey"
            is ReturnKey -> "ReturnKey"
            is BackspaceKey -> "BackspaceKey"
            is MacroKey -> "MacroKey"
            is NumPadKey -> "NumPadKey"
            is MiniSpaceKey -> "MiniSpaceKey"
            is PlaceholderKey -> "PlaceholderKey"
            else -> "SpaceKey"
        }

        val json = mutableMapOf<String, Any?>("type" to type)
        val appearance = keyDef.appearance
        appearance.textColor?.let { json["textColor"] = it }
        appearance.textColorMonet?.let { json["textColorMonet"] = it }
        appearance.altTextColor?.let { json["altTextColor"] = it }
        appearance.altTextColorMonet?.let { json["altTextColorMonet"] = it }
        appearance.backgroundColor?.let { json["backgroundColor"] = it }
        appearance.backgroundColorMonet?.let { json["backgroundColorMonet"] = it }
        appearance.shadowColor?.let { json["shadowColor"] = it }
        appearance.shadowColorMonet?.let { json["shadowColorMonet"] = it }

        when (keyDef) {
            is AlphabetKey -> {
                json["main"] = keyDef.character
                json["alt"] = keyDef.punctuation
                // 未设置（null / 空串）时不写该字段；显式填写的一律写回，**哪怕取值与 main
                // 相同**——那正是「键面恒定显示这个大小写」的诉求，不能因为同值就丢掉。
                keyDef.displayText?.takeIf { it.isNotEmpty() }?.let { json["displayText"] = it }
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
            }
            is CapsKey -> {
                json["weight"] = appearance.percentWidth
                putDirectionalSwipeFieldsForKey(json, keyDef)
            }
            is LayoutSwitchKey -> {
                json["label"] = (appearance as? KeyDef.Appearance.Text)?.displayText
                json["subLabel"] = keyDef.to
                json["weight"] = appearance.percentWidth
                putDirectionalSwipeFieldsForKey(json, keyDef)
            }
            is CommaKey -> {
                json["weight"] = appearance.percentWidth
            }
            is LanguageKey -> {
                json["weight"] = appearance.percentWidth
            }
            is SpaceKey -> {
                json["weight"] = appearance.percentWidth
                keyDef.swipeLabel?.let { json["swipeLabel"] = it }
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
            }
            is SymbolKey -> {
                json["label"] = keyDef.symbol
                json["weight"] = appearance.percentWidth
                putDirectionalSwipeFieldsForKey(json, keyDef)
            }
            is ReturnKey -> {
                json["weight"] = appearance.percentWidth
                putDirectionalSwipeFieldsForKey(json, keyDef)
            }
            is BackspaceKey -> {
                json["weight"] = appearance.percentWidth
                putDirectionalSwipeFieldsForKey(json, keyDef)
            }
            is NumPadKey -> {
                json["label"] = (appearance as? KeyDef.Appearance.Text)?.displayText
                json["sym"] = keysymToName(keyDef.sym)
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
            }
            is MiniSpaceKey -> {
                json["weight"] = appearance.percentWidth.takeIf { it != 0.15f }
            }
            is PlaceholderKey -> {
                // 主/副字符都允许为空（那正是"完全空白"的形态），因此**按是否存在**写字段，
                // 而不是像其它键那样用 `?: "0"` 之类兜底——兜底会把"留空"变成一个可见字符。
                val text = (appearance as? KeyDef.Appearance.AltText)
                text?.displayText?.takeIf { it.isNotEmpty() }?.let { json["main"] = it }
                text?.altText?.takeIf { it.isNotEmpty() }?.let { json["alt"] = it }
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
                // 只在"与默认相反"时写：占位键默认透明，写 false 的唯一理由是用户开了
                // 自定义颜色、需要一块真实底色（见 KeyJson.transparent 的说明）。
                if (!appearance.transparentBackground) json["transparent"] = false
            }
            is MacroKey -> {
                json["label"] = keyDef.label
                keyDef.displayText?.takeIf { it.isNotEmpty() }?.let { json["displayText"] = it }
                if (keyDef.longPressLabel != null) {
                    json["longPressLabel"] = keyDef.longPressLabel
                }
                json["tap"] = macroActionToJson(keyDef.tap)
                putDirectionalSwipeFieldsForKey(json, keyDef)
                keyDef.longPress?.let { json["longPress"] = macroActionToJson(it) }
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
            }
        }

        keyDef.composeOverride?.let { overrideDef ->
            val overrideJson = keyDefToJson(overrideDef).toMutableMap()
            overrideJson.remove("weight")
            overrideJson.remove("rowHeightPercent")
            if ((overrideJson["type"] as? String).isNullOrBlank()) {
                overrideJson["type"] = type
            }
            json["composeOverride"] = overrideJson
        }
        if (keyDef.independentColor) {
            json["independentColor"] = true
        }
        keyDef.rowHeightPercent?.let { rowHeight ->
            json["rowHeightPercent"] = rowHeight
        }
        // 只有置位的键才写出该字段；见 createKeyDef 的说明。
        if (keyDef.splitAfter) {
            json["splitAfter"] = true
        }

        return json
    }

    /**
     * 将 MacroAction 转换为 JSON 对象。
     *
     * @param action MacroAction 对象
     * @return JSON 对象，包含 "macro" 字段
     */
    fun macroActionToJson(action: MacroAction): JsonObject {
        val steps = JsonArray(action.steps.map { macroStepToJson(it) })
        return JsonObject(mapOf("macro" to steps))
    }

    /**
     * 将 MacroStep 转换为 JSON 对象。
     *
     * @param step MacroStep 对象
     * @return JSON 对象
     */
    fun macroStepToJson(step: MacroStep): JsonObject {
        return when (step) {
            is MacroStep.Down -> JsonObject(mapOf(
                "type" to JsonPrimitive("down"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Up -> JsonObject(mapOf(
                "type" to JsonPrimitive("up"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Tap -> JsonObject(mapOf(
                "type" to JsonPrimitive("tap"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Text -> JsonObject(mapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(step.text)
            ))
            is MacroStep.Edit -> JsonObject(mapOf(
                "type" to JsonPrimitive("edit"),
                "action" to JsonPrimitive(step.action)
            ))
            is MacroStep.AppAction -> JsonObject(mapOf(
                "type" to JsonPrimitive("app"),
                "id" to JsonPrimitive(step.id)
            ))
            is MacroStep.LayerSwitch -> JsonObject(mapOf(
                "type" to JsonPrimitive("layer"),
                "mode" to JsonPrimitive(layerSwitchModeToString(step.mode)),
                "target" to JsonPrimitive(step.target)
            ))
            is MacroStep.Shortcut -> JsonObject(mapOf(
                "type" to JsonPrimitive("shortcut"),
                "modifiers" to JsonArray(step.modifiers.map { keyRefToJson(it) }),
                "key" to keyRefToJson(step.key)
            ))
        }
    }

    /**
     * 将 KeyRef 转换为 JSON 对象。
     *
     * @param ref KeyRef 对象
     * @return JSON 对象
     */
    fun keyRefToJson(ref: KeyRef): JsonObject {
        return when (ref) {
            is KeyRef.Fcitx -> JsonObject(mapOf("fcitx" to JsonPrimitive(ref.code)))
            is KeyRef.Android -> JsonObject(mapOf("android" to JsonPrimitive(ref.code)))
        }
    }

    /**
     * 将 KeyJson 转换为 KeyDef。
     *
     * @param key 解析后的 KeyJson
     * @param subModeLabel 当前子模式标签（用于解析 displayText）
     * @param subModeName 当前子模式名称（用于解析 displayText）
     * @return 转换后的 KeyDef，当该键的必填字段缺失时返回 null（调用方应跳过它，
     *         而不是让单个坏键导致整个布局无法加载）
     */
    fun createKeyDef(
        key: KeyJson,
        subModeLabel: String = "",
        schemaId: String = "",
        subModeName: String = ""
    ): KeyDef? {
        // 旧版单槽划动（`swipe` / `altLabel` / `swipeLabel`）没有物理方向，历史上方向是由
        // 主题的「标点位置」推断的，于是改一次主题就会把用户按下滑配好的键整体翻成上滑，
        // 副标签也跟着跑到上方。这里的处理分两种：
        //
        // - **带方向线索**（有划动标签，或已有 swipeUp/swipeDown 任一侧）：把旧字段固定成
        //   方向字段（见 [legacySwipeTargetsUp]），方向从此只由配置本身决定；
        //   已经有明确方向字段时旧字段整体丢弃，避免旧动作从另一个方向漏出来。
        // - **不带任何线索**（无标签、无方向字段）：保持旧 `swipe` 槽位不动，运行时仍按
        //   「符号划动方向（符号隐藏时）」决定（默认「自动」= 上下都能触发）。这类键没有
        //   标签，用户看不到方向承诺，把它钉死到某一侧反而会悄悄丢掉另一个方向。
        val legacyLabel = if (key.type == "MacroKey") key.altLabel else key.swipeLabel
        val hasLegacyLabel = !legacyLabel.isNullOrEmpty()
        val hasDirectionalLabel = key.swipeUpLabel != null || key.swipeDownLabel != null
        val hasDirectionalMacro = key.swipeUp != null || key.swipeDown != null
        val pinLegacySwipe = hasLegacyLabel || hasDirectionalLabel || hasDirectionalMacro
        val legacyTargetsUp = legacySwipeTargetsUp(
            hasUpMacro = key.swipeUp != null,
            hasDownMacro = key.swipeDown != null,
            hasUpLabel = key.swipeUpLabel != null,
            hasDownLabel = key.swipeDownLabel != null
        )
        val directionalUpLabel = key.swipeUpLabel
            ?: legacyLabel?.takeIf { hasLegacyLabel && !hasDirectionalLabel && legacyTargetsUp }
        val directionalDownLabel = key.swipeDownLabel
            ?: legacyLabel?.takeIf { hasLegacyLabel && !hasDirectionalLabel && !legacyTargetsUp }
        val directionalUpMacro = key.swipeUp
            ?: key.swipe?.takeIf { pinLegacySwipe && !hasDirectionalMacro && legacyTargetsUp }
        val directionalDownMacro = key.swipeDown
            ?: key.swipe?.takeIf { pinLegacySwipe && !hasDirectionalMacro && !legacyTargetsUp }
        val directionalLegacyMacro = key.swipe?.takeIf { !pinLegacySwipe }

        val keyDef = when (key.type) {
            "AlphabetKey" -> AlphabetKey(
                character = key.main ?: "",
                punctuation = key.alt ?: "",
                // 空串代表「未设置」：字段缺失，或按方案分组但当前方案没有取值。
                // 是否为 null 决定「回落到主字符 + 保留 Shift」还是「原样显示」，所以这里
                // 必须用空串兜底——早先用 main 兜底，导致两者再也分不出来。
                displayText = resolveDisplayText(
                    key.displayText,
                    schemaId,
                    subModeLabel,
                    subModeName,
                    ""
                ).takeIf { it.isNotEmpty() },
                weight = key.weight,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                altTextColor = key.altTextColor,
                altTextColorMonet = key.altTextColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "CapsKey" -> CapsKey(
                swipeUp = directionalUpMacro,
                swipeDown = directionalDownMacro,
                swipeUpLabel = directionalUpLabel,
                swipeDownLabel = directionalDownLabel,
                swipe = directionalLegacyMacro,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "LayoutSwitchKey" -> LayoutSwitchKey(
                swipeUp = directionalUpMacro,
                swipeDown = directionalDownMacro,
                swipeUpLabel = directionalUpLabel,
                swipeDownLabel = directionalDownLabel,
                displayText = key.label ?: "?123",
                to = key.subLabel ?: "",
                swipe = directionalLegacyMacro,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "CommaKey" -> CommaKey(
                percentWidth = key.weight ?: 0.1f,
                variant = KeyDef.Appearance.Variant.Alternative,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "LanguageKey" -> LanguageKey(
                percentWidth = key.weight ?: 0.1f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "SpaceKey" -> SpaceKey(
                percentWidth = key.weight ?: 0f,
                swipe = key.swipe,
                swipeLabel = key.swipeLabel,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "SymbolKey" -> SymbolKey(
                swipeUp = directionalUpMacro,
                swipeDown = directionalDownMacro,
                swipeUpLabel = directionalUpLabel,
                swipeDownLabel = directionalDownLabel,
                symbol = key.label ?: ".",
                swipe = directionalLegacyMacro,
                percentWidth = key.weight ?: 0.1f,
                variant = KeyDef.Appearance.Variant.Alternative,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "ReturnKey" -> ReturnKey(
                swipeUp = directionalUpMacro,
                swipeDown = directionalDownMacro,
                swipeUpLabel = directionalUpLabel,
                swipeDownLabel = directionalDownLabel,
                swipe = directionalLegacyMacro,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "BackspaceKey" -> BackspaceKey(
                swipeUp = directionalUpMacro,
                swipeDown = directionalDownMacro,
                swipeUpLabel = directionalUpLabel,
                swipeDownLabel = directionalDownLabel,
                swipe = directionalLegacyMacro,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "NumPadKey" -> NumPadKey(
                displayText = key.label ?: "0",
                sym = key.sym ?: resolveKeysym(key.label) ?: FcitxKeyMapping.FcitxKey_KP_0,
                percentWidth = key.weight ?: 0.1f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "MiniSpaceKey" -> MiniSpaceKey(
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "PlaceholderKey" -> PlaceholderKey(
                displayText = key.main ?: "",
                altText = key.alt ?: "",
                percentWidth = key.weight ?: 0.1f,
                // 字段缺失 = 该类型的默认形态（完全空白、透明底）。只有显式写了 false
                // 才画底，那是"填了字符+自定义颜色"的装饰占位键。
                transparentBackground = key.transparent ?: true,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                altTextColor = key.altTextColor,
                altTextColorMonet = key.altTextColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "MacroKey" -> {
                // A MacroKey without a tap action cannot do anything. Skip the key instead of
                // throwing: the throw used to propagate out of BaseKeyboard.init and crash the
                // keyboard for a config the editor itself was able to write.
                val tap = key.tap ?: run {
                    Log.w(TAG, "Skipping MacroKey without 'tap' action: label=" + key.label)
                    return null
                }
                // label 与 displayText 分别保留：label 始终是标签文本，displayText 只在用户
                // 显式填写时非空，并优先于 label 参与渲染。空默认值让"未设置"可区分于
                // "设置了空串"，未设置时才会回落到 label（保留 Shift 大写行为）。
                val baseLabel = key.label ?: ""
                val resolvedDisplayText = resolveDisplayText(
                    key.displayText,
                    schemaId,
                    subModeLabel,
                    subModeName,
                    ""  // displayText 未命中任何 submode 条目时视为"未设置"，由 label 兜底
                ).takeIf { it.isNotEmpty() }
                MacroKey(
                    label = baseLabel,
                    displayText = resolvedDisplayText,
                    character = baseLabel.ifEmpty { resolvedDisplayText ?: "" },
                    swipeUpLabel = directionalUpLabel,
                    swipeDownLabel = directionalDownLabel,
                    longPressLabel = key.longPressLabel,
                    tap = tap,
                    swipeUp = directionalUpMacro,
                    swipeDown = directionalDownMacro,
                    swipe = directionalLegacyMacro,
                    longPress = key.longPress,
                    percentWidth = key.weight ?: 0.1f,
                    textColor = key.textColor,
                    textColorMonet = key.textColorMonet,
                    altTextColor = key.altTextColor,
                    altTextColorMonet = key.altTextColorMonet,
                    backgroundColor = key.backgroundColor,
                    backgroundColorMonet = key.backgroundColorMonet,
                    shadowColor = key.shadowColor,
                    shadowColorMonet = key.shadowColorMonet
                )
            }
            else -> SpaceKey() // Fallback
        }
        keyDef.rowHeightPercent = key.rowHeightPercent?.takeIf { it in 1f..100f }
        // 分界标记只在显式设置时写入：绝大多数键没有它，把 false 也写出去会让每个
        // 布局文件平白多出一整片 `"splitAfter": false`，QR 分享的载荷也跟着膨胀。
        if (key.splitAfter == true) keyDef.splitAfter = true
        key.composeOverride?.let { override ->
            val overrideDef = createKeyDef(
                override.copy(composeOverride = null, weight = null, rowHeightPercent = null),
                subModeLabel,
                schemaId,
                subModeName
            ) ?: return@let
            overrideDef.independentColor = override.independentColor ?: false
            keyDef.composeOverride = overrideDef
        }
        return keyDef
    }

    /**
     * 将内部数据结构转换为 JSON 格式用于保存。
     *
     * 支持子模式布局的嵌套结构：
     * ```json
     * {
     *   "rime": {
     *     "default": [...],
     *     "倉頡五代": [...]
     *   },
     *   "pinyin": [...]
     * }
     * ```
     *
     * @param entries 布局数据
     * @return JSON 对象
     */
    fun convertToSaveJson(
        entries: Map<String, List<List<Map<String, Any?>>>>,
        layoutHeightPercentOverrides: Map<String, Int> = emptyMap(),
        layoutHeightPercentOverridesLandscape: Map<String, Int> = emptyMap(),
        layoutAuxBarConfigs: Map<String, AuxBarConfig?> = emptyMap(),
        layoutAuxBarKeys: Map<String, List<Map<String, Any?>>> = emptyMap()
    ): JsonObject {
        fun buildMeta(overrideKey: String): JsonObject? {
            val portrait = layoutHeightPercentOverrides[overrideKey]?.takeIf { it in 10..90 }
            val landscape = layoutHeightPercentOverridesLandscape[overrideKey]?.takeIf { it in 10..90 }
            val auxBarConfig = layoutAuxBarConfigs[overrideKey]
            if (portrait == null && landscape == null && auxBarConfig == null) return null
            val meta = mutableMapOf<String, JsonElement>()
            portrait?.let { meta["keyboard_height_percent"] = JsonPrimitive(it) }
            landscape?.let { meta["keyboard_height_percent_landscape"] = JsonPrimitive(it) }
            auxBarConfig?.let { config ->
                val posStr = when (config.position) {
                    AuxBarPosition.Top -> "top"
                    AuxBarPosition.Bottom -> "bottom"
                    AuxBarPosition.Left -> "left"
                    AuxBarPosition.Right -> "right"
                    AuxBarPosition.AbovePreedit -> "above_preedit"
                }
                val auxBarObj = mutableMapOf<String, JsonElement>(
                    "position" to JsonPrimitive(posStr),
                    "size_percent" to JsonPrimitive(config.sizePercent)
                )
                layoutAuxBarKeys[overrideKey]?.takeIf { it.isNotEmpty() }?.let { keys ->
                    auxBarObj["keys"] = JsonArray(keys.map { keyMap ->
                        JsonObject(
                            keyMap
                                .filterValues { it != null }
                                .mapValues { (_, v) -> convertToJsonProperty(v) }
                        )
                    })
                }
                meta["aux_bar"] = JsonObject(auxBarObj)
            }
            return JsonObject(meta)
        }

        fun rowsOf(entryKey: String): JsonArray? = entries[entryKey]?.let { rows ->
            JsonArray(rows.map { row ->
                JsonArray(row.map { keyMap ->
                    val ordered = orderKeyFieldsForSave(keyMap)
                    JsonObject(
                        ordered
                            .filterValues { it != null }
                            .mapValues { (_, v) -> convertToJsonProperty(v) }
                    )
                })
            })
        }

        /**
         * 一个子布局（或布局本体）的 JSON 元素：普通排列 + 它自己的分体排列。
         *
         * 分体条目写在同一层里、`default` 的**旁边**（不是嵌套一层），因为读那一侧
         * （[org.fcitx.fcitx5.android.input.keyboard.LayoutVariantResolver]）就是这么找的：
         * `obj["default"]` 取普通排列，`obj["__variant__:split"]` 取分体排列。
         */
        fun subLayoutElement(docked: JsonArray?, split: JsonArray?, meta: JsonObject?): JsonElement? {
            if (docked == null && split == null && meta == null) return null
            if (split == null && meta == null && docked != null) return docked
            return JsonObject(
                mapOfNotNull(
                    meta?.let { "__meta__" to it },
                    docked?.let { "default" to it },
                    split?.let { toVariantSubModeLabel(LayoutVariant.Split.jsonKey!!) to it }
                )
            )
        }

        // 按「布局 → 子布局 → 排列」把压平在 entries 里的条目重新组织起来。
        // 认不出的键（结构畸形）按布局本体处理，至少不让内容消失。
        data class Bucket(
            val docked: MutableMap<String?, JsonArray> = mutableMapOf(),
            val split: MutableMap<String?, JsonArray> = mutableMapOf()
        )

        val buckets = LinkedHashMap<String, Bucket>()
        entries.keys.forEach { key ->
            val parsed = parseEntryKey(key)
            val layoutName = parsed?.layoutName ?: baseLayoutNameFromEntryKey(key)
            val subLayout = parsed?.subLayout
            // 认不出的键当作布局本体：它多半是"布局名里带冒号"的历史数据，
            // 归到本体比被静默丢弃安全。
            val effectiveSub = if (parsed == null) null else subLayout
            val rows = rowsOf(key) ?: return@forEach
            val bucket = buckets.getOrPut(layoutName) { Bucket() }
            if (parsed?.variant?.isSplit == true) {
                bucket.split[effectiveSub] = rows
            } else {
                bucket.docked.putIfAbsent(effectiveSub, rows)
            }
        }
        // 布局本体没有内容、却有子布局时，本体键仍要保留（否则该布局整体消失）。
        entries.keys.forEach { key ->
            if (!key.contains(':')) buckets.getOrPut(key) { Bucket() }
        }

        val layoutMap = mutableMapOf<String, JsonElement>()
        buckets.forEach { (layoutName, bucket) ->
            val bodyMeta = buildMeta(layoutName)
            val bodyDocked = bucket.docked[null]
            val bodySplit = bucket.split[null]
            val children = (bucket.docked.keys + bucket.split.keys)
                .filterNotNull()
                .distinct()
                .sorted()

            if (children.isEmpty() && bodySplit == null) {
                // 没有子布局、也没有分体排列：保持与加这个功能之前**完全一致**的形状
                // （平铺数组，或带高度覆盖时的 `{__meta__, default}`）。绝大多数布局文件
                // 都属于这一支，回归差异必须为零。
                val rows = bodyDocked ?: return@forEach
                layoutMap[layoutName] = if (bodyMeta == null) {
                    rows
                } else {
                    JsonObject(mapOf("__meta__" to bodyMeta, "default" to rows))
                }
                return@forEach
            }

            val layoutObject = mutableMapOf<String, JsonElement>()
            bodyMeta?.let { layoutObject["__meta__"] = it }
            bodyDocked?.let { layoutObject["default"] = it }
            bodySplit?.let {
                layoutObject[toVariantSubModeLabel(LayoutVariant.Split.jsonKey!!)] = it
            }
            children.forEach { child ->
                val childKey = "$layoutName:$child"
                // 子布局的高度/辅助栏覆盖记在它自己的键上（`rime:倉頡五代`），
                // 这就是"每个子布局都能单独设分体排列与键盘高度"的落点。
                val childMeta = buildMeta(childKey)
                subLayoutElement(
                    docked = bucket.docked[child],
                    split = bucket.split[child],
                    meta = childMeta
                )?.let { layoutObject[child] = it }
            }
            layoutMap[layoutName] = JsonObject(layoutObject.toSortedMap())
        }

        return JsonObject(layoutMap.toSortedMap())
    }

    /**
     * 递归转换任意值为 JsonElement。
     *
     * 转换规则：
     * - Map → JsonObject
     * - List → JsonArray
     * - String → JsonPrimitive
     * - Number → JsonPrimitive
     * - Boolean → JsonPrimitive
     * - null → JsonNull
     * - 其他 → JsonPrimitive(value.toString())
     *
     * @param value 要转换的值
     * @return JsonElement
     */
    fun convertToJsonProperty(value: Any?): JsonElement = when (value) {
        is JsonObject -> value
        is JsonArray -> value
        is Map<*, *> -> {
            val map = value.mapValues { (subKey, subValue) ->
                convertToJsonProperty(subValue)
            }
            JsonObject(map.mapKeys { it.key.toString() }.toMap())
        }
        is List<*> -> {
            val list = value.map { convertToJsonProperty(it) }
            JsonArray(list)
        }
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /**
     * Format layout json with readable indentation while keeping each key object on a single line.
     */
    fun formatJsonCompact(element: JsonElement): String {
        return formatJsonElement(element, 0)
    }

    private fun formatJsonElement(element: JsonElement, level: Int): String {
        return when (element) {
            is JsonObject -> formatJsonObject(element, level)
            is JsonArray -> formatJsonArray(element, level)
            else -> Json.encodeToString(JsonElement.serializer(), element)
        }
    }

    private fun formatJsonObject(obj: JsonObject, level: Int): String {
        if (obj.isEmpty()) return "{}"
        if ("type" in obj) {
            return formatJsonObjectInline(obj)
        }

        val indent = "  ".repeat(level)
        val childIndent = "  ".repeat(level + 1)
        val body = obj.entries.joinToString(",\n") { (key, value) ->
            val keyLiteral = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key))
            "$childIndent$keyLiteral: ${formatJsonElement(value, level + 1)}"
        }
        return "{\n$body\n$indent}"
    }

    private fun formatJsonArray(array: JsonArray, level: Int): String {
        if (array.isEmpty()) return "[]"
        val allScalar = array.all { it is JsonPrimitive || it is JsonNull }
        if (allScalar) {
            return Json.encodeToString(JsonElement.serializer(), array)
        }

        val indent = "  ".repeat(level)
        val childIndent = "  ".repeat(level + 1)
        val body = array.joinToString(",\n") { child ->
            "$childIndent${formatJsonElement(child, level + 1)}"
        }
        return "[\n$body\n$indent]"
    }

    private fun formatJsonObjectInline(obj: JsonObject): String {
        val body = obj.entries.joinToString(",") { (key, value) ->
            val keyLiteral = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key))
            "$keyLiteral:${formatJsonElementInline(value)}"
        }
        return "{$body}"
    }

    private fun formatJsonElementInline(element: JsonElement): String {
        return when (element) {
            is JsonObject -> formatJsonObjectInline(element)
            is JsonArray -> {
                val body = element.joinToString(",") { child -> formatJsonElementInline(child) }
                "[$body]"
            }
            else -> Json.encodeToString(JsonElement.serializer(), element)
        }
    }

    /** 只保留非 null 项，构建 map。用于「有没有这份内容」本身就是信息的场景。 */
    private fun <K, V : Any> mapOfNotNull(vararg pairs: Pair<K, V>?): Map<K, V> =
        pairs.filterNotNull().toMap()

    private fun orderKeyFieldsForSave(keyMap: Map<String, Any?>): LinkedHashMap<String, Any?> {        val ordered = LinkedHashMap<String, Any?>()
        KEY_FIELD_ORDER.forEach { key ->
            if (keyMap.containsKey(key)) {
                ordered[key] = if (key == "sym" && keyMap["type"] == "NumPadKey") {
                    when (val sym = keyMap[key]) {
                        is Number -> keysymToName(sym.toInt())
                        else -> sym
                    }
                } else {
                    keyMap[key]
                }
            }
        }
        keyMap.forEach { (key, value) ->
            if (!ordered.containsKey(key)) {
                ordered[key] = value
            }
        }
        return ordered
    }
}
