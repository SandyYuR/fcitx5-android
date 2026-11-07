/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.WanxiangProtection
import org.fcitx.fcitx5.android.ui.common.MaterialSpinner
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.common.WrappingActionRow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorUi
import org.fcitx.fcitx5.android.utils.settingsColor

/** Options scroll independently; the current operation stays within reach. */
internal class WanxiangUpdateForm(context: Context, initial: WanxiangSelection) : LinearLayout(context) {
    val content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
    val scroll = ScrollView(context).apply { isFillViewport = true; addView(content) }
    val target = text(R.string.wanxiang_loading_directory).apply { setTextIsSelectable(true) }
    val status = OperationStatusView(context)
    val update = MaterialButton(context).apply {
        setText(R.string.wanxiang_start)
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
    }
    val cancel: MaterialButton = EditorUi.button(context, android.R.string.cancel, tonal = true) {}
    val retryDirectory = EditorUi.button(context, R.string.wanxiang_retry_directory, tonal = true) {}
    val advanced = EditorUi.button(context, R.string.wanxiang_advanced_open, tonal = true) { onAdvanced() }
    private val footer = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
    private val controls = box()
    private val scheme: MaterialSpinner
    private val schemeRow: View
    private val auxiliary: MaterialSpinner
    private val auxiliaryRow: View
    private val channel: MaterialSpinner
    private val channelRow: View
    private val source: MaterialSpinner
    private val sourceRow: View
    private val scope: MaterialSpinner
    private val channelHint = text(R.string.wanxiang_channel_hint)
    private val scopeHint = text(R.string.wanxiang_scope_all_hint)
    private val extrasCard: View
    var onSelectionChanged: (WanxiangSelection) -> Unit = {}
    var onProtection: () -> Unit = {}
    var onToken: () -> Unit = {}
    var onGithubCheck: () -> Unit = {}
    var onExtra: () -> Unit = {}
    var onExtraRefresh: () -> Unit = {}
    var onAdvanced: () -> Unit = {}
    private var protection = initial.protection
    private var extras = initial.extraDictionaries
    private val protectionButton = EditorUi.button(context, R.string.wanxiang_protection_edit, tonal = true) { onProtection() }
    private val tokenButton = EditorUi.button(context, R.string.wanxiang_token, tonal = true) { onToken() }
    private val checkButton = EditorUi.button(context, R.string.wanxiang_github_check, tonal = true) { onGithubCheck() }
    private val extraButton = EditorUi.button(context, R.string.wanxiang_extra_choose, tonal = true) { onExtra() }
    private val extraRefresh = EditorUi.button(context, R.string.wanxiang_extra_refresh, tonal = true) { onExtraRefresh() }
    private val extraSummary = text(R.string.wanxiang_extra)
    private val tokenSummary = text(R.string.wanxiang_token_unset)
    private var busy = false
    private var targetAvailable = false
    private var compact = false

    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(footer, LayoutParams(-1, -2))
        content.addView(EditorUi.card(context, box().apply {
            addView(text(R.string.wanxiang_only_title, title = true).apply {
                setTextColor(context.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            })
            addView(text(R.string.wanxiang_only_hint).apply {
                setTextColor(context.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            }, gap())
        }).apply { setCardBackgroundColor(context.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer)) }, block())
        content.addView(disclosure(R.string.wanxiang_directory, column().apply {
            addView(target)
            addView(text(R.string.wanxiang_directory_hint), gap())
        }), block())
        controls.addView(text(R.string.wanxiang_update_options, title = true), block(12))
        fun field(label: Int, items: Int, selected: Int): Pair<MaterialSpinner, View> {
            val row = column()
            val caption = text(label)
            val spinner = MaterialSpinner(context).apply {
                id = View.generateViewId()
                contentDescription = context.getString(label)
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(items))
                setSelection(selected)
            }
            caption.labelFor = spinner.id
            row.addView(caption)
            row.addView(spinner, gap())
            controls.addView(row, block())
            return spinner to row
        }
        scope = field(R.string.wanxiang_scope, R.array.wanxiang_scopes, initial.scope.ordinal).first
        controls.addView(scopeHint, block())
        val edition = field(R.string.wanxiang_scheme, R.array.wanxiang_schemes, initial.scheme.ordinal)
        scheme = edition.first; schemeRow = edition.second
        val aux = field(R.string.wanxiang_auxiliary, R.array.wanxiang_auxiliaries,
            WanxiangSelection.auxiliaryCodes.indexOf(initial.auxiliary).coerceAtLeast(0))
        auxiliary = aux.first; auxiliaryRow = aux.second
        val release = field(R.string.wanxiang_channel, R.array.wanxiang_channels, initial.channel.ordinal)
        channel = release.first; channelRow = release.second
        val download = field(R.string.wanxiang_source, R.array.wanxiang_sources, initial.source.ordinal)
        source = download.first; sourceRow = download.second
        controls.addView(channelHint)
        content.addView(EditorUi.card(context, controls), block())
        extraSummary.text = context.getString(R.string.wanxiang_extra_selected, extras.size)
        extrasCard = EditorUi.card(context, box().apply {
            addView(text(R.string.wanxiang_extra, title = true))
            addView(extraSummary, gap())
            addView(text(R.string.wanxiang_extra_hint), gap())
            addView(WrappingActionRow(context).apply { addView(extraButton); addView(extraRefresh) }, gap())
        })
        content.addView(extrasCard, block())
        content.addView(disclosure(R.string.wanxiang_protection, column().apply {
            addView(text(R.string.wanxiang_protection_hint))
            addView(WrappingActionRow(context).apply { addView(protectionButton) }, gap())
        }), block())
        content.addView(disclosure(R.string.wanxiang_network_options, column().apply {
            addView(text(R.string.wanxiang_token_hint))
            addView(tokenSummary, gap())
            addView(WrappingActionRow(context).apply { addView(tokenButton); addView(checkButton) }, gap())
        }), block())
        content.addView(EditorUi.card(context, box().apply {
            addView(text(R.string.wanxiang_advanced_entry_hint))
            addView(WrappingActionRow(context).apply { addView(advanced) }, gap())
        }), block())
        content.addView(text(R.string.wanxiang_preserve_hint), block(8))
        footer.addView(status, block(4))
        footer.addView(WrappingActionRow(context).apply { addView(update); addView(cancel); addView(retryDirectory) }, LayoutParams(-1, -2))
        listOf(scope, scheme, auxiliary, channel, source).forEach { spinner ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshControls()
                    onSelectionChanged(selection())
                }
            }
        }
        setBusy(false, canUpdate = false)
    }

    fun selection() = WanxiangSelection(
        WanxiangSelection.Scheme.entries[scheme.selectedItemPosition],
        WanxiangSelection.auxiliaryCodes[auxiliary.selectedItemPosition],
        WanxiangSelection.Channel.entries[channel.selectedItemPosition],
        WanxiangSelection.Source.entries[source.selectedItemPosition],
        WanxiangSelection.Scope.entries[scope.selectedItemPosition], protection, extras
    )
    fun protectionText() = protection
    fun setProtection(value: String) {
        WanxiangProtection(value)
        protection = value
        onSelectionChanged(selection())
    }
    fun setExtras(ids: List<String>) {
        extras = ids
        extraSummary.text = context.getString(R.string.wanxiang_extra_selected, ids.size)
        refreshControls()
        onSelectionChanged(selection())
    }
    fun setTokenConfigured(value: Boolean) { tokenSummary.setText(if (value) R.string.wanxiang_token_set else R.string.wanxiang_token_unset) }

    fun setBusy(value: Boolean, canUpdate: Boolean, canCancel: Boolean = true) {
        busy = value
        targetAvailable = canUpdate
        refreshControls()
        cancel.visibility = if (value) View.VISIBLE else View.GONE
        cancel.isEnabled = value && canCancel
        retryDirectory.visibility = if (!value && !canUpdate) View.VISIBLE else View.GONE
        retryDirectory.isEnabled = !value
    }

    private fun refreshControls() {
        val selectedScope = WanxiangSelection.Scope.entries[scope.selectedItemPosition]
        val modelOnly = selectedScope == WanxiangSelection.Scope.MODEL
        val extraOnly = selectedScope == WanxiangSelection.Scope.EXTRA
        val hasScheme = selectedScope in setOf(WanxiangSelection.Scope.ALL, WanxiangSelection.Scope.SCHEME)
        scope.isEnabled = !busy
        source.isEnabled = !busy && !extraOnly
        scheme.isEnabled = !busy && !modelOnly
        schemeRow.visibility = if (modelOnly) View.GONE else View.VISIBLE
        channel.isEnabled = !busy && hasScheme
        channelRow.visibility = if (hasScheme) View.VISIBLE else View.GONE
        channelHint.visibility = if (hasScheme) View.VISIBLE else View.GONE
        sourceRow.visibility = if (extraOnly) View.GONE else View.VISIBLE
        auxiliaryRow.visibility = if (!modelOnly && !extraOnly && scheme.selectedItemPosition == WanxiangSelection.Scheme.PRO.ordinal) View.VISIBLE else View.GONE
        auxiliary.isEnabled = !busy && !modelOnly
        extrasCard.visibility = if (selectedScope in setOf(WanxiangSelection.Scope.ALL, WanxiangSelection.Scope.DICTIONARY, WanxiangSelection.Scope.EXTRA)) View.VISIBLE else View.GONE
        scopeHint.setText(when (selectedScope) {
            WanxiangSelection.Scope.ALL -> R.string.wanxiang_scope_all_hint
            WanxiangSelection.Scope.SCHEME -> R.string.wanxiang_scope_scheme_hint
            WanxiangSelection.Scope.DICTIONARY -> R.string.wanxiang_scope_dictionary_hint
            WanxiangSelection.Scope.MODEL -> R.string.wanxiang_scope_model_hint
            WanxiangSelection.Scope.EXTRA -> R.string.wanxiang_scope_extra_hint
        })
        listOf(protectionButton, tokenButton, checkButton, extraButton, extraRefresh, advanced).forEach { it.isEnabled = !busy }
        update.isEnabled = !busy && targetAvailable && (!extraOnly || extras.isNotEmpty())
    }

    /** Very short windows retain all actions by scrolling them with the form. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        footer.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val short = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getSize(heightMeasureSpec) < maxOf(dp(420), footer.measuredHeight + dp(160))
        if (short != compact) {
            compact = short
            (footer.parent as ViewGroup).removeView(footer)
            if (short) content.addView(footer, LayoutParams(-1, -2)) else addView(footer, LayoutParams(-1, -2))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun disclosure(label: Int, body: View): View = EditorUi.card(context, box().apply {
        val toggle = EditorUi.button(context, label, tonal = true) {}.apply {
            setIconResource(R.drawable.ic_baseline_expand_more_24)
            iconGravity = MaterialButton.ICON_GRAVITY_END
            setOnClickListener {
                val expanded = body.visibility != View.VISIBLE
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                setIconResource(if (expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
                ViewCompat.setStateDescription(this, context.getString(if (expanded) R.string.wanxiang_expanded else R.string.wanxiang_collapsed))
            }
        }
        ViewCompat.setStateDescription(toggle, context.getString(R.string.wanxiang_collapsed))
        body.visibility = View.GONE
        addView(toggle, LayoutParams(-1, -2))
        addView(body, gap())
    })
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun box() = column().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
    private fun text(label: Int, title: Boolean = false): TextView = MaterialTextView(context).apply {
        setText(label)
        setTextAppearance(if (title) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(context.settingsColor(if (title) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
    private fun gap() = LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun block(bottom: Int = 16) = LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
