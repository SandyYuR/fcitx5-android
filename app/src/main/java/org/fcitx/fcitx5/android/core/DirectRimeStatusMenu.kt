/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Pure status-menu encoding and decoding for the direct Rime frontend. */
internal object DirectRimeStatusMenu {
    const val ROOT_ID = 1
    const val SEPARATOR_ID = 9
    const val MODE_ID = 10
    const val DEPLOY_ID = 11
    const val SYNC_ID = 12

    private const val SCHEMA_ID_BASE = 100
    private const val OPTION_ID_BASE = 1000

    data class State(
        val schema: String,
        val schemaName: String,
        val ascii: Boolean,
    )

    data class Labels(
        val asciiMode: String,
        val chineseMode: String,
    )

    data class OptionChange(val name: String, val enabled: Boolean)

    sealed interface OwnerCommand {
        data object ToggleMode : OwnerCommand
        data class SelectSchema(val schema: String) : OwnerCommand
        data class SetOptions(val changes: List<OptionChange>) : OwnerCommand
    }

    fun build(
        state: State,
        schemas: List<Pair<String, String>>,
        optionRows: List<List<String>>,
        labels: Labels,
    ): Array<Action> {
        val targetMode = if (state.ascii) labels.chineseMode else labels.asciiMode
        val menu = mutableListOf(action(MODE_ID, "ascii_mode", targetMode, state.ascii))
        schemas.forEachIndexed { index, (id, name) ->
            menu += action(SCHEMA_ID_BASE + index, id, name, id == state.schema)
        }
        menu += action(SEPARATOR_ID, "separator", separator = true)

        val options = buildList {
            val groups = mutableSetOf<String>()
            optionRows.forEachIndexed { index, row ->
                val group = row[3]
                if (group.isEmpty()) {
                    add(action(OPTION_ID_BASE + index, row[0], row[1]))
                } else if (groups.add(group)) {
                    val members = optionRows.withIndex().filter { it.value[3] == group }
                    val selected = members.firstOrNull { it.value[2] == "1" } ?: members.first()
                    val children = members.map { (memberIndex, member) ->
                        action(OPTION_ID_BASE + memberIndex, member[0], member[1], member[2] == "1")
                    }.toTypedArray()
                    add(action(-OPTION_ID_BASE - index, "select-${row[0]}", selected.value[1], menu = children))
                }
            }
        }

        menu += action(DEPLOY_ID, "deploy", "重新部署")
        menu += action(SYNC_ID, "sync", "同步用户词典")
        val label = if (state.ascii) labels.asciiMode else state.schemaName.ifEmpty { "Rime" }
        return arrayOf(action(ROOT_ID, "rime", label, menu = menu.toTypedArray()), *options.toTypedArray())
    }

    /** Decode commands which must still be executed synchronously on the native owner. */
    fun resolveOwnerCommand(
        id: Int,
        schemas: List<Pair<String, String>>,
        optionRows: List<List<String>>,
    ): OwnerCommand? {
        if (id == MODE_ID) return OwnerCommand.ToggleMode

        val schemaIndex = schemaIndex(id, schemas.size)
        if (schemaIndex != null) return OwnerCommand.SelectSchema(schemas[schemaIndex].first)

        val optionIndex = optionIndex(id, optionRows.size) ?: return null
        val row = optionRows[optionIndex]
        val group = row[3]
        val changes = buildList {
            if (group.isNotEmpty()) {
                optionRows.filter { it[3] == group && it[0] != row[0] }.forEach {
                    add(OptionChange(it[0], false))
                }
            }
            add(OptionChange(row[0], group.isNotEmpty() || row[2] != "1"))
        }
        return OwnerCommand.SetOptions(changes)
    }

    fun schemaIndex(id: Int, size: Int): Int? =
        (id - SCHEMA_ID_BASE).takeIf { id in SCHEMA_ID_BASE until SCHEMA_ID_BASE + size }

    private fun optionIndex(id: Int, size: Int): Int? =
        (id - OPTION_ID_BASE).takeIf { id in OPTION_ID_BASE until OPTION_ID_BASE + size }

    private fun action(
        id: Int,
        name: String,
        text: String = name,
        checked: Boolean? = null,
        menu: Array<Action>? = null,
        separator: Boolean = false,
    ) = Action(id, separator, checked != null, checked == true, name, "", text, text, menu)
}
