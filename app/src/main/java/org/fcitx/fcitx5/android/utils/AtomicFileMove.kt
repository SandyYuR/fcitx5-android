/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/** Optional public NIO API, kept out of signatures so API 23 can load this class. */
internal object AtomicFileMove {
    private val nio: ((File, File) -> Unit)? by lazy {
        try {
            val path = Class.forName("java.nio.file.Path")
            val copyOption = Class.forName("java.nio.file.CopyOption")
            val options = java.lang.reflect.Array.newInstance(copyOption, 2)
            val constants = Class.forName("java.nio.file.StandardCopyOption").enumConstants!!
            java.lang.reflect.Array.set(options, 0, constants.first { it.toString() == "ATOMIC_MOVE" })
            java.lang.reflect.Array.set(options, 1, constants.first { it.toString() == "REPLACE_EXISTING" })
            val toPath = File::class.java.getMethod("toPath")
            val move = Class.forName("java.nio.file.Files").getMethod("move", path, path, options.javaClass)
            val action: (File, File) -> Unit = { source, destination ->
                try { move.invoke(null, toPath.invoke(source), toPath.invoke(destination), options); Unit }
                catch (error: InvocationTargetException) { throw error.targetException }
            }
            action
        } catch (_: ClassNotFoundException) { null }
        catch (_: NoSuchMethodException) { null }
    }

    fun replace(source: File, destination: File) {
        val move = nio
        if (move != null) move(source, destination)
        else if (!source.renameTo(destination)) {
            throw IOException("Cannot replace '${destination.path}'; original file preserved")
        }
        // An unsupported atomic move is an error. Never delete the original and retry.
    }
}
