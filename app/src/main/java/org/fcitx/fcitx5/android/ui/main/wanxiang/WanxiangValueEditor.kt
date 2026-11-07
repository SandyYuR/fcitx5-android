/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.WanxiangYaml
import org.fcitx.fcitx5.android.ui.common.MaterialSpinner
import org.fcitx.fcitx5.android.ui.common.WrappingActionRow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorUi
import org.yaml.snakeyaml.nodes.*
import org.fcitx.fcitx5.android.utils.settingsColor

/** One navigable editor retains nested drafts without allocating a stack of dialogs. */
internal class WanxiangValueEditor(context: Context, private val codec: WanxiangYaml, initial: Node, restored: State? = null) : LinearLayout(context) {
    @Serializable data class State(val yaml: String, val path: List<String> = emptyList(), val raw: Boolean = false,
        val pending: String? = null, val page: Int = 0, val newKey: String = "", val newType: Int = 0)
    private var root = if (restored == null) codec.clone(initial) else codec.parse(restored.yaml)
    private val path = restored?.path?.toMutableList() ?: mutableListOf()
    private var raw = restored?.raw ?: false
    private var page = restored?.page ?: 0
    private var input: TextInputEditText? = null
    private var inputBox: TextInputLayout? = null
    private var toggle: MaterialSwitch? = null
    private var keyInput: TextInputEditText? = null
    private var typeInput: MaterialSpinner? = null
    private var pending = restored?.pending
    private var nextKey = restored?.newKey.orEmpty()
    private var nextType = restored?.newType ?: 0
    private val errorView = MaterialTextView(context).apply { setTextColor(context.settingsColor(androidx.appcompat.R.attr.colorError)) }

    init { orientation = VERTICAL; render() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String) = MaterialTextView(context).apply {
        text = value
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    }
    private fun current(): Node = path.fold(root) { at, segment ->
        when (at) {
            is MappingNode -> codec.member(at, segment) ?: error("Missing mapping key")
            is SequenceNode -> at.value[segment.toInt()]
            else -> error("Invalid editor navigation")
        }
    }
    private fun replace(node: Node) {
        val old = current()
        node.blockComments = old.blockComments; node.inLineComments = old.inLineComments; node.endComments = old.endComments
        if (path.isEmpty()) root = node else {
            val key = path.removeAt(path.lastIndex)
            when (val parent = current()) {
                is MappingNode -> codec.put(parent, key, node)
                is SequenceNode -> parent.value[key.toInt()] = node
            }
            path += key
        }
    }
    private fun commitInput() {
        val old = current()
        val next = when {
            toggle != null -> codec.scalar(toggle!!.isChecked)
            input != null -> if (raw || codec.type(old) == WanxiangYaml.Type.YAML) codec.parse(input!!.text.toString()) else codec.value(input!!.text.toString(), codec.type(old))
            else -> return
        }
        require(old !is MappingNode || next is MappingNode) { "Value must remain a mapping" }
        require(old !is SequenceNode || next is SequenceNode) { "Value must remain a list" }
        replace(next)
    }
    fun result(): Node { commitInput(); return root }
    fun replaceRoot(node: Node) { root = codec.clone(node); path.clear(); raw = false; pending = null; page = 0; render() }
    fun state(): State {
        if (toggle != null) commitInput()
        return State(codec.dump(root), path.toList(), raw, input?.text?.toString(), page,
            keyInput?.text?.toString().orEmpty(), typeInput?.selectedItemPosition ?: 0)
    }

    private fun action(label: Int, block: () -> Unit) = EditorUi.button(context, label, tonal = true) {
        try { commitInput(); inputBox?.error = null; errorView.visibility = GONE; block() }
        catch (error: Exception) {
            if (inputBox != null) inputBox?.error = error.message ?: error.javaClass.simpleName
            else { errorView.text = error.message ?: error.javaClass.simpleName; errorView.visibility = VISIBLE }
        }
    }
    private fun render() {
        removeAllViews(); input = null; inputBox = null; toggle = null; keyInput = null; typeInput = null
        if (path.isNotEmpty()) {
            addView(label(path.joinToString(" › ")).apply {
                setTextColor(context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            addView(WrappingActionRow(context).apply {
                addView(action(R.string.wanxiang_advanced_parent) { path.removeAt(path.lastIndex); raw = false; page = 0; render() })
            }, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val node = current()
        if (raw || node !is MappingNode && node !is SequenceNode) {
            if (!raw && codec.type(node) == WanxiangYaml.Type.BOOLEAN) {
                toggle = MaterialSwitch(context).apply { text = context.getString(R.string.wanxiang_advanced_value); isChecked = codec.boolean(node); minHeight = dp(48) }.also(::addView)
            } else {
                inputBox = TextInputLayout(context).apply {
                    hint = context.getString(R.string.wanxiang_advanced_value)
                    input = TextInputEditText(context).apply {
                        inputType = if (!raw && codec.type(node) in listOf(WanxiangYaml.Type.INTEGER, WanxiangYaml.Type.DECIMAL))
                            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        minLines = if (raw || '\n' in codec.text(node)) 5 else 1
                        maxLines = 14
                        setText(pending ?: if (raw) codec.dump(node) else codec.text(node))
                    }
                    addView(input)
                }.also { addView(it, LayoutParams(-1, -2)) }
                pending = null
            }
        } else {
            val entries: List<Pair<String, Node>> = when (node) {
                is MappingNode -> node.value.map { codec.key(it.keyNode) to it.valueNode }
                is SequenceNode -> node.value.mapIndexed { index, item -> index.toString() to item }
                else -> emptyList()
            }
            addView(label(context.getString(R.string.wanxiang_advanced_item_count, entries.size)))
            page = page.coerceIn(0, (entries.size - 1).coerceAtLeast(0) / 30)
            entries.drop(page * 30).take(30).forEach { (key, value) ->
                val item = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    val preview = codec.text(value).lineSequence().firstOrNull().orEmpty().take(100)
                    addView(label(key).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall) })
                    addView(label(preview).apply {
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    }, LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    addView(WrappingActionRow(context).apply {
                        addView(action(R.string.edit) { path += key; page = 0; render() })
                        addView(action(R.string.wanxiang_advanced_remove) {
                            if (node is MappingNode) codec.put(node, key, null) else (node as SequenceNode).value.removeAt(key.toInt())
                            render()
                        })
                        if (node is SequenceNode) {
                            val index = key.toInt()
                            if (index > 0) addView(action(R.string.wanxiang_advanced_up) { val item = node.value.removeAt(index); node.value.add(index - 1, item); render() })
                            if (index < node.value.lastIndex) addView(action(R.string.wanxiang_advanced_down) { val item = node.value.removeAt(index); node.value.add(index + 1, item); render() })
                        }
                    })
                }
                addView(EditorUi.card(context, item), LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            if (entries.size > 30) addView(WrappingActionRow(context).apply {
                if (page > 0) addView(action(R.string.wanxiang_advanced_previous) { page--; render() })
                if ((page + 1) * 30 < entries.size) addView(action(R.string.wanxiang_advanced_next) { page++; render() })
            })
            if (node is MappingNode) {
                addView(TextInputLayout(context).apply {
                    hint = context.getString(R.string.wanxiang_advanced_key)
                    keyInput = TextInputEditText(context).apply { setText(nextKey) }.also(::addView)
                }, LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            val typeLabel = label(context.getString(R.string.wanxiang_advanced_type))
            addView(typeLabel, LayoutParams(-1, -2).apply { topMargin = dp(12) })
            typeInput = MaterialSpinner(context).apply {
                id = View.generateViewId()
                typeLabel.labelFor = id
                contentDescription = context.getString(R.string.wanxiang_advanced_type)
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.wanxiang_value_types))
                setSelection(nextType)
            }.also { addView(it, LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
            addView(action(R.string.wanxiang_advanced_add) {
                val key = keyInput?.text?.toString().orEmpty()
                require(node !is MappingNode || key.isNotBlank() && codec.member(node, key) == null) { "键不能为空或重复" }
                val next = when (typeInput!!.selectedItemPosition) {
                    1 -> codec.scalar(false)
                    2 -> codec.scalar(0)
                    3 -> codec.parse("0.0")
                    4 -> codec.sequence(emptyList())
                    5 -> codec.mapping()
                    6 -> codec.parse("null")
                    else -> codec.string("")
                }
                if (node is MappingNode) { codec.put(node, key, next); path += key }
                else { (node as SequenceNode).value.add(next); path += node.value.lastIndex.toString() }
                nextKey = ""; nextType = 0; page = 0; render()
            })
        }
        addView(WrappingActionRow(context).apply {
            addView(action(if (raw) R.string.wanxiang_advanced_structured else R.string.wanxiang_advanced_raw) { raw = !raw; render() })
        }, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        errorView.visibility = GONE
        addView(errorView)
    }
}
