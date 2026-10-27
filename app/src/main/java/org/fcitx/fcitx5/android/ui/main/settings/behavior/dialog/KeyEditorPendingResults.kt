/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.os.Bundle
import android.os.Parcelable
import androidx.activity.result.ActivityResult
import androidx.annotation.MainThread
import androidx.core.os.BundleCompat
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity

/** Serializable results, rather than closures tied to a form that may never finish loading. */
@MainThread
internal class KeyEditorPendingResults {
    enum class Kind { MACRO, COLOR, COMPOSE }
    private val pending = linkedMapOf<Kind, Parcelable>()

    fun offer(kind: Kind, result: Parcelable) { pending[kind] = result }
    fun takeAll(): List<Pair<Kind, Parcelable>> = pending.toList().also { pending.clear() }

    fun save(out: Bundle) {
        out.putBundle(STATE, Bundle().apply {
            putStringArrayList("order", ArrayList(pending.keys.map { it.name }))
            pending.forEach { (kind, result) -> putParcelable(kind.name, result) }
        })
    }

    fun restore(state: Bundle?) {
        val saved = state?.getBundle(STATE) ?: return
        saved.classLoader = ThemeColorEditorActivity.EditorResult::class.java.classLoader
        pending.clear()
        saved.getStringArrayList("order").orEmpty().forEach { name ->
            val kind = Kind.entries.firstOrNull { it.name == name } ?: return@forEach
            val result = if (kind == Kind.COLOR) {
                BundleCompat.getParcelable(saved, name, ThemeColorEditorActivity.EditorResult::class.java)
            } else BundleCompat.getParcelable(saved, name, ActivityResult::class.java)
            if (result != null) pending[kind] = result
        }
    }

    companion object { private const val STATE = "pending_key_form_results" }
}
