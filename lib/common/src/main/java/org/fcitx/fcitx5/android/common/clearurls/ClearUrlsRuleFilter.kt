package org.fcitx.fcitx5.android.common.clearurls

import android.net.Uri
import org.json.JSONObject
import java.net.URLDecoder

class ClearUrlsRuleFilter(rawRules: String) {
    private data class Provider(
        val urlPattern: Regex,
        val rules: List<Regex> = emptyList(),
        val rawRules: List<Regex> = emptyList(),
        val exceptions: List<Regex> = emptyList(),
        val redirections: List<Regex> = emptyList()
    )

    private val catalog = parseProviders(rawRules)
    private val urlPattern = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun transform(text: String): String {
        // Clipboard entries may be whole documents. Do not run the provider regex catalog
        // over large text just because its first line starts with a URL.
        if (text.length > MAX_URL_CHARS || !urlPattern.matchesAt(text, 0)) return text
        val seen = hashSetOf(text)
        var result = text
        repeat(MAX_REDIRECTS) {
            val next = transformOnce(result)
            if (next == result) return result
            if (!seen.add(next)) return text
            result = next
        }
        // Several pipeline stages may filter the same URL. Never publish a partial rewrite
        // that a later stage would rewrite again: preserve the input on budget exhaustion.
        return text
    }

    private fun transformOnce(text: String): String {
        var result = text
        for (provider in catalog) {
            if (!provider.urlPattern.containsMatchIn(result)) continue
            if (provider.exceptions.any { it.containsMatchIn(result) }) continue
            provider.redirections.forEach { redirection ->
                redirection.matchAt(result, 0)?.groupValues?.getOrNull(1)?.let { target ->
                    decodeUrl(target)?.takeIf { it != result }?.let { return it }
                }
            }
            provider.rawRules.forEach { rule ->
                result = rule.replace(result, "")
            }
            val uri = Uri.parse(result)
            result = uri.buildUpon()
                .encodedQuery(filterParams(uri.encodedQuery, provider.rules))
                .encodedFragment(filterParams(uri.encodedFragment, provider.rules))
                .toString()
        }
        return result
    }

    private fun parseProviders(rawRules: String): List<Provider> {
        val providers = JSONObject(rawRules)
            .optJSONObject("providers")
            ?: return emptyList()
        val result = mutableListOf<Provider>()
        val keys = providers.keys()
        while (keys.hasNext()) {
            val provider = providers.optJSONObject(keys.next()) ?: continue
            val pattern = provider.optString("urlPattern")
            if (pattern.isEmpty()) continue
            result += Provider(
                urlPattern = pattern.toRegexSafe(),
                rules = provider.optRegexList("rules") + provider.optRegexList("referralMarketing"),
                rawRules = provider.optRegexList("rawRules"),
                exceptions = provider.optRegexList("exceptions"),
                redirections = provider.optRegexList("redirections")
            )
        }
        return result
    }

    private fun JSONObject.optRegexList(key: String): List<Regex> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                array.optString(i)?.takeIf { it.isNotEmpty() }?.let { add(it.toRegexSafe()) }
            }
        }
    }

    private fun String.toRegexSafe(): Regex =
        Regex(this, RegexOption.IGNORE_CASE)

    private fun decodeUrl(str: String): String? {
        var result = str
        repeat(MAX_REDIRECTS) {
            // Stop as soon as the wrapper's URL is decoded. Further decoding would turn an
            // encoded '&' inside a target query value into another query parameter.
            if (urlPattern.matchesAt(result, 0)) return result
            val next = Uri.decode(result)
            if (next == result) return null
            result = next
        }
        return result.takeIf { urlPattern.matchesAt(it, 0) }
    }

    private fun filterParams(params: String?, rules: List<Regex>): String? {
        if (params.isNullOrEmpty()) return params
        // Split the encoded document on real separators and decode names only. Preserve
        // untouched values, duplicates, flags, '=' and original escaping byte for byte.
        val kept = params.split('&').filter { part ->
            val rawName = part.substringBefore('=')
            val name = try { URLDecoder.decode(rawName, "UTF-8") }
                catch (_: IllegalArgumentException) { rawName }
            rules.none { it.matches(name) }
        }
        return if (kept.isEmpty()) null else kept.joinToString("&")
    }

    private companion object {
        const val MAX_URL_CHARS = 64 * 1024
        const val MAX_REDIRECTS = 8
    }
}
