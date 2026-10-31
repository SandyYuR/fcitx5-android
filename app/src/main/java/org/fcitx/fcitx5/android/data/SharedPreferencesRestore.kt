/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

/** Android treats an AtomicFile .bak as authoritative, even if the main XML exists. */
internal object SharedPreferencesRestore {
    internal fun fileLimit(heapBytes: Long) = minOf(16L * 1024 * 1024, (heapBytes / 16).coerceAtLeast(1024 * 1024L))
    private val valueTags = setOf("string", "set", "int", "long", "float", "boolean", "null")
    fun normalize(directory: File) {
        if (!directory.exists()) return
        if (!directory.isDirectory) throw IOException("Invalid preferences directory")
        val files = directory.listFiles() ?: throw IOException("Cannot inspect preferences backup")
        for (backup in files.filter { it.name.endsWith(".xml.bak") }) {
            validate(backup)
            val target = File(directory, backup.name.removeSuffix(".bak"))
            org.fcitx.fcitx5.android.utils.FileUtil.replaceAtomically(backup, target)
        }
        directory.listFiles()?.filter { it.name.endsWith(".xml") }?.forEach(::validate)
    }

    private fun validate(file: File) {
        if (!file.isFile || file.length() > fileLimit(Runtime.getRuntime().maxMemory())) throw IOException("Invalid or oversized preferences XML")
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var roots = 0
            var nodes = 0
            var closed = false
            val stack = ArrayList<String>(3)
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> {
                        if (!closed || parser.depth != 0) throw IOException("Preferences XML is incomplete")
                        break
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.depth == 1 && parser.name == "map") closed = true
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                    XmlPullParser.DOCDECL -> throw IOException("Preferences XML must not contain a document declaration")
                    XmlPullParser.START_TAG -> {
                        if (++nodes > 100_000 || parser.depth > 3) throw IOException("Preferences XML exceeds structure limit")
                        val name = parser.name
                        when (parser.depth) {
                            1 -> if (++roots != 1 || name != "map") throw IOException("Preferences XML must contain one map")
                            2 -> {
                                if (stack.lastOrNull() != "map" || name !in valueTags)
                                    throw IOException("Unsupported preference value type")
                                val value = parser.getAttributeValue(null, "value")
                                val valid = when (name) {
                                    "int" -> value?.toIntOrNull() != null
                                    "long" -> value?.toLongOrNull() != null
                                    "float" -> value?.toFloatOrNull() != null
                                    "boolean" -> value.equals("true", true) || value.equals("false", true)
                                    else -> true
                                }
                                if (!valid) throw IOException("Invalid $name preference value")
                            }
                            3 -> if (stack.lastOrNull() != "set" || (name != "string" && name != "null"))
                                throw IOException("Preference sets must contain strings")
                        }
                        stack += name
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (stack.lastOrNull() != "string" && !parser.isWhitespace)
                        throw IOException("Unexpected preference XML content")
                    XmlPullParser.ENTITY_REF -> if (stack.lastOrNull() != "string" || parser.text == null)
                        throw IOException("Invalid preference XML entity")
                }
            }
            if (roots != 1) throw IOException("Preferences XML is empty")
        }
    }
}
