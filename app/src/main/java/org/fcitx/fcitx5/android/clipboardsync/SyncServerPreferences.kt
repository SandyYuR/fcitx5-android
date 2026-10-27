/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.clipboardsync.network.SyncServerAddress

/** Make existing implicit HTTP choices explicit once, before changing new-address defaults. */
internal object SyncServerPreferences {
    private const val VERSION = "sync_server_scheme_version"
    private val profiles = listOf("syncclipboard", "oneclip", "clipcascade", "custom")
    private val legacyDefaults = mapOf(
        "syncclipboard" to "http://192.168.10.45:5033",
        "oneclip" to "http://192.168.10.45:8899",
        "clipcascade" to "http://192.168.10.45:8080"
    )
    private val addressKeys = listOf("server_address") + profiles.map { "server_address_$it" }

    @Synchronized fun migrate(prefs: SharedPreferences) {
        if (runCatching { prefs.getInt(VERSION, 0) }.getOrDefault(0) >= 1) return
        val snapshot = prefs.all
        val hadConfiguration = snapshot.keys.any { it in addressKeys || it == "server_profile_type" ||
            it == "username" || it == "password" || it == "quick_sync" }
        val editor = prefs.edit()
        val migrated = addressKeys.associateWith { key ->
            val value = (snapshot[key] as? String)?.trim().orEmpty()
            if (value.isNotEmpty() && !value.contains("://")) {
                runCatching { SyncServerAddress.normalize(value, "http") }.getOrDefault(value).also {
                    editor.putString(key, it)
                }
            } else value
        }
        if (hadConfiguration) {
            val current = migrated.getValue("server_address")
            val previousProfile = snapshot["server_profile_type"] as? String
            val profile = previousProfile?.takeIf { it in profiles }
                ?: (if (!previousProfile.isNullOrBlank()) "custom" else null)
                ?: legacyDefaults.entries.firstOrNull { it.value == current.trimEnd('/') }?.key
                ?: if (current.isEmpty()) "syncclipboard" else "custom"
            editor.putString("server_profile_type", profile)
            if (current.isEmpty()) {
                // Preserve only the previously selected implicit endpoint. Unconfigured
                // alternatives must not silently contact the old hard-coded LAN machine.
                val selected = migrated["server_address_$profile"].orEmpty().ifEmpty { legacyDefaults[profile].orEmpty() }
                if (selected.isNotEmpty()) {
                    editor.putString("server_address", selected)
                    editor.putString("server_address_$profile", selected)
                }
            }
        }
        editor.putInt(VERSION, 1).apply()
    }

    fun hasConfiguredCurrent(prefs: SharedPreferences): Boolean {
        val current = runCatching { prefs.getString("server_address", "").orEmpty() }.getOrDefault("")
        val profile = runCatching { prefs.getString("server_profile_type", "syncclipboard") }.getOrNull()
        val address = current.ifBlank {
            if (profile in profiles) runCatching { prefs.getString("server_address_$profile", "").orEmpty() }.getOrDefault("")
            else ""
        }
        return address.isNotBlank() && runCatching { SyncServerAddress.normalize(address) }.isSuccess
    }
}
