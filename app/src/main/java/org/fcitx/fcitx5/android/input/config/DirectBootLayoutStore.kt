/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.IOException

/** Only the selected layout is mirrored. All calls run on the sync worker, never in a getter. */
internal class DirectBootLayoutStore(private val destination: File) {
    fun syncFrom(source: File?) {
        // An unavailable external volume is different from an explicitly deleted layout.
        if (source == null) return
        if (!source.isFile) {
            clear()
            return
        }
        val content = source.readText()
        val valid = try {
            UserJsonConfigStore.parser.parseToJsonElement(
                UserJsonConfigStore.stripLineComments(content)
            ) is JsonObject
        } catch (_: SerializationException) {
            false
        }
        if (!valid) {
            clear()
            return
        }
        // Imports/restores can retain an older (or identical) timestamp. Compare contents,
        // and publish with the same fsync + atomic replacement used by the layout editor.
        if (destination.isFile && destination.readText() == content) return
        FileUtil.writeAtomically(destination, content)
    }

    fun clear() {
        if (destination.exists() && !destination.delete()) {
            throw IOException("Cannot remove Direct Boot keyboard layout")
        }
    }
}
