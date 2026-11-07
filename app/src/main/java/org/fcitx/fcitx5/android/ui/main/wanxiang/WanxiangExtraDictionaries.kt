/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.content.Context
import okhttp3.Request
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.utils.FileUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object WanxiangExtraDictionaries {
    data class Entry(val id: String, val name: String)
    fun parse(text: String): List<Entry> {
        require(text.toByteArray().size <= 256 * 1024)
        val data = JSONArray(text)
        require(data.length() in 1..200) { "附加词库目录为空或过大" }
        val result = (0 until data.length()).map { index ->
            val item = data.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name")
            require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")) && name.isNotBlank() && name.length <= 80)
            Entry(id, name)
        }
        require(result.distinctBy { it.id }.size == result.size)
        return result
    }
    private fun cache(context: Context) = File(context.noBackupFilesDir, "wanxiang-extra-catalog.json")
    fun cached(context: Context): List<Entry> = runCatching {
        val file = cache(context); require(file.length() <= 256 * 1024); parse(file.readText())
    }.getOrDefault(emptyList())
    fun refresh(context: Context): List<Entry> {
        val url = "https://raw.githubusercontent.com/amzxyz/rime-wanxiang-data/wanxiang/extra-dicts.json"
        val text = WanxiangGithub.client(context).newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "附加词库目录 HTTP ${it.code}" }
            WanxiangGithub.bounded(it.body!!.byteStream(), 256 * 1024)
        }
        return parse(text).also { FileUtil.writeAtomically(cache(context), text) }
    }
    private fun hash(file: File, checkActive: () -> Unit): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun prepareAuxiliary(job: File, payload: File, user: File, checkActive: () -> Unit = {}): Map<String, String> {
        val staged = WanxiangPayload.child(payload.canonicalFile, "dicts/zi.pro.dict.yaml")
        val source = if (staged.isFile) staged else WanxiangPayload.child(user.canonicalFile, "dicts/zi.pro.dict.yaml")
        require(source.isFile && source.length() <= 128L * 1024 * 1024) { "请先下载当前 Pro 辅助码的词库（缺少 dicts/zi.pro.dict.yaml）" }
        val digest = hash(source, checkActive)
        val map = WanxiangDictionaryConverter.auxiliary(source, checkActive)
        require(digest == hash(source, checkActive)) { "辅助码字表在读取时已变化，请重新更新" }
        FileUtil.writeAtomically(WanxiangPayload.child(job.canonicalFile, "auxiliary.json"), JSONObject().put("staged", staged.isFile).put("sha256", digest).toString())
        return map
    }
    fun verifyAuxiliary(job: File, payload: File, user: File, checkActive: () -> Unit = {}) {
        val metadata = WanxiangPayload.child(job.canonicalFile, "auxiliary.json")
        if (!metadata.exists()) return
        require(metadata.isFile && metadata.length() <= 4096)
        val data = JSONObject(metadata.readText())
        val base = if (data.getBoolean("staged")) payload else user
        val file = WanxiangPayload.child(base.canonicalFile, "dicts/zi.pro.dict.yaml")
        require(file.isFile && file.length() <= 128L * 1024 * 1024 && hash(file, checkActive) == data.getString("sha256")) {
            "辅助码字表已变化或受保护，请重新更新附加词库"
        }
    }
    fun stage(download: File, payload: File, asset: WanxiangPackage, scheme: WanxiangSelection.Scheme,
              auxiliary: Map<String, String> = emptyMap(), checkActive: () -> Unit = {}) {
        val suffix = when (scheme) { WanxiangSelection.Scheme.PRO -> ".pro"; WanxiangSelection.Scheme.LITE -> ".lite"; else -> "" }
        val name = asset.name.removeSuffix(".dict.yaml") + suffix
        val target = WanxiangPayload.child(payload.canonicalFile, "dicts/$name.dict.yaml")
        WanxiangDictionaryConverter.convert(download, target, name, scheme, auxiliary, checkActive)
        require(payload.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= WanxiangPayload.MAX_BYTES)
    }
}
