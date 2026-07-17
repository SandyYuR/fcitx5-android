/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec.TRANSFER_TYPE_ICON_THEME

object IconThemeQrTransferCodec {
    const val SCHEMA = "f5a-icon-theme-qr-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class IconThemeSharePayload(
        val schema: String = SCHEMA,
        val iconTheme: IconTheme
    )

    fun encodeIconThemeToChunks(theme: IconTheme): LayoutQrTransferCodec.ChunkBundle {
        val payload = IconThemeSharePayload(iconTheme = theme)
        val rawJson = json.encodeToString(payload)
        return LayoutQrTransferCodec.encodeJsonToChunks(rawJson, transferType = TRANSFER_TYPE_ICON_THEME)
    }

    fun decodeIconThemeFromChunks(chunks: List<String>): IconTheme {
        val raw = LayoutQrTransferCodec.decodeChunksToJson(chunks)
        return decodeIconThemeFromJson(raw)
    }

    fun decodeIconThemeFromJson(raw: String): IconTheme {
        val payload = json.decodeFromString(IconThemeSharePayload.serializer(), raw)
        check(payload.schema == SCHEMA) { "Unsupported icon theme share schema" }
        return payload.iconTheme
    }

    fun detectSchema(raw: String): String? =
        runCatching {
            json.parseToJsonElement(raw).jsonObject["schema"]?.jsonPrimitive?.content
        }.getOrNull()
}
