/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.*
import org.fcitx.fcitx5.android.ui.common.*
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.EditorDraftState
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorUi
import org.fcitx.fcitx5.android.utils.settingsColor
import org.fcitx.fcitx5.android.utils.allowImeInput
import org.yaml.snakeyaml.nodes.*

class WanxiangAdvancedActivity : SettingsUiActivity() {
    private val model: WanxiangAdvancedViewModel by viewModels()
    private val yaml = WanxiangYaml()
    private val drafts by lazy { EditorDraftState(this, "wanxiang-advanced") }
    private lateinit var file: MaterialSpinner
    private lateinit var mode: MaterialSpinner
    private lateinit var group: MaterialSpinner
    private lateinit var groupRow: View
    private lateinit var target: TextView
    private lateinit var hint: TextView
    private lateinit var modeSummary: TextView
    private lateinit var footerSummary: TextView
    private lateinit var editingOptions: LinearLayout
    private lateinit var optionsButton: MaterialButton
    private lateinit var fields: LinearLayout
    private lateinit var status: OperationStatusView
    private lateinit var save: View
    private lateinit var reload: View
    private lateinit var repair: View
    private lateinit var add: View
    private var rendering = false
    private var optionsExpanded = false
    private var editor: WanxiangValueEditor? = null
    private var editorDialog: androidx.appcompat.app.AlertDialog? = null
    private var fileKeys = emptyList<String>()
    private var groupKeys = emptyList<String>()
    private val groupNames by lazy { resources.getStringArray(R.array.wanxiang_group_keys).zip(resources.getStringArray(R.array.wanxiang_group_titles)).toMap() }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.wanxiang_advanced)
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val notice = text(getString(R.string.wanxiang_advanced_scope_hint)).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
        }
        body.addView(EditorUi.card(this, notice).apply {
            setCardBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSecondaryContainer))
        }, block())
        val selectors = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        fun selector(parent: LinearLayout, label: Int): Pair<MaterialSpinner, View> {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val spinner = MaterialSpinner(this).apply { id = View.generateViewId(); contentDescription = getString(label) }
            row.addView(text(getString(label)).apply { labelFor = spinner.id })
            row.addView(spinner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            return spinner to row
        }
        file = selector(selectors, R.string.wanxiang_advanced_file).first
        val category = selector(selectors, R.string.wanxiang_advanced_section)
        group = category.first; groupRow = category.second
        modeSummary = text("").apply { setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)) }
        selectors.addView(modeSummary)
        optionsExpanded = savedInstanceState?.getBoolean("editingOptions") ?: false
        optionsButton = EditorUi.button(this, R.string.wanxiang_advanced_options, tonal = true) {
            optionsExpanded = !optionsExpanded
            showEditingOptions()
        }
        selectors.addView(WrappingActionRow(this).apply { addView(optionsButton) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        editingOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mode = selector(editingOptions, R.string.wanxiang_advanced_mode).first.apply {
            adapter = ArrayAdapter(this@WanxiangAdvancedActivity, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.wanxiang_advanced_modes))
        }
        hint = text("").apply { setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)) }
        editingOptions.addView(hint)
        selectors.addView(editingOptions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        showEditingOptions()
        body.addView(EditorUi.card(this, selectors), block())
        status = OperationStatusView(this)
        body.addView(status, block())
        fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(EditorUi.card(this, fields), block())
        add = EditorUi.button(this, R.string.wanxiang_advanced_add_field, tonal = true) { addField() }
        body.addView(WrappingActionRow(this).apply { addView(add) }, block())
        val maintenance = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        maintenance.addView(text(getString(R.string.wanxiang_advanced_maintenance)).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        target = text("").apply { setTextIsSelectable(true); setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)) }
        maintenance.addView(target, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        maintenance.addView(text(getString(R.string.wanxiang_advanced_repair_hint)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        repair = EditorUi.button(this, R.string.wanxiang_advanced_repair, tonal = true) {
            if (!model.state.value.busy) discard { model.repairDeployment() }
        }
        maintenance.addView(WrappingActionRow(this).apply { addView(repair) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(EditorUi.card(this, maintenance), block())
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            setBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        footerSummary = text("").apply { setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)) }
        footer.addView(footerSummary)
        footer.addView(WrappingActionRow(this).apply {
            reload = EditorUi.button(this@WanxiangAdvancedActivity, R.string.wanxiang_advanced_reload, tonal = true) { discard { model.reload() } }.also(::addView)
            save = MaterialButton(this@WanxiangAdvancedActivity).apply {
                setText(R.string.wanxiang_advanced_save)
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { model.save() }
            }.also(::addView)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // Keep actions reachable while editing a long file. In a short window they join
        // the scroll content, so large text never consumes the entire viewport.
        val root = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                footer.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val compact = View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED &&
                    View.MeasureSpec.getSize(heightMeasureSpec) < maxOf(dp(420), footer.measuredHeight + dp(216))
                val destination: ViewGroup = if (compact) body else this
                if (footer.parent !== destination) {
                    (footer.parent as? ViewGroup)?.removeView(footer)
                    destination.addView(footer, LinearLayout.LayoutParams(-1, -2))
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@WanxiangAdvancedActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root); setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        fun listen(spinner: MaterialSpinner, action: (Int) -> Unit) {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (!rendering) action(position) }
            }
        }
        listen(file) { index -> fileKeys.getOrNull(index)?.takeIf { it != model.state.value.file }?.let { model.select(it) } }
        listen(group) { index -> groupKeys.getOrNull(index)?.takeIf { it != model.state.value.group }?.let { model.select(model.state.value.file, it) } }
        listen(mode) { index -> if (!model.state.value.forcedDirect && (index == 1) != model.state.value.direct) model.mode(index == 1) }
        onBackPressedDispatcher.addCallback(this) {
            if (!model.state.value.saving) discard { finish() }
        }
        model.initialize(drafts.read(savedInstanceState))
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }

    private fun showEditingOptions() {
        editingOptions.visibility = if (optionsExpanded) View.VISIBLE else View.GONE
        optionsButton.setText(if (optionsExpanded) R.string.wanxiang_advanced_options_hide else R.string.wanxiang_advanced_options)
    }

    private fun render(state: WanxiangAdvancedViewModel.State) {
        rendering = true
        target.text = state.target?.let { "${getString(R.string.wanxiang_directory)}\n${it.profile}\n${it.path}" } ?: getString(R.string.wanxiang_loading_directory)
        val common = "default.yaml" in state.files && state.files.any { it in WanxiangAdvancedViewModel.MAIN_FILES }
        val nextFiles = (if (common) listOf(WanxiangAdvancedViewModel.COMMON) else emptyList()) + state.files
        if (fileKeys != nextFiles) {
            fileKeys = nextFiles
            file.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, fileKeys.map { if (it == WanxiangAdvancedViewModel.COMMON) getString(R.string.wanxiang_advanced_common) else it }) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                        contentDescription = text
                        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, text)
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
                        layoutParams = AbsListView.LayoutParams(-1, -2)
                    }
            }
        }
        file.setSelection(fileKeys.indexOf(state.file).coerceAtLeast(0))
        if (groupKeys != state.groups) {
            groupKeys = state.groups
            group.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groupKeys.map { groupNames[it] ?: it })
        }
        group.setSelection(groupKeys.indexOf(state.group).coerceAtLeast(0))
        groupRow.visibility = if (groupKeys.isEmpty()) View.GONE else View.VISIBLE
        mode.setSelection(if (state.direct || state.forcedDirect) 1 else 0)
        modeSummary.setText(if (state.direct || state.forcedDirect) R.string.wanxiang_advanced_direct_summary else R.string.wanxiang_advanced_patch_summary)
        mode.isEnabled = !state.busy && state.dirty == 0 && !state.forcedDirect && model.session?.documents?.isNotEmpty() == true
        file.isEnabled = !state.busy; group.isEnabled = !state.busy
        hint.text = buildString {
            append(getString(if (state.direct || state.forcedDirect) R.string.wanxiang_advanced_direct_hint else R.string.wanxiang_advanced_patch_hint))
            if (state.dirty > 0) append("\n\n").append(getString(R.string.wanxiang_advanced_mode_locked))
            if (!state.compiled) append("\n\n").append(getString(R.string.wanxiang_advanced_compile_hint))
        }
        save.isEnabled = !state.busy && !state.stale && state.dirty > 0
        reload.isEnabled = !state.busy
        repair.isEnabled = !state.busy && state.target != null
        add.visibility = if (state.file == WanxiangAdvancedViewModel.COMMON) View.GONE else View.VISIBLE
        add.isEnabled = !state.busy && !state.stale && model.session?.documents?.containsKey(state.file) == true
        if (!state.busy) {
            fields.isEnabled = true
            fields.removeAllViews()
            state.rows.forEachIndexed { index, row ->
                if (index > 0) fields.addView(View(this).apply {
                    setBackgroundColor(settingsColor(com.google.android.material.R.attr.colorOutlineVariant))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) })
                addRow(row, state)
            }
            if (state.file == WanxiangAdvancedViewModel.COMMON && state.rows.isNotEmpty()) fields.addView(EditorUi.button(this,
                R.string.wanxiang_advanced_import_switches, tonal = true) { model.importSwitches() }.apply { isEnabled = !state.stale })
        } else {
            fun disable(view: View) {
                view.isEnabled = false
                if (view is android.view.ViewGroup) repeat(view.childCount) { disable(view.getChildAt(it)) }
            }
            disable(fields)
        }
        when {
            state.error != null -> status.show(getString(R.string.ui_operation_failed), state.error, OperationStatusView.State.ERROR)
            state.busy -> status.show(getString(if (state.saving) R.string.wanxiang_advanced_saving else R.string.wanxiang_advanced_loading), state = OperationStatusView.State.WORKING)
            state.saved -> status.show(getString(R.string.wanxiang_advanced_saved), state = OperationStatusView.State.SUCCESS)
            state.dirty > 0 -> status.show(getString(R.string.wanxiang_advanced_dirty, state.dirty))
            !state.compiled -> status.show(getString(R.string.wanxiang_advanced_source_values), getString(R.string.wanxiang_advanced_compile_hint))
            else -> status.show(getString(R.string.wanxiang_advanced_ready))
        }
        status.visibility = if (state.error != null || state.busy || state.saved || !state.compiled) View.VISIBLE else View.GONE
        footerSummary.text = when {
            state.busy -> getString(if (state.saving) R.string.wanxiang_advanced_saving else R.string.wanxiang_advanced_loading)
            state.error != null -> getString(R.string.ui_operation_failed)
            state.dirty > 0 -> getString(R.string.wanxiang_advanced_dirty, state.dirty)
            state.saved -> getString(R.string.wanxiang_advanced_saved)
            else -> getString(R.string.wanxiang_advanced_ready)
        }
        rendering = false
        if (!state.busy && model.pending != null && editorDialog == null) showPending()
    }

    private fun title(row: WanxiangAdvancedViewModel.Row): String = if (row.common != null)
        resources.getStringArray(R.array.wanxiang_common_titles)[row.common.ordinal]
    else row.help.lineSequence().firstOrNull { it.any { c -> c in '\u4e00'..'\u9fff' } }?.take(70) ?: row.path.substringAfterLast('/')

    private fun addRow(row: WanxiangAdvancedViewModel.Row, state: WanxiangAdvancedViewModel.State) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(64) }
        val readOnly = row.path == "schema/schema_id"
        if (row.type == WanxiangYaml.Type.BOOLEAN) {
            container.addView(MaterialSwitch(this).apply {
                text = title(row); minHeight = dp(48)
                isChecked = yaml.boolean(model.node(row)); isEnabled = !state.busy && !state.stale && !readOnly
                setOnCheckedChangeListener { _, checked ->
                    if (!rendering) runCatching { model.edit(row, yaml.dump(yaml.scalar(checked))) }.onFailure { showError(it) }
                }
            })
            if (row.common == null && !readOnly) container.addView(EditorUi.button(this,
                if (state.direct || state.forcedDirect) R.string.wanxiang_advanced_remove else R.string.wanxiang_advanced_reset, tonal = true) { model.reset(row) }.apply {
                    isEnabled = !state.busy && !state.stale
                })
        } else {
            container.addView(text(title(row)).apply { textSize = 16f })
            val node = model.node(row)
            val preview = when (row.common) {
                WanxiangCommonSettings.Key.PAGING -> resources.getStringArray(R.array.wanxiang_paging_choices).getOrNull((node as ScalarNode).value.toIntOrNull() ?: -1).orEmpty()
                WanxiangCommonSettings.Key.GRAMMAR -> getString(R.string.wanxiang_advanced_grammar_action)
                else -> when (node) {
                    is SequenceNode -> getString(R.string.wanxiang_advanced_item_count, node.value.size)
                    is MappingNode -> getString(R.string.wanxiang_advanced_item_count, node.value.size)
                    else -> yaml.text(node)
                }
            }
            container.addView(text(if (row.reset) getString(R.string.wanxiang_advanced_pending_reset) else preview).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            container.setBackgroundResource(android.R.attr.selectableItemBackground.let { attribute ->
                val value = android.util.TypedValue(); theme.resolveAttribute(attribute, value, true); value.resourceId
            })
            container.isEnabled = !state.busy && !state.stale && !readOnly
            container.setOnClickListener { open(row) }
        }
        if (row.common == null) container.addView(text(if (readOnly) getString(R.string.wanxiang_advanced_readonly)
            else row.path + if (row.reset) "\n" + getString(R.string.wanxiang_advanced_pending_reset) else "").apply {
            textSize = 12f; setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        else if (row.help.isNotBlank()) container.addView(text(row.help).apply {
            textSize = 12f; setTextColor(settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        fields.addView(container)
    }

    private fun open(row: WanxiangAdvancedViewModel.Row) {
        if (model.state.value.busy || model.state.value.stale) return
        val node = model.node(row)
        model.pending = WanxiangAdvancedViewModel.Pending(model.state.value.file, row.path, row.type.name, yaml.dump(node), row.common?.name)
        showPending()
    }

    private fun showPending() {
        try { showPendingChecked() }
        catch (error: Exception) { model.pending = null; showError(error) }
    }
    private fun showPendingChecked() {
        val pending = model.pending ?: return
        val common = pending.common?.let(WanxiangCommonSettings.Key::valueOf)
        val row = WanxiangAdvancedViewModel.Row(pending.path, pending.path, pending.value, WanxiangYaml.Type.valueOf(pending.type), common = common)
        val node = yaml.parse(pending.value)
        if (common in setOf(WanxiangCommonSettings.Key.SCHEMAS, WanxiangCommonSettings.Key.PHRASE_SCHEMAS, WanxiangCommonSettings.Key.ABBREV_SCHEMAS)) {
            showSchemas(row, node, common == WanxiangCommonSettings.Key.SCHEMAS); return
        }
        if (common == WanxiangCommonSettings.Key.PAGING || common == WanxiangCommonSettings.Key.GRAMMAR) {
            val choices = resources.getStringArray(if (common == WanxiangCommonSettings.Key.PAGING) R.array.wanxiang_paging_choices else R.array.wanxiang_grammar_choices)
            var selection = (node as ScalarNode).value.toInt()
            val dialog = MaterialAlertDialogBuilder(this).setTitle(title(row)).setSingleChoiceItems(choices, selection) { _, index ->
                selection = index; model.pending = model.pending?.copy(value = yaml.dump(yaml.scalar(index)))
            }.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching { model.editPending(yaml.dump(yaml.scalar(selection))) }.onFailure(::showError)
            }.create()
            track(dialog); dialog.show(); return
        }
        val valueEditor = WanxiangValueEditor(this, yaml, node, pending.editor)
        editor = valueEditor
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val sourceRow = model.state.value.rows.firstOrNull { it.path == pending.path }
        sourceRow?.help?.takeIf { it.isNotBlank() }?.let { content.addView(text(it)) }
        addPresetActions(content, pending.path, valueEditor)
        content.addView(valueEditor)
        val scroll = ScrollView(this).apply { addView(content); layoutParams = LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6f).toInt()) }
        val builder = MaterialAlertDialogBuilder(this).setTitle(title(sourceRow ?: row)).setView(scroll)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null)
        if (common == null) builder.setNeutralButton(if (model.state.value.direct || model.state.value.forcedDirect)
            R.string.wanxiang_advanced_remove else R.string.wanxiang_advanced_reset, null)
        val dialog = builder.create()
        track(dialog)
        dialog.setOnShowListener {
            // Boolean/list views can create a text editor on navigation or in raw YAML mode.
            dialog.allowImeInput()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                runCatching { model.editPending(yaml.dump(valueEditor.result())) }.onSuccess { dialog.dismiss() }.onFailure { error ->
                    MaterialAlertDialogBuilder(this).setTitle(R.string.ui_operation_failed).setMessage(error.message).setPositiveButton(android.R.string.ok, null).show()
                }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                model.reset(sourceRow ?: row); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showSchemas(row: WanxiangAdvancedViewModel.Row, node: Node, schemas: Boolean) {
        val original = (node as SequenceNode).value
        fun id(item: Node): String = if (schemas) (yaml.member(item as MappingNode, "schema") as ScalarNode).value else (item as ScalarNode).value
        val selected = original.map(::id).toMutableSet()
        val options = ((if (schemas) model.state.value.files.filter { it.endsWith(".schema.yaml") } else model.session!!.mainFiles)
            .map { it.removeSuffix(".schema.yaml") } + selected).distinct()
        fun result() = yaml.sequence(selected.map { name ->
            original.firstOrNull { id(it) == name }?.let(yaml::clone) ?: if (schemas) yaml.mapping().also { yaml.put(it, "schema", yaml.string(name)) } else yaml.string(name)
        })
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title(row)).setMultiChoiceItems(options.toTypedArray(), options.map { it in selected }.toBooleanArray()) { _, index, checked ->
            if (checked) selected += options[index] else selected -= options[index]
            model.pending = model.pending?.copy(value = yaml.dump(result()))
        }.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        track(dialog)
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            runCatching { model.editPending(yaml.dump(result())) }.onSuccess { dialog.dismiss() }.onFailure(::showError)
        } }
        dialog.show()
    }

    private fun addPresetActions(content: LinearLayout, path: String, value: WanxiangValueEditor) {
        if (path != "speller/algebra" || value.result() !is MappingNode) return
        val library = model.session?.documents?.get("wanxiang_algebra.yaml")?.source ?: return
        val presets = WanxiangAlgebraPresets(yaml, library, model.state.value.file)
        val actions = WrappingActionRow(this)
        fun select(label: Int, options: List<String>, aux: Boolean = false, stroke: Boolean = false) {
            if (options.isEmpty()) return
            actions.addView(EditorUi.button(this, label, tonal = true) {
                runCatching {
                    val node = value.result() as? MappingNode ?: return@runCatching
                    MaterialAlertDialogBuilder(this).setTitle(label).setSingleChoiceItems(options.toTypedArray(), presets.selected(node, options)) { dialog, index ->
                        value.replaceRoot(presets.select(node, options[index], aux, stroke)); dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
                }.onFailure(::showError)
            })
        }
        select(R.string.wanxiang_advanced_preset, presets.schemes, false)
        select(R.string.wanxiang_advanced_auxiliary, presets.auxiliary, true)
        select(R.string.wanxiang_advanced_strokes, presets.strokes, stroke = true)
        if (presets.extra.isNotEmpty()) actions.addView(EditorUi.button(this, R.string.wanxiang_advanced_rules, tonal = true) {
            runCatching {
                val node = value.result() as? MappingNode ?: return@runCatching
                val selected = presets.selectedExtra(node).toMutableSet()
                MaterialAlertDialogBuilder(this).setTitle(R.string.wanxiang_advanced_rules).setMultiChoiceItems(presets.extra.toTypedArray(),
                    presets.extra.map { it in selected }.toBooleanArray()) { _, index, checked ->
                    if (checked) selected += presets.extra[index] else selected -= presets.extra[index]
                }.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> value.replaceRoot(presets.extras(node, selected)) }.show()
            }.onFailure(::showError)
        })
        content.addView(actions)
    }

    private fun addField() {
        val input = TextInputEditText(this)
        val field = TextInputLayout(this).apply { hint = getString(R.string.wanxiang_advanced_path); addView(input); setPadding(dp(20), 0, dp(20), 0) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.wanxiang_advanced_add_field).setView(field)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val path = input.text.toString().trim()
                yaml.path(path)
                require(path != "schema/schema_id")
                model.pending = WanxiangAdvancedViewModel.Pending(model.state.value.file, path, WanxiangYaml.Type.YAML.name, "null\n")
                dialog.dismiss(); showPending()
            }.onFailure { field.error = it.message }
        } }
        dialog.show()
    }

    private fun track(dialog: androidx.appcompat.app.AlertDialog) {
        editorDialog = dialog
        dialog.setOnDismissListener {
            if (!isChangingConfigurations && !isDestroyed) model.pending = null
            editorDialog = null; editor = null
        }
    }
    private fun discard(action: () -> Unit) {
        if (model.state.value.dirty == 0 && model.pending == null) action()
        else MaterialAlertDialogBuilder(this).setMessage(R.string.wanxiang_advanced_discard)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> action() }.show()
    }
    private fun showError(error: Throwable) { MaterialAlertDialogBuilder(this).setTitle(R.string.ui_operation_failed).setMessage(error.message).setPositiveButton(android.R.string.ok, null).show() }
    private fun text(value: String): MaterialTextView = MaterialTextView(this).apply { text = value; textSize = 14f }
    private fun block() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editingOptions", optionsExpanded)
        editor?.let { value -> model.pending = model.pending?.copy(editor = value.state()) }
        model.exportDraft()?.let { drafts.save(outState, it) } ?: drafts.retain(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (isFinishing) drafts.delete(); super.onDestroy() }
}
