/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import okhttp3.HttpUrl

/** Configured endpoints add exact hostnames, not blanket permission to access arbitrary URLs. */
internal class JsAppEndpointPolicy(declaredHosts: Set<String>, settings: Map<String, String>) {
    private val declaredHosts = declaredHosts.map { it.trimEnd('.') }.toSet()
    private val configuredHttpHosts = configuredHosts(settings, "https")
    private val configuredWebSocketHosts = configuredHosts(settings, "wss")

    /** foxy.websocket.authorize 只拿到主机名，没有 URL 可校验，所以单独给一个判断。 */
    fun allows(host: String): Boolean = allowsHost(
        host,
        declaredHosts + configuredHttpHosts + configuredWebSocketHosts,
    )

    /** HTTP APIs remain HTTPS-only even when the same host is also configured for WebSocket. */
    fun checkUrl(url: String): HttpUrl {
        require(url.substringBefore("://", "").equals("https", ignoreCase = true)) { "HTTPS is required" }
        return checkSecureUrl(url, "Invalid HTTPS endpoint", declaredHosts + configuredHttpHosts)
    }

    /** App WebSocket APIs require WSS while sharing the same exact-host eligibility rules. */
    fun checkWebSocketUrl(url: String): HttpUrl {
        require(url.substringBefore("://", "").equals("wss", ignoreCase = true)) { "WSS is required" }
        return checkSecureUrl(
            url,
            "Invalid WSS endpoint",
            declaredHosts + configuredHttpHosts + configuredWebSocketHosts,
        )
    }

    private fun checkSecureUrl(url: String, invalidMessage: String, allowedHosts: Set<String>): HttpUrl {
        val parsed = requireNotNull(JsVoiceHelpers.secureUrl(url)) { invalidMessage }
        require(allowsHost(parsed.host, allowedHosts)) {
            "Endpoint must be declared or configured in plugin settings"
        }
        return parsed
    }

    private fun configuredHosts(settings: Map<String, String>, scheme: String): Set<String> =
        settings.values.mapNotNull { value ->
            value.trim().takeIf { it.substringBefore("://", "").equals(scheme, ignoreCase = true) }
                ?.let(JsVoiceHelpers::secureUrl)?.host?.trimEnd('.')
        }.toSet()

    private fun allowsHost(host: String, allowedHosts: Set<String>): Boolean =
        allowedHosts.any { it.equals(host.trimEnd('.'), ignoreCase = true) }
}
