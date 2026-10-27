/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

internal object SyncServerAddress {
    fun normalize(value: String, defaultScheme: String = "https"): String {
        val raw = value.trim()
        if (raw.isEmpty()) throw IOException("Server address is empty")
        val withScheme = when {
            raw.startsWith("wss://", true) -> "https://" + raw.substring(6)
            raw.startsWith("ws://", true) -> "http://" + raw.substring(5)
            raw.contains("://") -> raw
            else -> "$defaultScheme://$raw"
        }
        val url = withScheme.toHttpUrlOrNull() ?: throw IOException("Invalid HTTP(S) server address")
        return url.newBuilder().fragment(null).build().toString().trimEnd('/')
    }
}

/** Run before redirect requests write a body; an HTTPS origin cannot silently downgrade. */
internal object SyncTransportPolicy : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().request().url.isHttps && !chain.request().url.isHttps) {
            throw IOException("Refusing an HTTPS to HTTP redirect")
        }
        return chain.proceed(chain.request())
    }
}
