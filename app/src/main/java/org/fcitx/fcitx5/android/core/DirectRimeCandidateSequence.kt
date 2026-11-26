/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/**
 * Schema-driven candidate actions for Wanxiang `super_sequence.lua` and validated EVO editors.
 *
 * Native discovers the active processor/editor's declared keys, highlights the long-pressed
 * candidate of the same snapshot and delivers that key. Persistence and sync remain owned by
 * the engine. Missing bindings hide only their own action; EVO auto_delete clears the selected
 * candidate's automatic learning, independently of manual ordering. The backend presents it in
 * place of the equivalent legacy forget entry when explicitly declared.
 */
internal object DirectRimeCandidateSequence {
    const val FORGET_ID = 0
    private const val EVO_BINDINGS_TAG = "evo-editor-bindings-v1"

    /** Resolved slots: up, down, reset, pin; EVO appends auto_delete and never supplies pin. */
    enum class Operation(val id: Int, val slot: Int, val label: String) {
        Pin(1, 3, "置顶"),
        Forward(2, 0, "前移一位"),
        Backward(3, 1, "后移一位"),
        Reset(4, 2, "重置排序"),
        AutoDelete(5, 4, "重置自动调频"),
    }

    val forgetAction = CandidateAction(FORGET_ID, "忘记此词", false, "", false, false)

    /** Schema ID `wanxiang` or any ID starting with it (wanxiang_t9, wanxiang_english, ...). */
    fun isWanxiangSchema(schema: String): Boolean = schema.startsWith("wanxiang")

    fun operation(id: Int): Operation? = Operation.entries.firstOrNull { it.id == id }

    /** Lua keeps its four slots; EVO sends every raw binding so aliases cannot hide collisions. */
    fun resolveKeys(raw: Array<String>, evo: Boolean): Array<String> {
        if (raw.firstOrNull() != EVO_BINDINGS_TAG) return if (raw.size == 4) raw else emptyArray()
        if (!evo || raw.size % 2 != 1) return emptyArray()
        val bindings = (1 until raw.size step 2).mapNotNull { index ->
            parseEvoKey(raw[index])?.let { Binding(raw[index], raw[index + 1], it) }
        }
        val counts = bindings.groupingBy { it.key.event }.eachCount()
        val result = Array(5) { "" }
        for (binding in bindings) {
            if (!binding.key.deliverable || counts[binding.key.event] != 1) continue
            val slot = when (binding.action) {
                "manual_up" -> 0
                "manual_down" -> 1
                "manual_delete" -> 2
                "auto_delete" -> 4
                else -> continue
            }
            if (result[slot].isEmpty()) result[slot] = binding.raw
        }
        return if (result.any { it.isNotEmpty() }) result else emptyArray()
    }

    fun actions(keys: Array<String>, evo: Boolean = false): List<CandidateAction> =
        Operation.entries.filter { keyArgument(keys, it.id, evo) != null }
            .map {
                val label = if (evo && keys.size == 5 && it == Operation.Reset) "重置手动排序" else it.label
                CandidateAction(it.id, label, false, "", false, false)
            }

    /** Native action argument for [id], or null when the schema's key cannot be delivered. */
    fun keyArgument(keys: Array<String>, id: Int, evo: Boolean = false): String? {
        val operation = operation(id) ?: return null
        if (keys.size == 4) return keys.getOrNull(operation.slot)?.let(::encode)
        if (!evo || keys.size != 5 || operation == Operation.Pin) return null
        val key = parseEvoKey(keys[operation.slot])?.takeIf { it.deliverable } ?: return null
        return "${key.event.sym} ${key.event.mask}"
    }

    private data class Event(val sym: Int, val mask: Int)
    private data class EvoKey(val event: Event, val deliverable: Boolean)
    private data class Binding(val raw: String, val action: String, val key: EvoKey)

    // A conservative subset of Rime's exact X11 names. The compatibility catalogue also contains
    // frontend-only aliases (ENTER, PGUP, ...); those can block a collision, but cannot be sent.
    private val evoKeyNames = setOf(
        "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand",
        "apostrophe", "quoteright", "parenleft", "parenright", "asterisk", "plus", "comma",
        "minus", "period", "slash", "colon", "semicolon", "less", "equal", "greater",
        "question", "at", "bracketleft", "backslash", "bracketright", "asciicircum",
        "underscore", "grave", "quoteleft", "braceleft", "bar", "braceright", "asciitilde",
        "BackSpace", "Tab", "Linefeed", "Clear", "Return", "Pause", "Scroll_Lock", "Sys_Req",
        "Escape", "Delete", "Home", "Left", "Up", "Right", "Down", "Page_Up", "Prior",
        "Page_Down", "Next", "End", "Begin", "Select", "Print", "Execute", "Insert",
        "Undo", "Redo", "Menu", "Find", "Cancel", "Help", "Break", "ISO_Left_Tab",
    ) + (1..35).map { "F$it" }

    /** Rime consumes modifiers from the start; only Shift/Control/Alt survive rimeMask unchanged. */
    private fun parseEvoKey(raw: String): EvoKey? {
        if (raw.length == 1 && raw[0].code in 0x20..0x7e) return EvoKey(Event(raw[0].code, 0), true)
        var start = 0
        var mask = 0
        while (true) {
            val end = raw.indexOf('+', start)
            if (end < 0) break
            mask = mask or when (raw.substring(start, end)) {
                "Shift" -> 1
                "Control" -> 4
                "Alt" -> 8
                else -> return null
            }
            start = end + 1
        }
        val name = raw.substring(start)
        val sym = KeyCodec.exactKeysym(name)?.takeIf { it > 0 && it != 0xffffff } ?: return null
        val ordinary = name.length == 1 && (name[0] in 'a'..'z' || name[0] in 'A'..'Z' || name[0] in '0'..'9')
        return EvoKey(Event(sym, mask), ordinary || name in evoKeyNames)
    }

    /** Legacy Lua key syntax ("Control+p") to "<keysym> <frontend mask>". */
    internal fun encode(raw: String): String? {
        val key = Key.parse(raw.trim())
        return if (key.sym > 0) "${key.sym} ${key.states}" else null
    }
}
