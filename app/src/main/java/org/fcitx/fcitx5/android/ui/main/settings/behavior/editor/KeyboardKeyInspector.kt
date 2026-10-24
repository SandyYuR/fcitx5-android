/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorForm
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorResultHost
import android.view.Gravity
import androidx.appcompat.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp

/** Complete key settings share the canvas scrolling surface. */
internal class KeyboardKeyInspector(context: Context) : LinearLayout(context) {
    var onDraft: (MutableMap<String, Any?>) -> Unit = {}
    var onDone: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onCopy: () -> Unit = {}
    var onDelete: () -> Unit = {}
    var onRowSettings: () -> Unit = {}
    var onStepMove: (Int) -> Unit = {}
    var resultHost: KeyEditorResultHost? = null
    private var form: KeyEditorForm? = null
    private var lastEmitted: Map<String, Any?>? = null
    private val title = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setPadding(0, context.dp(12), context.dp(8), context.dp(12))
    }
    private val moreButton = EditorUi.icon(context, R.drawable.ic_baseline_more_horiz_24, R.string.layout_editor_key_menu) { showKeyMenu() }
    private val doneButton = EditorUi.button(context, R.string.layout_editor_done, tonal = true) { if (validate()) onDone() }
    private val controls = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(moreButton)
        addView(doneButton)
    }

    private fun showKeyMenu() {
        PopupMenu(context, moreButton).apply {
            menu.add(R.string.layout_editor_move_left).setOnMenuItemClickListener { if (validate()) onStepMove(-1); true }
            menu.add(R.string.layout_editor_move_right).setOnMenuItemClickListener { if (validate()) onStepMove(1); true }
            menu.add(R.string.layout_editor_copy_key).setOnMenuItemClickListener { if (validate()) onCopy(); true }
            menu.add(R.string.layout_editor_delete_key).setOnMenuItemClickListener { onDelete(); true }
            menu.add(R.string.layout_editor_row_settings).setOnMenuItemClickListener { if (validate()) onRowSettings(); true }
            menu.add(R.string.layout_editor_discard).setOnMenuItemClickListener { onCancel(); true }
        }.show()
    }

    init {
        orientation = VERTICAL
        addView(controls)
        clear()
    }

    fun clear() {
        form = null
        lastEmitted = null
        resultHost?.bind(null)
        removeAllViews()
    }

    fun bind(position: EditorKeyPosition, key: Map<String, Any?>, options: Intent = Intent(), state: Bundle? = null) {
        clear()
        title.text = context.getString(R.string.layout_editor_key_title, position.row + 1, position.key + 1)
        options.putExtra(KeyEditorForm.EXTRA_KEY_DATA, KeyEditorForm.toSerializableMap(key))
        options.putExtra(KeyEditorForm.EXTRA_ROW_INDEX, position.row)
        options.putExtra(KeyEditorForm.EXTRA_KEY_INDEX, position.key)
        val editor = resultHost?.createForm(context, options, state) ?: KeyEditorForm(context, options, state)
        form = editor
        lastEmitted = editor.validatedDraft(showErrors = false) ?: key
        addView(controls)
        addView(editor.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        editor.onChange = { editor.validatedDraft(showErrors = false)?.let(::publishDraft) }
        resultHost?.bind(editor)
    }

    fun saveState(): Bundle? = form?.let { editor -> Bundle().also(editor::saveState) }
    private fun publishDraft(key: MutableMap<String, Any?>) {
        if (key != lastEmitted) {
            lastEmitted = key
            onDraft(key)
        }
    }

    fun validate(): Boolean {
        val editor = form ?: return true
        val key = editor.validatedDraft() ?: return false
        publishDraft(key)
        return true
    }
}
