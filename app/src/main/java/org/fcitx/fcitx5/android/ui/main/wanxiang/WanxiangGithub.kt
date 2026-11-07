/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.clipboardsync.network.SyncTransportPolicy
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceSecretCrypto
import org.fcitx.fcitx5.android.ui.main.update.UpdateRepository
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Credentials stay in encrypted preferences and are sent only to the author's GitHub API. */
internal object WanxiangGithub {
    private fun preferences(context: Context) = context.getSharedPreferences("wanxiang-github-secrets", Context.MODE_PRIVATE)
    fun configured(context: Context) = preferences(context).contains("token")
    fun token(context: Context): String = preferences(context).getString("token", null)?.let {
        JsVoiceSecretCrypto.decrypt(it) ?: error("GitHub Token 无法解密，请重新配置")
    }.orEmpty()
    fun normalize(value: String): String {
        val clean = value.trim().removeSurrounding("`").trim().replace(Regex("(?i)^(?:authorization:\\s*)?(?:bearer|token)\\s+"), "")
        require(clean.isEmpty() || clean.length <= 255 && clean.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }) { "GitHub Token 格式不正确，请粘贴 Token 本体" }
        return clean
    }
    fun save(context: Context, value: String) {
        val clean = normalize(value)
        val prefs = preferences(context)
        if (clean.isEmpty()) prefs.edit().remove("token").apply()
        else {
            val encrypted = JsVoiceSecretCrypto.encrypt(clean) ?: error("无法安全保存 GitHub Token，请检查设备密钥存储")
            check(prefs.edit().putString("token", encrypted).commit()) { "无法保存 GitHub Token" }
        }
    }
    fun apiRequest(request: Request, token: String): Request {
        val url = request.url
        val path = url.pathSegments
        val allowed = url.isHttps && url.host == "api.github.com" && path.size >= 3 && path[0] == "repos" &&
            path[1].equals("amzxyz", true) && path[2].lowercase() in setOf("rime-wanxiang", "rime-wanxiang-data", "rime-lmdg")
        return request.newBuilder().removeHeader("Authorization").apply {
            if (allowed && token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
    }
    fun client(context: Context): OkHttpClient = UpdateRepository.httpClientWithConfiguredHosts(context).newBuilder()
        .callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            // Avoid accessing Keystore for ordinary downloads and redirects.
            val secret = if (request.url.host == "api.github.com") token(context) else ""
            chain.proceed(apiRequest(request, secret))
        }.addNetworkInterceptor(SyncTransportPolicy).build()

    fun bounded(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        input.use { while (true) { val n = it.read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "服务器返回的数据过大" }; output.write(buffer, 0, n) } }
        return output.toByteArray().toString(Charsets.UTF_8)
    }
    private fun metadata(client: OkHttpClient, url: String): Pair<JSONObject, String?> {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "fcitx5-android-wanxiang").build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { when (response.code) {
                401 -> "GitHub Token 无效或已过期"
                403, 429 -> "GitHub API 配额已耗尽或访问受限，请配置有效 Token 或稍后重试"
                else -> "GitHub API HTTP ${response.code}"
            } }
            JSONObject(bounded(response.body!!.byteStream(), 1024 * 1024)) to response.header("X-RateLimit-Remaining")
        }
    }
    fun check(context: Context): String {
        val (data, quota) = metadata(client(context), "https://api.github.com/repos/amzxyz/rime-wanxiang/releases/latest")
        return listOfNotNull(data.optString("tag_name").takeIf { it.isNotBlank() }, quota?.let { "API: $it" }).joinToString(" · ")
    }
    fun downloadUrl(client: OkHttpClient, asset: WanxiangPackage): String {
        if (!asset.url.startsWith("https://github.com/amzxyz/")) return asset.url
        val repo = if (asset.kind == WanxiangPackage.Kind.MODEL) "RIME-LMDG" else "rime-wanxiang"
        val release = when {
            asset.kind == WanxiangPackage.Kind.MODEL -> "tags/LTS"
            "/latest/" in asset.url -> "latest"
            else -> "tags/dict-nightly"
        }
        // Public release downloads remain usable when the metadata API is rate-limited.
        return runCatching {
            val (data, _) = metadata(client, "https://api.github.com/repos/amzxyz/$repo/releases/$release")
            val assets = data.getJSONArray("assets")
            (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name") == asset.name }
                .getString("browser_download_url").also { require(it.startsWith("https://github.com/amzxyz/$repo/releases/download/")) }
        }.getOrDefault(asset.url)
    }
}
