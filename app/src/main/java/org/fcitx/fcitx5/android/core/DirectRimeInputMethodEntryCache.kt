/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Reuses the immutable frontend entry while its actually displayed fields stay equal. */
internal class DirectRimeInputMethodEntryCache {
    private var entry: InputMethodEntry? = null
    private var ascii: Boolean? = null
    private var schema = ""
    private var schemaName = ""
    private var displayName = ""

    fun get(ascii: Boolean, schema: String, schemaName: String, displayName: String): InputMethodEntry {
        val cached = entry
        if (cached != null && this.ascii == ascii && this.displayName == displayName &&
            (ascii || (this.schema == schema && this.schemaName == schemaName))) return cached

        this.ascii = ascii
        this.schema = schema
        this.schemaName = schemaName
        this.displayName = displayName
        val subMode = if (ascii) {
            InputMethodSubMode("Latin Mode", "abc", "fcitx_rime_latin")
        } else {
            InputMethodSubMode(schemaName, schemaName, "fcitx-rime:$schema")
        }
        return InputMethodEntry(
            "rime", displayName, "fcitx-rime", "中州韵", "㞢", "zh", "rime", true, subMode
        ).also { entry = it }
    }
}
