/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import java.util.concurrent.ConcurrentHashMap

// In-process registry for voice input providers that live inside the IME
// itself (e.g. a future built-in echo / test provider). External plugin
// providers are discovered via PackageManager queries; see
// VoiceInputProviderManager.listProviders.
object VoiceInputProviderRegistry {
    data class Entry(
        val id: String,
        val label: CharSequence,
        val packageName: String,
        val provider: IVoiceInputProvider,
    )

    private val providers = ConcurrentHashMap<String, Entry>()

    fun register(entry: Entry) {
        providers[entry.id] = entry
    }

    fun unregister(id: String): Entry? = providers.remove(id)

    fun get(id: String): Entry? = providers[id]

    fun all(): List<Entry> = providers.values.toList()

    fun clear() {
        providers.clear()
    }
}
