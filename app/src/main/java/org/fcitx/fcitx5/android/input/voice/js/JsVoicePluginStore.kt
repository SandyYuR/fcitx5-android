/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.Context
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

/**
 * 用户导入的 JS 语音插件的宿主侧存储。
 *
 * 两处写入必须同步推进，否则会出现「设置里有、注册表里没有」的孤儿插件：
 * - 元信息进 SharedPreferences（一个插件一个键，便于单条增删）；
 * - 脚本正文进 `filesDir/js-voice/<id>.js`。
 *
 * [list] 会在返回前剔除脚本文件已丢失的记录，因此任何一次读都是「能跑的那批」。
 */
internal object JsVoicePluginStore {

    private const val PREFS_NAME = "js_voice_imported_plugins"
    private const val KEY_PREFIX = "p:"
    private const val DIR_NAME = "js-voice"

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun directory(): File = File(appContext.filesDir, DIR_NAME).apply {
        if (!isDirectory) runCatching { mkdirs() }
    }

    /**
     * 脚本文件路径。id 已由 [JsVoicePluginMeta.isValidId] 限定为字母数字与 `._-` 且首字符为字母数字，
     * 所以不存在 `..` 之类的穿越写法。
     */
    fun scriptFile(id: String): File = File(directory(), id + ".js")

    /** 全部已导入插件；脚本文件缺失的记录会被清掉而不是留在列表里点了报错。 */
    fun list(): List<JsVoicePluginRecord> {
        val all = prefs().all
        val records = mutableListOf<JsVoicePluginRecord>()
        val stale = mutableListOf<String>()
        all.forEach { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is String) return@forEach
            val id = key.removePrefix(KEY_PREFIX)
            val record = JsVoicePluginRecord.decode(value)
            if (record == null || record.meta.id != id || !scriptFile(id).isFile) {
                stale += key
                return@forEach
            }
            records += record
        }
        if (stale.isNotEmpty()) {
            prefs().edit().apply { stale.forEach { remove(it) } }.apply()
        }
        return records.sortedBy { it.importedAt }
    }

    fun recordOf(id: String): JsVoicePluginRecord? =
        prefs().getString(KEY_PREFIX + id, null)?.let(JsVoicePluginRecord::decode)

    fun importedIds(): Set<String> = list().map { it.meta.id }.toSet()

    fun readScript(id: String): String? =
        scriptFile(id).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    /**
     * 保存（或覆盖）一个插件。
     *
     * 覆盖时**只替换脚本与元信息**，不动 [JsVoiceSettingsStore] 里已经存下的设置与密钥：
     * 那是按 provider id 分键的，同名插件升级后用户不必重新填一遍 key。
     */
    fun save(record: JsVoicePluginRecord, script: String): Boolean {
        val id = record.meta.id
        if (!JsVoicePluginMeta.isValidId(id)) return false
        val file = scriptFile(id)
        val written = runCatching { file.writeText(script) }.isSuccess
        if (!written) return false
        return prefs().edit().putString(KEY_PREFIX + id, record.encode()).commit()
    }

    /** 删除插件记录与脚本文件。已填设置与密钥保留在 [JsVoiceSettingsStore] 里，重装同名插件可继续用。 */
    fun delete(id: String) {
        prefs().edit().remove(KEY_PREFIX + id).apply()
        runCatching { scriptFile(id).delete() }
    }
}
