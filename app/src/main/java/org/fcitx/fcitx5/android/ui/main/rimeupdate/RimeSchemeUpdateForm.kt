/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.rimeupdate

import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.common.WrappingActionRow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorUi
import org.fcitx.fcitx5.android.utils.settingsColor

internal class RimeSchemeUpdateForm(context: Context) : LinearLayout(context) {
    private val content = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
    val scroll = ScrollView(context).apply { isFillViewport = true; addView(content) }
    val profile = text(context.getString(R.string.wanxiang_loading_directory), true).apply { textSize = 18f }
    val target = text("").apply { setTextIsSelectable(true); visibility = GONE }
    val pathToggle = EditorUi.button(context, R.string.scheme_update_show_path, true) { showPath(target.visibility != VISIBLE) }
    val status = OperationStatusView(context)
    val add = EditorUi.button(context, R.string.scheme_update_add, true) { onAdd() }
    val execute = MaterialButton(context).apply {
        setText(R.string.scheme_update_run_selected); isAllCaps = false; minHeight = dp(48); minimumHeight = dp(48)
        setOnClickListener { onExecute() }
    }
    val delete = EditorUi.button(context, R.string.scheme_update_delete_selected) { onDelete() }
    val cancel = EditorUi.button(context, android.R.string.cancel, true) { onCancel() }
    val retry = EditorUi.button(context, R.string.wanxiang_retry_directory, true) { onRetry() }
    private val tasks = column()
    private val empty = text(context.getString(R.string.scheme_update_empty))
    private val footer = column().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
    private val editors = mutableListOf<View>()
    private val runners = mutableListOf<View>()
    private var selectedCount = 0
    private var busy = false
    private var allowed = false
    private var compact = false
    var onAdd: () -> Unit = {}
    var onExecute: () -> Unit = {}
    var onDelete: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onRetry: () -> Unit = {}
    var onEdit: (RimeSchemeTask) -> Unit = {}
    var onRunOne: (RimeSchemeTask) -> Unit = {}
    var onSelect: (RimeSchemeTask, Boolean) -> Unit = { _, _ -> }

    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(footer, LayoutParams(-1, -2))
        content.addView(EditorUi.card(context, box().apply {
            addView(text(context.getString(R.string.scheme_update_current), true))
            addView(profile, gap())
            addView(pathToggle, gap())
            addView(target, gap())
            addView(text(context.getString(R.string.scheme_update_hint)), gap())
        }), block())
        content.addView(WrappingActionRow(context).apply { addView(add); addView(delete) }, block())
        content.addView(empty, block())
        content.addView(tasks, LayoutParams(-1, -2))
        footer.addView(status, block(6))
        footer.addView(WrappingActionRow(context).apply { addView(execute); addView(cancel); addView(retry) })
        setControls(false, false, true)
    }

    fun showPath(expanded: Boolean) {
        target.visibility = if (expanded) VISIBLE else GONE
        pathToggle.setText(if (expanded) R.string.scheme_update_hide_path else R.string.scheme_update_show_path)
    }

    fun showTasks(items: List<RimeSchemeTask>) {
        val offset = scroll.scrollY
        tasks.removeAllViews(); editors.clear(); runners.clear()
        selectedCount = items.count { it.selected }
        empty.visibility = if (items.isEmpty()) VISIBLE else GONE
        delete.visibility = if (items.isEmpty()) GONE else VISIBLE
        execute.text = context.getString(R.string.scheme_update_run_count, selectedCount)
        items.forEach { task ->
            tasks.addView(EditorUi.card(context, box().apply {
                val selection = MaterialCheckBox(context).apply {
                    text = task.name; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                    minHeight = dp(48); isChecked = task.selected
                    setOnCheckedChangeListener { _, checked -> onSelect(task, checked) }
                }
                editors += selection
                addView(selection, LayoutParams(-1, -2))
                addView(text(task.fileName + " · " + context.getString(if (task.unpack) R.string.scheme_update_unpack else R.string.scheme_update_raw)), gap())
                addView(text(task.destinations.joinToString("、") { it.ifEmpty { context.getString(R.string.scheme_update_root) } }), gap())
                val edit = EditorUi.button(context, R.string.scheme_update_edit, true) { onEdit(task) }
                val run = EditorUi.button(context, R.string.scheme_update_run_one, true) { onRunOne(task) }
                editors += edit; runners += run
                addView(WrappingActionRow(context).apply { addView(edit); addView(run) }, gap())
            }), block())
        }
        updateControls()
        scroll.post { scroll.scrollTo(0, offset) }
    }

    fun setControls(value: Boolean, canStart: Boolean, canCancel: Boolean) {
        busy = value; allowed = canStart
        cancel.visibility = if (value) VISIBLE else GONE
        cancel.isEnabled = value && canCancel
        retry.visibility = if (!value && !canStart) VISIBLE else GONE
        retry.isEnabled = !value
        updateControls()
    }
    private fun updateControls() {
        add.isEnabled = !busy
        delete.isEnabled = !busy && selectedCount > 0
        execute.isEnabled = !busy && allowed && selectedCount > 0
        editors.forEach { it.isEnabled = !busy }
        runners.forEach { it.isEnabled = !busy && allowed }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        footer.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val short = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getSize(heightMeasureSpec) < maxOf(dp(420), footer.measuredHeight + dp(160))
        if (short != compact) {
            compact = short
            (footer.parent as ViewGroup).removeView(footer)
            if (short) content.addView(footer, 1, LayoutParams(-1, -2)) else addView(footer, LayoutParams(-1, -2))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun box() = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
    private fun text(value: String, title: Boolean = false) = MaterialTextView(context).apply {
        text = value; textSize = if (title) 16f else 14f
        if (title) typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.settingsColor(if (title) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
    private fun gap() = LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun block(bottom: Int = 12) = LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** The Activity persists this draft across rotation and the system document picker. */
internal class RimeSchemeTaskEditor(context: Context, private val initial: RimeSchemeTask,
    browse: () -> Unit) : LinearLayout(context) {
    val taskId: String get() = initial.id
    private var automaticName = RimeSchemeTask.suggestedFileName(initial.source)
    private fun input(title: Int, value: String, max: Int, lines: Int = 1): TextInputEditText {
        val box = TextInputLayout(context).apply { hint = context.getString(title) }
        // The layout supplies the outlined-field theme overlay to its editor.
        val field = TextInputEditText(box.context).apply {
            setText(value)
            filters = arrayOf(InputFilter.LengthFilter(max))
            minLines = lines; maxLines = maxOf(lines, 8)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        }
        box.addView(field, LayoutParams(-1, -2))
        addView(box, LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(12) })
        return field
    }
    val name: TextInputEditText
    val source: TextInputEditText
    val fileName: TextInputEditText
    val unpack: MaterialSwitch
    val strip: MaterialSwitch
    val destinations: TextInputEditText
    val excludes: TextInputEditText
    val error = MaterialTextView(context).apply {
        setTextColor(context.settingsColor(androidx.appcompat.R.attr.colorError))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    init {
        orientation = VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(16))
        name = input(R.string.scheme_update_name, initial.name, 120)
        source = input(R.string.scheme_update_source, initial.source, 8192, 2)
        addView(EditorUi.button(context, R.string.scheme_update_browse, true, browse), LayoutParams(-1, -2))
        fileName = input(R.string.scheme_update_filename, initial.fileName, 240)
        source.doAfterTextChanged {
            val next = RimeSchemeTask.suggestedFileName(it.toString().trim())
            if (fileName.text.isNullOrBlank() || fileName.text.toString() == automaticName) fileName.setText(next)
            automaticName = next
        }
        unpack = MaterialSwitch(context).apply { setText(R.string.scheme_update_unpack); isChecked = initial.unpack; minHeight = dp(48) }
        strip = MaterialSwitch(context).apply { setText(R.string.scheme_update_strip); isChecked = initial.stripRoot; minHeight = dp(48); isEnabled = initial.unpack }
        unpack.setOnCheckedChangeListener { _, checked -> strip.isEnabled = checked }
        addView(unpack, LayoutParams(-1, -2)); addView(strip, LayoutParams(-1, -2))
        addView(MaterialTextView(context).apply { setText(R.string.scheme_update_formats); textSize = 12f }, LayoutParams(-1, -2))
        destinations = input(R.string.scheme_update_destinations, initial.destinations.joinToString("\n") { it.ifEmpty { "." } }, 4096, 2)
        excludes = input(R.string.scheme_update_excludes, initial.excludes, 16384, 4)
        addView(MaterialTextView(context).apply { setText(R.string.scheme_update_exclude_hint); textSize = 12f }, LayoutParams(-1, -2))
        addView(error, LayoutParams(-1, -2))
    }
    fun selectedFile(uri: String, displayName: String) {
        source.setText(uri)
        fileName.setText(displayName)
        automaticName = displayName
    }
    fun showError(message: String?) {
        error.text = message
        post { (parent as? ScrollView)?.smoothScrollTo(0, error.top) }
    }
    fun draft() = initial.copy(name = name.text.toString().trim(), source = source.text.toString().trim(),
        fileName = fileName.text.toString().trim(), unpack = unpack.isChecked, stripRoot = strip.isChecked,
        destinations = destinations.text.toString().lines().map(String::trim).filter(String::isNotEmpty)
            .map { if (it == ".") "" else it.removeSuffix("/") }.ifEmpty { listOf("") }, excludes = excludes.text.toString())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
