/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.util.UUID

/** Publish a definition last; neither a failed write nor process death replaces its old images. */
internal object ThemeAssetTransaction {
    data class Assets(val cropped: File, val source: File)

    fun <T> publish(root: File, cropped: File, source: File?, definition: (Assets) -> T): T {
        require(cropped.isFile && cropped.length() > 0) { "The cropped background is missing" }
        val original = source ?: cropped
        require(original.isFile && original.length() > 0) { "The source background is missing" }
        val base = root.canonicalFile.also { check(it.isDirectory || it.mkdirs()) }
        val directory = File(base, "assets-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Cannot create background staging directory" }
        val files = Assets(File(directory, "cropped.png"), File(directory, "source.${original.extension.ifEmpty { "img" }}"))
        var published = false
        try {
            FileUtil.writeAtomically(files.cropped) { output -> cropped.inputStream().use { it.copyTo(output) } }
            FileUtil.writeAtomically(files.source) { output -> original.inputStream().use { it.copyTo(output) } }
            return definition(files).also { published = true }
        } finally {
            if (!published) {
                // Only the two private files from this attempt can be removed.
                files.cropped.delete()
                files.source.delete()
                directory.delete()
            }
        }
    }
}
