/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.utils.Const
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import timber.log.Timber

object UpdateRepository {
    private const val RELEASE_CACHE_TTL_MS = 2 * 60 * 1000L

    private val json = Json {
        ignoreUnknownKeys = true
    }
    @Volatile
    private var cachedReleaseInfo: ReleaseInfo? = null
    @Volatile
    private var cachedReleaseAt: Long = 0L

    private fun invalidateReleaseCache() {
        cachedReleaseInfo = null
        cachedReleaseAt = 0L
    }

    suspend fun fetchLatestRelease(context: Context, forceRefresh: Boolean = false): ReleaseInfo {
        if (!forceRefresh) {
            val cached = cachedReleaseInfo
            val age = System.currentTimeMillis() - cachedReleaseAt
            if (cached != null && age in 0 until RELEASE_CACHE_TTL_MS) {
                return cached
            }
        }
        return withContext(Dispatchers.IO) {
            val hosts = UpdatePrefs.loadHostsConfig(context)
            val url = URL(Const.githubRepo)
            val owner = url.path.trim('/').split('/').getOrNull(0)
                ?: throw IOException("Invalid GitHub repo owner")
            val repo = url.path.trim('/').split('/').getOrNull(1)
                ?: throw IOException("Invalid GitHub repo name")
            val apiUrl = "https://api.github.com/repos/$owner/$repo/releases?per_page=1"
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "fcitx5-android-update")
                .build()
            val mapping = if (hosts.enabled) hosts.mapping else emptyMap()
            try {
                clientWithHosts(mapping).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("GitHub API request failed: ${response.code}")
                    }
                    val body = response.body?.string()
                        ?: throw IOException("GitHub API response body is empty")
                    val obj = json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
                        ?: throw IOException("No release found")
                    val assets = obj["assets"]?.jsonArray.orEmpty().mapNotNull { item ->
                        val o = item.jsonObject
                        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val link = o["browser_download_url"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L
                        val digest = o["digest"]?.jsonPrimitive?.contentOrNull
                        val sha256 = digest
                            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                            ?.lowercase()
                        ReleaseAsset(
                            name = name,
                            browserDownloadUrl = link,
                            sizeBytes = size,
                            sha256 = sha256
                        )
                    }
                    val publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull?.let {
                        // Parse ISO 8601 time to milliseconds
                        java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli()
                    }
                    val release = ReleaseInfo(
                        tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        releaseName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        releaseBody = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        assets = assets,
                        publishedAt = publishedAt
                    )
                    cachedReleaseInfo = release
                    cachedReleaseAt = System.currentTimeMillis()
                    UpdatePrefs.saveReleaseCache(context, release)
                    return@withContext release
                }
            } catch (t: Throwable) {
                val cached = UpdatePrefs.loadReleaseCache(context)
                if (cached != null) {
                    Timber.w(t, "Failed to fetch release, falling back to cached release info")
                    cachedReleaseInfo = cached
                    cachedReleaseAt = System.currentTimeMillis()
                    return@withContext cached
                }
                throw t
            }
        }
    }

    suspend fun updateHostsMapping(context: Context): Int = withContext(Dispatchers.IO) {
        val config = UpdatePrefs.loadHostsConfig(context)
        val request = Request.Builder()
            .url(config.sourceUrl)
            .addHeader("User-Agent", "fcitx5-android-update")
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Hosts source request failed: ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Hosts source response body is empty")
            val mapping = parseHostsMapping(body)
            UpdatePrefs.saveHostsConfig(
                context,
                config.copy(
                    mapping = mapping,
                    updatedAt = System.currentTimeMillis()
                )
            )
            invalidateReleaseCache()
            return@withContext mapping.size
        }
    }

    fun saveHostsSource(context: Context, sourceUrl: String) {
        val config = UpdatePrefs.loadHostsConfig(context)
        UpdatePrefs.saveHostsConfig(
            context,
            config.copy(sourceUrl = sourceUrl.trim())
        )
        invalidateReleaseCache()
    }

    fun saveHostsEnabled(context: Context, enabled: Boolean) {
        val config = UpdatePrefs.loadHostsConfig(context)
        UpdatePrefs.saveHostsConfig(
            context,
            config.copy(enabled = enabled)
        )
        invalidateReleaseCache()
    }

    fun applyMirror(rawUrl: String, mirror: MirrorRule?): String {
        if (mirror == null) return rawUrl
        return Regex(mirror.pattern).replace(rawUrl, mirror.replacement)
    }

    fun isHostsEnabled(context: Context): Boolean {
        return UpdatePrefs.loadHostsConfig(context).enabled
    }

    fun httpClientWithConfiguredHosts(context: Context): OkHttpClient {
        val hosts = UpdatePrefs.loadHostsConfig(context)
        val mapping = if (hosts.enabled) hosts.mapping else emptyMap()
        return clientWithHosts(mapping)
    }

    /**
     * Add publishedAtMs parameter for comparison.
     * If tag version is not comparable, try fallbackVersionSource first, then fallback to
     * publish time and local build time (ms).
     */
    fun isNewerVersion(
        latestTag: String,
        currentVersion: String,
        publishedAtMs: Long? = null,
        buildTimeMs: Long? = null,
        fallbackVersionSource: String? = null
    ): Boolean {
        val latest = extractVersionNumbers(latestTag).ifEmpty {
            extractVersionNumbers(fallbackVersionSource.orEmpty())
        }
        val current = extractVersionNumbers(currentVersion)
        if (latest.isEmpty() || current.isEmpty()) {
            // Version numbers not comparable, fallback to timestamp
            if (publishedAtMs != null && buildTimeMs != null) {
                return publishedAtMs > buildTimeMs
            }
            return false
        }
        val maxSize = maxOf(latest.size, current.size)
        for (i in 0 until maxSize) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun extractVersionNumbers(value: String): List<Int> {
        // Only take the first numeric version segment (e.g. 0.1.2-349-gxxxx), ignore suffixes (e.g. arm64-v8a)
        val versionPart = Regex("""(\d+\.\d+\.\d+(?:-\d+)?(?:-g[0-9a-fA-F]+)?)""").find(value)?.value
            ?: value
        return Regex("""\d+""").findAll(versionPart).map {
            it.value.toIntOrNull() ?: 0
        }.toList()
    }

    private fun parseHostsMapping(content: String): Map<String, List<String>> {
        val map = linkedMapOf<String, MutableList<String>>()
        content.lineSequence().forEach { line ->
            val stripped = line.substringBefore('#').trim()
            if (stripped.isBlank()) return@forEach
            val parts = stripped.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size < 2) return@forEach
            val ip = parts[0]
            parts.drop(1).forEach { host ->
                val list = map.getOrPut(host) { mutableListOf() }
                if (ip !in list) list += ip
            }
        }
        return map
    }

    private fun clientWithHosts(mapping: Map<String, List<String>>): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(UpdateHostsDns(mapping))
            .build()
    }

    private class UpdateHostsDns(
        private val mapping: Map<String, List<String>>
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val configured = mapping[hostname].orEmpty()
            if (configured.isEmpty()) {
                return Dns.SYSTEM.lookup(hostname)
            }
            return configured.map { ip -> InetAddress.getByName(ip) }
        }
    }
}
