/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MirrorRule(
    val id: String,
    val name: String,
    val pattern: String,
    val replacement: String
)

@Serializable
data class HostsConfig(
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("mapping") val mapping: Map<String, List<String>> = emptyMap(),
    @SerialName("updated_at") val updatedAt: Long = 0L,
)

@Serializable
data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?
)

@Serializable
data class ReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val releaseBody: String,
    val assets: List<ReleaseAsset>,
    val publishedAt: Long? = null // Timestamp of release publish time (milliseconds)
)
