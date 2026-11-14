/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

/**
 * Foxy 的 App 命令形式：App ID 就是命令，前缀 `js.`（`llm_assistant` → `js.llm_assistant`）。
 *
 * 宿主用它解析布局里的 `{"type":"app","command":"js.rewrite"}` 节点。Foxy 要求 App ID 全局唯一，
 * 本宿主只保证插件内唯一，所以这里按「恰好一个已注册 App 匹配」解析：没有匹配或多于一个都算
 * 解析失败，由调用方提示用户改用编辑按钮里的精确绑定，而不是随便挑一个插件执行。
 */
internal object JsAppCommand {
    const val PREFIX = "js."

    data class Target(val pluginId: String, val appId: String)

    /** `js.<appId>` → `<appId>`；不是合法命令时返回 null。 */
    fun parse(command: String): String? {
        if (!command.startsWith(PREFIX)) return null
        return command.removePrefix(PREFIX).takeIf { JsVoicePluginMeta.isValidId(it) }
    }

    fun resolve(command: String, available: List<Pair<String, JsPluginApp>>): Target? {
        val appId = parse(command) ?: return null
        return available.filter { it.second.id == appId }.singleOrNull()
            ?.let { Target(it.first, it.second.id) }
    }
}
