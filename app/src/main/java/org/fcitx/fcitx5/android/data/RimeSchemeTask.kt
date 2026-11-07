/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.net.Uri
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** A saved recipe. A running update uses an immutable snapshot of the selected recipes. */
@Serializable
internal data class RimeSchemeTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val source: String = "",
    val fileName: String = "",
    val unpack: Boolean = true,
    val stripRoot: Boolean = true,
    val destinations: List<String> = listOf(""),
    val excludes: String = DEFAULT_EXCLUDES,
    val selected: Boolean = true
) {
    val remote: Boolean get() = source.toHttpUrlOrNull() != null
    fun validated(): RimeSchemeTask {
        require(UUID.fromString(id).toString() == id) { "无效的任务标识" }
        require(name.isNotBlank() && name.length <= 120 && name.none(Char::isISOControl)) { "请填写任务名称（最多120字）" }
        require(source.isNotBlank() && source.length <= 8192 && source.none { it.isISOControl() }) { "请填写有效的下载链接或本地文件" }
        val url = source.toHttpUrlOrNull()
        if (url != null) {
            require(url.username.isEmpty() && url.password.isEmpty()) { "链接中不能包含账号密码" }
        } else {
            val uri = Uri.parse(source)
            require(source.startsWith('/') || uri.scheme in setOf("content", "file")) { "仅支持 HTTP(S) 链接、本地路径或通过文件选择器授权的文件" }
            if (uri.scheme == "file") require(uri.host.isNullOrEmpty() && uri.path?.startsWith('/') == true && uri.query == null && uri.fragment == null)
            if (uri.scheme == "content") require(!uri.authority.isNullOrEmpty())
        }
        require(fileName.isNotBlank() && fileName.toByteArray().size <= 240 && '/' !in fileName &&
            RimeSchemePayload.resource(fileName)) { "请填写有效的文件名，不能使用用户词典或运行数据的名称" }
        require(destinations.size in 1..16 && destinations.distinct().size == destinations.size) { "目标子目录不能重复，最多16个" }
        destinations.forEach { if (it.isNotEmpty()) require(RimeSchemePayload.resource(it)) { "目标必须是当前 Rime 工作目录内的相对子目录" } }
        rules()
        return this
    }

    fun rules(): List<Regex> {
        require(excludes.toByteArray().size <= 16 * 1024) { "排除规则过长" }
        val lines = excludes.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }
        require(lines.size <= 128 && lines.all { it.length <= 512 }) { "排除规则最多128行，每行最多512字" }
        return lines.mapIndexed { i, rule ->
            try { Regex(rule) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("第${i + 1}行不是有效的正则表达式") }
        }
    }

    companion object {
        const val DEFAULT_EXCLUDES = "(?i)(^|/).*\\.custom\\.yaml$\n(?i)(^|/)custom_phrase\\.(txt|dict\\.yaml)$\n(?i)(^|/)sequence[^/]*\\.txt$"
        fun suggestedFileName(source: String): String = runCatching {
            val url = source.toHttpUrlOrNull()
            val uri = Uri.parse(source)
            (if (source.startsWith('/')) java.io.File(source).name else url?.pathSegments?.lastOrNull()
                ?: uri.lastPathSegment.takeIf { uri.scheme in setOf("file", "content") }).orEmpty()
                .takeIf { it.isNotBlank() && '/' !in it && ':' !in it && it.length <= 240 }.orEmpty()
        }.getOrDefault("")
    }
}
