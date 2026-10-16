/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Application key syntax. X11 keysyms are data, independent of any input engine. */
internal object KeyCodec {
    private class Names {
        val byName = HashMap<String, Int>()
        val bySym = HashMap<Int, String>()
        val byUnicode = HashMap<Int, Int>()
        val unicodeBySym = HashMap<Int, Int>()
        val displayBySym = HashMap<Int, String>()
        init {
            checkNotNull(KeyCodec::class.java.getResourceAsStream("/keysym-compat.tsv")).bufferedReader().useLines { lines ->
                lines.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
                    val fields = line.split('\t')
                    val sym = fields[1].toInt(16)
                    byName[fields[0]] = sym
                    if (fields[2] == "1") bySym[sym] = fields[0]
                    fields.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { displayBySym[sym] = it }
                    fields.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt(16)?.let { cp ->
                        unicodeBySym[sym] = cp
                        if (sym < (byUnicode[cp] ?: Int.MAX_VALUE)) byUnicode[cp] = sym
                    }
                }
            }
        }
    }
    private val names by lazy { Names() }
    private val modifiers = listOf("Control" to 4, "Alt" to 8, "Shift" to 1, "Super" to 64, "Hyper" to 32)
    private val legacy = listOf("CTRL_" to 4, "ALT_" to 8, "SHIFT_" to 1, "SUPER_" to 64, "HYPER_" to 32)
    private val cache = object : LinkedHashMap<String, Key>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Key>?) = size > 256
    }

    fun parse(raw: String): Key = synchronized(cache) { cache[raw] ?: decode(raw).also { cache[raw] = it } }

    private fun decode(raw: String): Key {
        if (raw.isEmpty()) return Key.None
        var states = 0
        var offset = 0
        for ((prefix, bit) in modifiers.map { "${it.first}+" to it.second } + legacy) {
            val found = raw.indexOf(prefix)
            if (found >= 0) { states = states or bit; offset = maxOf(offset, found + prefix.length) }
        }
        val name = raw.substring(offset)
        if (name.startsWith('<') && name.endsWith('>')) {
            val code = name.substring(1, name.length - 1).toUIntOrNull()?.takeIf { it > 0u }
            val label = code?.let { create('a'.code, states).portableString.dropLast(1) + "<$it>" }.orEmpty()
            return Key(0, states, label, label)
        }
        // Common layout characters avoid even loading the name catalogue.
        val sym = when {
            name.length == 1 && name[0].code in 0x20..0x7e -> name[0].code
            else -> (names.byName[name] ?: name.takeIf { it.startsWith("XF86") }?.let { names.byName[it.removePrefix("XF86")] })
                ?: if (name.isNotEmpty() && name.codePointCount(0, name.length) == 1)
                fromUnicode(name.codePointAt(0)) else 0
        }
        return create(sym, states)
    }

    private fun fromUnicode(cp: Int): Int = when {
        cp in 0x20..0x7e || cp in 0xa0..0xff -> cp
        cp <= 0 || cp > 0x10ffff || cp in 0xd800..0xdfff -> 0
        else -> names.byUnicode[cp] ?: (0x01000000 or cp)
    }

    fun create(sym: Int, states: Int): Key {
        if (sym == 0) return Key(0, states, "", "")
        val normalized = if (sym == 0xfe20) 0xff09 else sym // ISO_Left_Tab serializes as Tab.
        val cp = when {
            normalized in 0x20..0x7e || normalized in 0xa0..0xff -> normalized
            normalized and 0xff000000.toInt() == 0x01000000 -> normalized and 0x00ffffff
            else -> names.unicodeBySym[normalized] ?: 0
        }
        val character = if (cp in 0x20..0x10ffff && cp !in 0xd800..0xdfff) String(Character.toChars(cp)) else ""
        val ordinary = normalized in 'a'.code..'z'.code || normalized in 'A'.code..'Z'.code || normalized in '0'.code..'9'.code
        val portable = if (ordinary)
            normalized.toChar().toString() else names.bySym[normalized] ?: character
        if (portable.isEmpty()) return Key(sym, states, "", "")
        fun prefix(mask: Int) = modifiers.filter { (_, bit) ->
            mask and when (bit) { 64 -> 64 or (1 shl 26); 32 -> 32 or (1 shl 27); else -> bit } != 0
        }.joinToString("") { "${it.first}+" }
        val ownModifier = when (sym) {
            0xffe1, 0xffe2 -> 1
            0xffe3, 0xffe4 -> 4
            0xffe7, 0xffe8, 0xffe9, 0xffea -> 8
            0xffeb, 0xffec, 0xffed, 0xffee -> 64
            else -> 0
        }
        val display = if (ordinary) character else names.displayBySym[normalized] ?: character.ifEmpty { portable }
        return Key(sym, states, prefix(states) + portable, prefix(states and ownModifier.inv()) + display)
    }
}
