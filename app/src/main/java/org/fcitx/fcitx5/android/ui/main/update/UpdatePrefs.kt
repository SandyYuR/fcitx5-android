/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import timber.log.Timber

object UpdatePrefs {
    private const val KEY_MIRROR_RULES = "update_mirror_rules_v1"
    private const val KEY_SELECTED_MIRROR = "update_selected_mirror_v1"
    private const val KEY_HOSTS_CONFIG = "update_hosts_config_v1"
    private const val KEY_RELEASE_CACHE = "update_release_cache_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadMirrorRules(context: Context): MutableList<MirrorRule> {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sp.getString(KEY_MIRROR_RULES, null).orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return try {
            json.decodeFromString<List<MirrorRule>>(raw).toMutableList()
        } catch (t: Throwable) {
            Timber.w(t, "Failed to parse mirror rules preference")
            mutableListOf()
        }
    }

    fun saveMirrorRules(context: Context, rules: List<MirrorRule>) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit {
            putString(KEY_MIRROR_RULES, json.encodeToString(rules))
        }
    }

    fun newMirrorRule(name: String, pattern: String, replacement: String): MirrorRule {
        return MirrorRule(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            pattern = pattern.trim(),
            replacement = replacement.trim()
        )
    }

    fun loadSelectedMirrorId(context: Context): String? {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getString(KEY_SELECTED_MIRROR, null)
    }

    fun saveSelectedMirrorId(context: Context, id: String?) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit {
            putString(KEY_SELECTED_MIRROR, id)
        }
    }

    fun loadHostsConfig(context: Context): HostsConfig {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sp.getString(KEY_HOSTS_CONFIG, null).orEmpty()
        if (raw.isBlank()) {
            return HostsConfig()
        }
        return try {
            json.decodeFromString<HostsConfig>(raw)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to parse hosts config preference")
            HostsConfig()
        }
    }

    fun saveHostsConfig(context: Context, config: HostsConfig) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit {
            putString(KEY_HOSTS_CONFIG, json.encodeToString(config))
        }
    }

    fun loadReleaseCache(context: Context): ReleaseInfo? {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sp.getString(KEY_RELEASE_CACHE, null).orEmpty()
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString<ReleaseInfo>(raw)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to parse release cache preference")
            null
        }
    }

    fun saveReleaseCache(context: Context, release: ReleaseInfo) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit {
            putString(KEY_RELEASE_CACHE, json.encodeToString(release))
        }
    }
}
