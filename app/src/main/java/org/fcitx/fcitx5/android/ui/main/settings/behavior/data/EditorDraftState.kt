/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import android.content.Context
import android.os.Bundle
import java.io.File

/** Keep large editor state out of the process-wide saved-state Binder transaction. */
internal class EditorDraftState(context: Context, namespace: String) {
    private val store = LayoutDraftStore(File(context.noBackupFilesDir, "editor-drafts/$namespace"))

    fun read(state: Bundle?): String? {
        store.adopt(state?.getString(FILE))
        runCatching { store.pruneStale(LayoutDraftStore.MAX_AGE_MS) }
        if (state == null) return null
        return state.getString(INLINE) ?: store.read()
    }

    fun save(state: Bundle, json: String) {
        val name = store.write(json)
        when {
            name != null -> state.putString(FILE, name)
            LayoutDraftStore.fitsInBundle(json) -> state.putString(INLINE, json)
            else -> store.existingSnapshotName()?.let { state.putString(FILE, it) }
        }
    }

    fun retain(state: Bundle) { store.existingSnapshotName()?.let { state.putString(FILE, it) } }
    fun delete() = store.delete()

    private companion object {
        const val FILE = "editor_draft_file"
        const val INLINE = "editor_draft_inline"
    }
}
