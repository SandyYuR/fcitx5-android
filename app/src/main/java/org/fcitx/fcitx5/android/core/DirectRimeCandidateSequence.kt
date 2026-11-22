/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/**
 * Candidate menu entries for schema-driven manual ordering (Wanxiang `super_sequence.lua`).
 *
 * The Lua processor reorders the highlighted candidate when it receives the schema's
 * `super_sequence/{up,down,reset,pin}` keys. Native highlights the long-pressed candidate of
 * the same snapshot and then delivers that key, so ordering, persistence and sync remain
 * owned by the schema. Only Wanxiang schemas ([isWanxiangSchema]) that also load such a
 * processor show the entries; every other schema keeps the original menu.
 */
internal object DirectRimeCandidateSequence {
    const val FORGET_ID = 0

    /** [slot] follows the order returned by native `sequenceKeys()`: up, down, reset, pin. */
    enum class Operation(val id: Int, val slot: Int, val label: String) {
        Pin(1, 3, "置顶"),
        Forward(2, 0, "前移一位"),
        Backward(3, 1, "后移一位"),
        Reset(4, 2, "重置排序"),
    }

    private const val SLOTS = 4

    val forgetAction = CandidateAction(FORGET_ID, "忘记此词", false, "", false, false)

    /** Schema ID `wanxiang` or any ID starting with it (wanxiang_t9, wanxiang_english, ...). */
    fun isWanxiangSchema(schema: String): Boolean = schema.startsWith("wanxiang")

    fun operation(id: Int): Operation? = Operation.entries.firstOrNull { it.id == id }

    fun actions(keys: Array<String>): List<CandidateAction> =
        if (keys.size != SLOTS) emptyList()
        else Operation.entries.filter { encode(keys[it.slot]) != null }
            .map { CandidateAction(it.id, it.label, false, "", false, false) }

    /** Native action argument for [id], or null when the schema's key cannot be delivered. */
    fun keyArgument(keys: Array<String>, id: Int): String? {
        val operation = operation(id) ?: return null
        return if (keys.size != SLOTS) null else encode(keys[operation.slot])
    }

    /** Rime key syntax ("Control+p") to "<keysym> <frontend mask>". */
    internal fun encode(raw: String): String? {
        val key = Key.parse(raw.trim())
        return if (key.sym > 0) "${key.sym} ${key.states}" else null
    }
}
