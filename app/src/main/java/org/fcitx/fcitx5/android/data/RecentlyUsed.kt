/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import timber.log.Timber
import java.util.ArrayDeque

class RecentlyUsed(val type: String, val limit: Int) {

    companion object {
        // for backwords compatibility only
        const val DIR_NAME = "recently_used"
        const val PREFERENCE_NAME = "picker_recently_used"
    }

    private val sharedPreferences = FcitxApplication.getInstance().directBootAwareContext
        .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    private val maxItems = limit.coerceAtLeast(0)
    private val map = LinkedHashMap<String, Boolean>(maxItems)

    init {
        val loadedItems = migrate() ?: load()
        loadedItems.forEach { map[it] = true }
        val trimmed = trimToLimit()
        if (trimmed || loadedItems.size != map.size) {
            save()
        }
    }

    val items: List<String> get() = map.keys.reversed()

    private fun load(): List<String> {
        val rawValue = sharedPreferences.getString(type, "") ?: ""
        if (rawValue.isEmpty()) {
            return emptyList()
        }
        return try {
            Json.decodeFromString<List<String>>(rawValue)
        } catch (_: Exception) {
            sharedPreferences.edit {
                remove(type)
            }
            emptyList()
        }
    }

    private fun save() {
        sharedPreferences.edit {
            putString(type, Json.encodeToString<List<String>>(map.keys.toList()))
        }
    }

    fun insert(item: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            map.putLast(item, true)
        } else {
            // LinkedHashMap's encounter order is not affected when `put` an existing key
            if (map.containsKey(item)) {
                map.remove(item)
            }
            map.put(item, true)
        }
        trimToLimit()
        save()
    }

    /** Remove the oldest entries until the configured recent list bound is satisfied. */
    private fun trimToLimit(): Boolean {
        var trimmed = false
        while (map.size > maxItems) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
            trimmed = true
        }
        return trimmed
    }

    fun migrate(): List<String>? {
        val dir = FcitxApplication.getInstance().directBootAwareContext.filesDir.resolve(DIR_NAME)
        val file = dir.resolve(type)
        if (file.exists()) {
            try {
                val lines = if (maxItems == 0) {
                    emptyList()
                } else {
                    file.useLines { source ->
                        val recent = ArrayDeque<String>()
                        source.forEach { line ->
                            if (recent.size == maxItems) recent.removeFirst()
                            recent.addLast(line)
                        }
                        recent.toList()
                    }
                }
                // save to sharedPreferences before deleting old file
                sharedPreferences.edit {
                    putString(type, Json.encodeToString<List<String>>(lines))
                }
                file.delete()
                if (dir.list()?.isEmpty() == true) {
                    dir.delete()
                }
                return lines
            } catch (e: Exception) {
                Timber.w("Failed to migrate RecentlyUsed(type=$type)")
                Timber.w(e)
                return null
            }
        }
        return null
    }
}
