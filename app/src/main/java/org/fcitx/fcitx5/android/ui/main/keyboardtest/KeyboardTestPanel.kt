/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.keyboardtest

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.contentcapture.ContentCaptureManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorUi
import org.fcitx.fcitx5.android.utils.settingsColor

/** A transient editor using Android's real input contracts; no settings or draft storage. */
internal class KeyboardTestPanel(context: Context, close: () -> Unit) : LinearLayout(context) {
    enum class Kind(val label: Int, val hint: Int, val inputType: Int, val secret: Boolean = false) {
        TEXT(R.string.keyboard_test_text, R.string.keyboard_test_text_hint, InputType.TYPE_CLASS_TEXT),
        NUMBER(R.string.keyboard_test_number, R.string.keyboard_test_number_hint, InputType.TYPE_CLASS_NUMBER),
        PASSWORD(R.string.keyboard_test_password, R.string.keyboard_test_password_hint,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, true),
        PIN(R.string.keyboard_test_pin, R.string.keyboard_test_pin_hint,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, true)
    }

    var kind: Kind = Kind.TEXT
        private set
    val input = TextInputEditText(context).apply {
        id = R.id.keyboard_test_input
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        setFreezesText(false)
        setSingleLine(true)
        minHeight = dp(56)
        textSize = 16f
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO
    }
    private val field = TextInputLayout(context).apply {
        addView(input, LayoutParams(-1, -2))
        endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        setEndIconContentDescription(R.string.keyboard_test_clear)
    }
    val tabs = KeyboardTestTypeGroup(context)
    private val choices = Kind.entries.associateWith { item ->
        EditorUi.button(context, item.label, tonal = true) {}.apply {
            id = View.generateViewId()
            tag = item
            isCheckable = true
            minWidth = 0
            minimumWidth = 0
            textSize = 13f
            maxLines = 2
            setPadding(dp(4), dp(8), dp(4), dp(8))
            cornerRadius = dp(12)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            backgroundTintList = ColorStateList(states, intArrayOf(
                context.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer), Color.TRANSPARENT))
            setTextColor(ColorStateList(states, intArrayOf(
                context.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                context.settingsColor(com.google.android.material.R.attr.colorOnSurface))))
            strokeWidth = dp(1)
            strokeColor = ColorStateList(states, intArrayOf(
                context.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer),
                context.settingsColor(com.google.android.material.R.attr.colorOutlineVariant)))
        }
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        setPadding(dp(20), dp(12), dp(20), dp(16))
        if (Build.VERSION.SDK_INT == 29) {
            // Android 10 has no public per-view opt-out. Disable the host Activity's
            // capture session before attaching the editor, and keep it disabled for
            // that Activity's lifetime instead of re-enabling it when this panel closes.
            context.getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)
        }
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply {
                setText(R.string.keyboard_test_title)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                ViewCompat.setAccessibilityHeading(this, true)
            }, LayoutParams(0, -2, 1f))
            addView(EditorUi.icon(context, R.drawable.ic_settings_close, R.string.keyboard_test_close, close))
        }, LayoutParams(-1, -2))
        addView(MaterialTextView(context).apply {
            setText(R.string.keyboard_test_hint)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        choices.values.forEach(tabs::addView)
        addView(tabs, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(field, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        tabs.check(choices.getValue(kind).id)
        applyKind()
        tabs.onSelectionChanged = { id ->
            choices.entries.firstOrNull { it.value.id == id }?.key?.let(::select)
        }
    }

    fun select(next: Kind) {
        if (next == kind) return
        // A password must never reappear in a plain text field after changing tabs.
        if (kind.secret || next.secret) input.text?.clear()
        kind = next
        applyKind()
        tabs.check(choices.getValue(next).id)
        if (input.hasFocus()) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(input)
        }
    }

    private fun applyKind() {
        input.inputType = kind.inputType
        input.transformationMethod = if (kind.secret) PasswordTransformationMethod.getInstance() else null
        input.typeface = Typeface.DEFAULT
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            if (kind.secret) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0
        field.hint = context.getString(kind.hint)
        input.setSelection(input.text?.length ?: 0)
    }

    fun clear() {
        input.text?.clear()
        input.clearFocus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Four equal cells, or an equal 2-by-2 grid when scaled labels cannot fit in one row. */
internal class KeyboardTestTypeGroup(context: Context) : ViewGroup(context) {
    private val gap = (4 * resources.displayMetrics.density).toInt()
    private var selectedId = View.NO_ID
    private var columns = 4
    private var cellWidth = 0
    private var cellHeight = 0
    private var items = emptyList<MaterialButton>()
    val checkedButtonIds: List<Int> get() = if (selectedId == View.NO_ID) emptyList() else listOf(selectedId)
    var onSelectionChanged: (Int) -> Unit = {}

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        require(child is MaterialButton)
        child.setOnClickListener { check(child.id) }
    }

    fun check(id: Int) {
        if ((0 until childCount).none { getChildAt(it).id == id }) return
        val changed = selectedId != id
        selectedId = id
        // MaterialButton toggles itself before delivering its click. Rechecking the
        // current button keeps a required single selection rather than leaving none.
        repeat(childCount) { (getChildAt(it) as MaterialButton).isChecked = getChildAt(it).id == id }
        if (changed) onSelectionChanged(id)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        items = (0 until childCount).map { getChildAt(it) as MaterialButton }.filter { it.visibility != GONE }
        if (items.isEmpty()) { setMeasuredDimension(0, 0); return }
        val natural = items.maxOf { kotlin.math.ceil(it.paint.measureText(it.text.toString())).toInt() +
            it.compoundPaddingLeft + it.compoundPaddingRight }
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) natural * 4 + gap * 3
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        columns = if (natural <= (available - 3 * gap) / 4) 4 else 2
        cellWidth = ((available - (columns - 1) * gap) / columns).coerceAtLeast(0)
        val cellSpec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        items.forEach { it.measure(cellSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
        cellHeight = items.maxOf { it.measuredHeight }
        items.forEach { it.measure(cellSpec, MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)) }
        val rows = (items.size + columns - 1) / columns
        setMeasuredDimension(resolveSize(available + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(rows * cellHeight + (rows - 1) * gap + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val usedWidth = columns * cellWidth + (columns - 1) * gap
        val spare = (width - paddingLeft - paddingRight - usedWidth).coerceAtLeast(0) / 2
        items.forEachIndexed { index, item ->
            val offset = (index % columns) * (cellWidth + gap)
            val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - paddingRight - spare - offset - cellWidth
                else paddingLeft + spare + offset
            val y = paddingTop + (index / columns) * (cellHeight + gap)
            item.layout(x, y, x + cellWidth, y + cellHeight)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
