/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import kotlin.math.roundToInt
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.settingsColor

// Android's TYPE_APPLICATION_MEDIA_OVERLAY (1004) sits between TYPE_APPLICATION_MEDIA
// and its parent window. The platform accepts this type, but omits its name from the SDK.
private const val IME_PANEL_DROPDOWN_WINDOW_TYPE = 1004

/** Attached window; typing stays in the local editor while host APIs retain the app connection. */
internal class JsAppDialog(
    private val service: FcitxInputMethodService,
    private val event: (String, JsonObject) -> Unit,
    private val closed: () -> Unit,
    private val keyboardView: () -> View? = { service.inputView?.keyboardView },
    private val inputLimit: Int = 65536,
) : Dialog(service), LifecycleOwner {
    private val panelLifecycle = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = panelLifecycle
    private val uiContext = SettingsUiPreferences.themedContext(service, SettingsUiPreferences.read(service))
    private val surfaceColor = uiContext.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    private val textColor = uiContext.settingsColor(com.google.android.material.R.attr.colorOnSurface)
    private val mutedColor = uiContext.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val outlineColor = uiContext.settingsColor(com.google.android.material.R.attr.colorOutlineVariant)
    private val accentColor = uiContext.settingsColor(androidx.appcompat.R.attr.colorPrimary)
    private val onAccentColor = uiContext.settingsColor(com.google.android.material.R.attr.colorOnPrimary)
    private val tonalColor = uiContext.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer)
    private val onTonalColor = uiContext.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
    private val controls = linkedMapOf<String, View>()
    private val editors = linkedMapOf<String, EditText>()
    private val selections = linkedMapOf<String, String>()
    private val selectors = linkedMapOf<String, Pair<MaterialAutoCompleteTextView, List<Pair<String, String>>>>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val header = FrameLayout(uiContext)
    private val footer = FrameLayout(uiContext)
    private var nativeContent: View? = null
    private var nativeFooterScroll: BoundedScrollView? = null
    private var panelPaddingDp = 16
    private var layoutColumn: LinearLayout? = null
    private var headerView: View? = null
    private var actionsView: View? = null
    private var allScrollable = false
    private val content = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL; addView(content) }
    private val scroll = BoundedScrollView(uiContext).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
    private val status = label("", size = 12f).apply {
        setTextColor(uiContext.settingsColor(com.google.android.material.R.attr.colorOnErrorContainer))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(uiContext.settingsColor(com.google.android.material.R.attr.colorErrorContainer))
        }
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
        setPadding(dp(12), dp(8), dp(12), dp(8))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = LinearProgressIndicator(uiContext).apply {
        visibility = View.GONE
        isIndeterminate = true
        setIndicatorColor(accentColor)
        trackColor = outlineColor
        trackThickness = dp(3)
        trackCornerRadius = dp(2)
        contentDescription = service.getString(R.string.loading)
    }
    private val sections = LinearLayout(uiContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(header, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LinearLayout.LayoutParams(-1, -2))
        addView(status, LinearLayout.LayoutParams(-1, -2))
        addView(footer, LinearLayout.LayoutParams(-1, -2))
    }
    private val root = JsAppPanelLayout(uiContext, ::movePanel).apply {
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), outlineColor)
        }
        addView(sections, FrameLayout.LayoutParams(-1, -2))
        addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply {
            bottomMargin = dp(8); marginStart = dp(24); marginEnd = dp(24)
        })
    }
    private var prompt: CompletableDeferred<Boolean>? = null
    private var busy = false
    private var dismissed = false
    private var hasPresented = false
    private var maxWidthDp = 340
    private var maxHeightDp = 360
    private var moved = false
    private var panelX = 0
    private var panelY = 0
    private var observedKeyboard: View? = null
    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        applyBounds()
        // A pending keyboard layout can become ready without changing its screen bounds.
        root.invalidate()
    }
    private val firstDrawListener = ViewTreeObserver.OnPreDrawListener { prepareDraw() }

    init {
        root.setViewTreeLifecycleOwner(this)
        panelLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setContentView(root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setWindowAnimations(0)
        setPanelVisible(false)
        root.viewTreeObserver.addOnPreDrawListener(firstDrawListener)
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyBounds()
        }
        applyBounds()
    }

    override fun show() {
        if (dismissed) return
        // The shared IME dialog host adds dimming; this panel must leave the app and keyboard usable.
        window?.apply {
            // Keep the attached panel below its parent IME window. The real preedit, including
            // changing text/height and floating candidates, can then draw above it without
            // moving the panel or copying the encoding into a second view.
            attributes = attributes.apply { type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA }
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            setDimAmount(0f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        observedKeyboard = keyboardView()
        observedKeyboard?.viewTreeObserver?.addOnGlobalLayoutListener(keyboardLayoutListener)
        // Attach with measured bounds, rather than correcting an already visible window.
        applyBounds()
        super.show()
        panelLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun dismiss() {
        if (!dismissed) {
            dismissed = true
            // Card long-press/repeat jobs belong to this window, including a close during DOWN.
            panelLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            observedKeyboard?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(keyboardLayoutListener)
            observedKeyboard = null
            root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(firstDrawListener)
            editors.values.filterIsInstance<JsAppEditText>().forEach { it.ownsLocalInput = false }
            selectors.values.forEach { it.first.dismissDropDown() }
            prompt?.complete(false)
            closed()
        }
        super.dismiss()
    }

    fun values() = buildJsonObject {
        editors.forEach { (id, view) -> put(id, view.text.toString()) }
        selections.forEach { (id, value) -> put(id, value) }
    }

    val panelContext: Context get() = uiContext

    fun nativeInputBox(editor: EditText): TextInputLayout = fieldBox(editor)

    /** Native paged lists get their own viewport, never nested inside the script ScrollView. */
    fun setNativeContent(bodyView: View, footerView: View, editor: EditText, heading: View? = null) {
        check(nativeContent == null && content.childCount == 0)
        nativeContent = bodyView
        root.clipToOutline = true
        panelPaddingDp = 8
        sections.setPadding(dp(8), dp(8), dp(8), dp(8))
        sections.removeView(scroll)
        sections.addView(bodyView, 1, LinearLayout.LayoutParams(-1, 0))
        headerView = heading
        heading?.let { header.addView(it, FrameLayout.LayoutParams(-1, -2)) }
        actionsView = footerView
        nativeFooterScroll = BoundedScrollView(uiContext).apply {
            isVerticalScrollBarEnabled = false
            addView(footerView, ViewGroup.LayoutParams(-1, -2))
            footer.addView(this, FrameLayout.LayoutParams(-1, -2))
        }
        maxWidthDp = 600
        maxHeightDp = 440
        editors["query"] = editor
        editor.showSoftInputOnFocus = false
        editor.setOnFocusChangeListener { _, focused -> if (focused) bindEditor(editor) }
        editor.setOnTouchListener { _, _ -> bindEditor(editor); false }
        applyBounds()
        setBusy(busy)
    }

    fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value && prompt == null) View.VISIBLE else View.GONE
        controls.forEach { (id, view) ->
            val active = enabled[id] != false && (!value || id in setOf("close", "cancel"))
            view.isEnabled = active
            view.alpha = if (active) 1f else 0.5f
        }
        if (value) {
            blockTyping()
            selectors.values.forEach { it.first.dismissDropDown() }
        } else focusEditor()
    }

    fun showError(message: String) {
        status.text = message.take(500)
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        applyBounds()
    }

    fun setValue(id: String, value: String) {
        editors[id]?.let { it.setText(value.take(inputLimit)); it.setSelection(it.text.length) }
        selectors[id]?.let { (view, options) ->
            options.firstOrNull { it.second == value }?.let { (label, selected) ->
                selections[id] = selected
                view.setText(label, false)
            }
        }
    }

    fun setEnabled(id: String, value: Boolean) {
        enabled[id] = value
        setBusy(busy)
    }

    suspend fun confirm(title: String, message: String, allow: String, deny: String): Boolean {
        val reply = CompletableDeferred<Boolean>()
        prompt = reply
        content.visibility = View.GONE
        header.visibility = View.GONE
        footer.visibility = View.GONE
        progress.visibility = View.GONE
        blockTyping()
        val panel = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, bold = true, size = 13f, draggable = true))
            addView(label(message, size = 12f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(LinearLayout(uiContext).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(button(deny) { reply.complete(false) })
                addView(button(allow, style = "filled") { reply.complete(true) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        body.addView(panel)
        applyBounds()
        try { return reply.await() } finally {
            body.removeView(panel)
            content.visibility = View.VISIBLE
            prompt = null
            if (!dismissed) { applyBounds(); setBusy(busy) }
        }
    }

    fun render(spec: JsonObject) {
        selectors.values.forEach { it.first.dismissDropDown() }
        blockTyping()
        controls.clear(); editors.clear(); selections.clear(); selectors.clear(); enabled.clear()
        header.removeAllViews(); footer.removeAllViews()
        layoutColumn = null; headerView = null; actionsView = null; allScrollable = false
        content.removeAllViews()
        status.text = ""
        status.visibility = View.GONE
        var nodes = 0
        var hasTitle = false
        fun node(obj: JsonObject, depth: Int): View {
            require(depth <= 12 && ++nodes <= 128) { "Dialog layout is too large" }
            val id = obj.text("id")
            val view: View = when (obj.text("type")) {
                "actions" -> JsAppActionRow(uiContext).apply {
                    (obj["children"] as? JsonArray).orEmpty().forEach { child ->
                        require(child.jsonObject.text("type") == "button") { "Actions must contain buttons" }
                        addView(node(child.jsonObject, depth + 1))
                    }
                }
                "column", "row", "header" -> (if (obj.text("type") == "header") JsAppHeaderRow(uiContext)
                    else LinearLayout(uiContext)).apply {
                    orientation = if (obj.text("type") == "column") LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or when (obj.text("align")) {
                        "end" -> Gravity.END; "center" -> Gravity.CENTER_HORIZONTAL; else -> Gravity.START
                    }
                    val gap = dp((obj["gap"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 32) ?: 6)
                    (obj["children"] as? JsonArray).orEmpty().forEachIndexed { index, child ->
                        val c = child.jsonObject
                        val weight = (c["weight"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }?.coerceIn(0f, 20f) ?: 0f
                        // A field's height belongs to its input box, independently of its label.
                        val params = if (orientation == LinearLayout.HORIZONTAL)
                            LinearLayout.LayoutParams(if (weight > 0) 0 else -2, -2, weight)
                        else LinearLayout.LayoutParams(-1, if (weight > 0) 0 else -2, weight)
                        if (index > 0) {
                            if (orientation == LinearLayout.HORIZONTAL) params.marginStart = gap else params.topMargin = gap
                        }
                        addView(node(c, depth + 1), params)
                    }
                }
                "text" -> label(obj.text("text").take(400),
                    (obj["bold"] as? JsonPrimitive)?.booleanOrNull == true,
                    (obj["size"] as? JsonPrimitive)?.floatOrNull?.coerceIn(10f, 24f) ?: 14f,
                    (obj["muted"] as? JsonPrimitive)?.booleanOrNull == true, draggable = !hasTitle).also { text ->
                    hasTitle = true
                    icon(obj.text("icon"))?.let { drawable ->
                        drawable.setTint(accentColor)
                        drawable.setBounds(0, 0, dp(24), dp(24))
                        text.setCompoundDrawablesRelative(drawable, null, null, null)
                        text.compoundDrawablePadding = dp(10)
                    }
                    if (obj.text("tone") == "error") {
                        text.setTextColor(uiContext.settingsColor(com.google.android.material.R.attr.colorOnErrorContainer))
                        text.background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(uiContext.settingsColor(com.google.android.material.R.attr.colorErrorContainer))
                        }
                        text.setPadding(dp(12), dp(8), dp(12), dp(8))
                        text.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }
                    (obj["maxLines"] as? JsonPrimitive)?.intOrNull?.let { text.maxLines = it.coerceIn(1, 8) }
                    text.ellipsize = TextUtils.TruncateAt.END
                    obj.text("description").takeIf { it.isNotBlank() }?.let {
                        TooltipCompat.setTooltipText(text, it.take(600))
                    }
                }
                "input" -> {
                    val readOnly = (obj["readOnly"] as? JsonPrimitive)?.booleanOrNull == true
                    val edit = JsAppEditText(uiContext).apply {
                        setTextColor(if (readOnly) onTonalColor else textColor); setHintTextColor(mutedColor)
                        textSize = 16f
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = null
                        minHeight = 0; minimumHeight = 0
                        val multiline = (obj["multiline"] as? JsonPrimitive)?.booleanOrNull == true
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                            if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                        setSingleLine(!multiline)
                        gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
                        showSoftInputOnFocus = false
                        filters = arrayOf(InputFilter.LengthFilter(inputLimit))
                        setText(obj.text("value").take(inputLimit)); hint = obj.text("placeholder")
                        setSelection(if (readOnly) 0 else text?.length ?: 0)
                        if (readOnly) {
                            keyListener = null
                            isCursorVisible = false
                            setTextIsSelectable(true)
                        }
                        setOnFocusChangeListener { _, focused ->
                            if (focused && !dismissed) {
                                if (readOnly) blockTyping() else bindEditor(this)
                            }
                        }
                        setOnTouchListener { _, _ ->
                            if (isEnabled && !dismissed) {
                                if (readOnly) blockTyping() else bindEditor(this)
                            }
                            false
                        }
                    }
                    editors[id] = edit
                    field(obj, fieldBox(edit, readOnly), defaultHeight = if ((obj["multiline"] as? JsonPrimitive)?.booleanOrNull == true) 88 else 48)
                }
                "select" -> {
                    val options = (obj["options"] as? JsonArray).orEmpty().take(128).map { option ->
                        if (option is JsonObject) option.text("label").take(100) to option.text("value")
                        else option.jsonPrimitive.content.take(100) to option.jsonPrimitive.content
                    }
                    val selected = options.firstOrNull { it.second == obj.text("value") } ?: options.firstOrNull()
                    selections[id] = selected?.second.orEmpty()
                    val input = EditorPreservingSelect(uiContext, ::panelArea) { focusEditor() }.apply {
                        textSize = 14f
                        setTextColor(textColor); setHintTextColor(mutedColor)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        minHeight = 0; minimumHeight = 0
                        threshold = 0
                        inputType = InputType.TYPE_NULL
                        background = null
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        isCursorVisible = false
                        showSoftInputOnFocus = false
                        setAdapter(object : ArrayAdapter<String>(uiContext, R.layout.js_app_dropdown_item, android.R.id.text1,
                            options.map { it.first }) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                                super.getView(position, convertView, parent).apply {
                                    findViewById<TextView>(android.R.id.text1).setTextColor(textColor)
                                }
                        })
                        setText(selected?.first.orEmpty(), false)
                        setOnClickListener { if (isEnabled) showDropDown() }
                        setOnItemClickListener { _, _, position, _ ->
                            if (!busy && isEnabled && position in options.indices) {
                                val (label, value) = options[position]
                                setText(label, false)
                                selections[id] = value
                                event(id, values())
                            }
                        }
                        setDropDownBackgroundDrawable(GradientDrawable().apply {
                            setColor(surfaceColor); cornerRadius = dp(12).toFloat(); setStroke(dp(1), outlineColor)
                        })
                    }
                    selectors[id] = input to options
                    val box = fieldBox(input).apply {
                        endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
                        setEndIconTintList(ColorStateList.valueOf(mutedColor))
                        endIconMinSize = dp(32)
                    }
                    field(obj, box, defaultHeight = 48)
                }
                "button" -> button(obj.text("label").take(80), style = obj.text("style"),
                    iconName = obj.text("icon"), iconOnly = (obj["iconOnly"] as? JsonPrimitive)?.booleanOrNull == true) {
                    if (!busy || id in setOf("close", "cancel")) {
                        service.jsAppInputConnection?.finishComposingText()
                        event(id, values())
                    }
                }
                "spacer" -> View(uiContext).apply {
                    minimumHeight = dp((obj["height"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 320) ?: 0)
                }
                else -> error("Unsupported layout node")
            }
            if (id.isNotBlank()) {
                require(id !in controls) { "Duplicate control ID" }
                view.tag = id
                controls[id] = if (id in editors || id in selectors) {
                    // Enable/disable the whole Material box, including its dropdown icon.
                    (view as ViewGroup).getChildAt(view.childCount - 1)
                } else view
            } else require(obj.text("type") !in setOf("input", "select", "button")) { "Invalid control ID" }
            return view
        }
        val layout = spec["layout"] as? JsonObject ?: error("Dialog must declare a layout")
        val rendered = node(layout, 0)
        layoutColumn = (rendered as? LinearLayout)?.takeIf { it.orientation == LinearLayout.VERTICAL }
        layoutColumn?.let { column ->
            headerView = column.getChildAt(0) as? JsAppHeaderRow
            actionsView = column.getChildAt(column.childCount - 1) as? JsAppActionRow
            headerView?.let { column.removeView(it); header.addView(it, FrameLayout.LayoutParams(-1, -2)) }
            actionsView?.let { column.removeView(it); footer.addView(it, FrameLayout.LayoutParams(-1, -2)) }
        }
        footer.setPadding(0, if (actionsView == null) 0 else dp(8), 0, 0)
        content.addView(rendered, LinearLayout.LayoutParams(-1, -2))
        maxWidthDp = (spec["maxWidth"] as? JsonPrimitive)?.intOrNull?.coerceIn(200, 600) ?: 340
        maxHeightDp = (spec["maxHeight"] as? JsonPrimitive)?.intOrNull?.coerceIn(96, 600) ?: 360
        applyBounds()
        setBusy(busy)
    }

    private fun fieldBox(input: EditText, readOnly: Boolean = false) = TextInputLayout(uiContext).apply {
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = if (readOnly) tonalColor else surfaceColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursorColor = ColorStateList.valueOf(accentColor)
        }
        isHintEnabled = false
        setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
        boxStrokeWidth = if (readOnly) 0 else dp(1); boxStrokeWidthFocused = if (readOnly) 0 else dp(2)
        setBoxStrokeColorStateList(ColorStateList(arrayOf(
            intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_hovered), intArrayOf()),
            intArrayOf(outlineColor, accentColor, mutedColor, outlineColor)))
        addView(input, LinearLayout.LayoutParams(-1, -1))
    }

    private fun field(spec: JsonObject, box: TextInputLayout, defaultHeight: Int): View {
        val height = dp((spec["height"] as? JsonPrimitive)?.intOrNull?.coerceIn(30, 320) ?: defaultHeight)
        val top = spec.text("labelPlacement") == "top"
        return LinearLayout(uiContext).apply {
            orientation = if (top) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (spec.text("label").isNotBlank()) addView(label(spec.text("label"), size = 12f, muted = true).apply {
                setPadding(dp(2), 0, dp(8), if (top) dp(6) else 0)
                gravity = if (top) Gravity.START else Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(if (top) -1 else -2, if (top) -2 else height))
            addView(box, if (top || spec.text("label").isBlank()) LinearLayout.LayoutParams(-1, height)
                else LinearLayout.LayoutParams(0, height, 1f))
        }
    }

    private fun panelArea(): Rect {
        val metrics = service.resources.displayMetrics
        // Panel insets change as it moves; using them would feed its own position back into bounds.
        val imeInsets = keyboardView()?.rootView?.rootWindowInsets
            ?: service.window?.window?.decorView?.rootWindowInsets
        val insets = imeInsets?.let {
            WindowInsetsCompat.toWindowInsetsCompat(it).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
        val area = Rect(insets?.left ?: 0, insets?.top ?: 0,
            metrics.widthPixels - (insets?.right ?: 0), metrics.heightPixels - (insets?.bottom ?: 0))
        keyboardView()?.takeIf { it.isShown && it.height > 0 }?.let {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            if (location[1] > area.top) area.bottom = minOf(area.bottom, location[1])
        }
        area.inset(dp(8), dp(8))
        area.right = maxOf(area.right, area.left + 1)
        area.bottom = maxOf(area.bottom, area.top + 1)
        return area
    }

    private fun applyBounds(): Boolean {
        if (dismissed) return false
        val area = panelArea()
        val width = minOf(dp(maxWidthDp), area.width())
        val maximum = minOf(dp(maxHeightDp), area.height())
        root.maximumHeight = maximum
        val contentWidth = (width - dp(panelPaddingDp * 2)).coerceAtLeast(1)
        fun chromeHeight(view: View?): Int {
            view ?: return 0
            view.measure(View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return view.measuredHeight
        }
        val headerHeight = chromeHeight(headerView)
        val footerHeight = chromeHeight(actionsView) + footer.paddingTop
        val errorHeight = if (status.visibility == View.GONE) 0 else status.lineHeight * 3 + dp(16)
        val native = nativeContent
        if (native != null) {
            // Keep the result viewport independent of the fixed search/group controls. If even
            // the controls exceed a short window, they remain reachable by scrolling the footer.
            val available = (maximum - dp(panelPaddingDp * 2) - errorHeight).coerceAtLeast(1)
            header.visibility = if (available >= headerHeight + footerHeight + dp(48)) View.VISIBLE else View.GONE
            val headingHeight = if (header.visibility == View.VISIBLE) headerHeight else 0
            val chrome = minOf(footerHeight, (available - headingHeight - 1).coerceAtLeast(1))
            nativeFooterScroll?.maximumHeight = chrome
            footer.visibility = View.VISIBLE
            val bodyHeight = (available - headingHeight - chrome).coerceAtLeast(1)
            val params = native.layoutParams
            if (params.height != bodyHeight) { params.height = bodyHeight; native.layoutParams = params }
        } else {
            // Extremely short windows scroll as a whole; ordinary windows keep controls reachable.
            // Move existing views only when the mode changes, preserving editor/permission state.
            val scrollEverything = maximum < headerHeight + footerHeight + dp(112)
            updateSectionPlacement(scrollEverything)
            header.visibility = if (prompt == null && !allScrollable && headerView != null) View.VISIBLE else View.GONE
            footer.visibility = if (prompt == null && !allScrollable && actionsView != null) View.VISIBLE else View.GONE
            val fixedHeight = if (prompt == null && !allScrollable) headerHeight + footerHeight else 0
            scroll.maximumHeight = (maximum - dp(32) - errorHeight - fixedHeight).coerceAtLeast(1)
        }
        val height = if (native != null) {
            // A paged browser fills its bounded viewport. Measuring RecyclerView manually in
            // every pre-draw can keep requesting another traversal as its pages arrive.
            maximum
        } else {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST))
            root.measuredHeight.coerceAtLeast(1)
        }
        if (!moved) { panelX = area.centerX() - width / 2; panelY = area.bottom - height }
        // Keep the requested position through temporary sizes while a new form is measured.
        val x = panelX.coerceIn(area.left, maxOf(area.left, area.right - width))
        val y = panelY.coerceIn(area.top, maxOf(area.top, area.bottom - height))
        window?.let { w ->
            val attrs = w.attributes
            @Suppress("RtlHardcoded") val gravity = Gravity.TOP or Gravity.LEFT
            if (attrs.width != width || attrs.height != height ||
                attrs.gravity != gravity || attrs.x != x || attrs.y != y) {
                attrs.width = width; attrs.height = height
                attrs.gravity = gravity; attrs.x = x; attrs.y = y
                w.attributes = attrs
                root.requestLayout()
                return true
            }
        }
        return false
    }

    private fun updateSectionPlacement(scrollEverything: Boolean) {
        val column = layoutColumn ?: return
        if (allScrollable == scrollEverything) return
        allScrollable = scrollEverything
        headerView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            if (scrollEverything) column.addView(view, 0, LinearLayout.LayoutParams(-1, -2))
            else header.addView(view, FrameLayout.LayoutParams(-1, -2))
        }
        actionsView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            if (scrollEverything) column.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            else footer.addView(view, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun prepareDraw(): Boolean {
        if (dismissed) return true
        val keyboard = keyboardView()
        val keyboardVisible = keyboard == null || (keyboard.isShown && keyboard.isAttachedToWindow &&
            keyboard.width > 0 && keyboard.height > 0)
        val keyboardReady = keyboardVisible && (keyboard == null ||
            (keyboard.isLaidOut && !keyboard.isLayoutRequested))
        val hasContent = nativeContent != null || prompt != null || content.childCount > 0 || status.visibility == View.VISIBLE
        if (!hasContent || !keyboardVisible || (!keyboardReady && !hasPresented)) {
            setPanelVisible(false)
            return true
        }
        // The first frame waits for the keyboard. Once visible, keep the existing surface
        // through transient layout requests from clipboard/picker page switches.
        if (!keyboardReady) return true
        // Attribute changes in this traversal take effect at the next WindowManager layout.
        // Keep the first frame transparent until that final size/position has been laid out.
        if (applyBounds() || root.width != root.measuredWidth || root.height != root.measuredHeight) {
            root.requestLayout()
            return false
        }
        setPanelVisible(true)
        hasPresented = true
        return true
    }

    private fun setPanelVisible(visible: Boolean) {
        window?.let { w ->
            val alpha = if (visible) 1f else 0f
            val attrs = w.attributes
            val flags = if (visible) attrs.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else attrs.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (attrs.alpha != alpha || attrs.flags != flags) {
                attrs.alpha = alpha; attrs.flags = flags
                w.attributes = attrs
            }
        }
    }

    private fun movePanel(dx: Float, dy: Float) {
        moved = true
        panelX = (window?.attributes?.x ?: panelX) + dx.roundToInt()
        panelY = (window?.attributes?.y ?: panelY) + dy.roundToInt()
        applyBounds()
    }

    private fun blockTyping() {
        editors.values.filterIsInstance<JsAppEditText>().forEach { it.ownsLocalInput = false }
        service.jsAppInputConnection = JsAppInputConnection.blocked(service)
    }
    private fun focusEditor() {
        if (dismissed || busy || prompt != null) return
        blockTyping()
        editors.values.firstOrNull { it.isEnabled && it.keyListener != null }?.let {
            it.requestFocus(); bindEditor(it)
        }
    }
    private fun bindEditor(editor: EditText) {
        if (!dismissed && !busy && prompt == null && editor.isEnabled && editor.keyListener != null) {
            editors.values.filterIsInstance<JsAppEditText>().forEach { it.ownsLocalInput = it === editor }
            service.jsAppInputConnection = JsAppInputConnection(editor)
        }
    }
    private fun label(value: String, bold: Boolean = false, size: Float = 14f, muted: Boolean = false,
        draggable: Boolean = false) = (if (draggable) JsAppPanelTitle(uiContext) else MaterialTextView(uiContext)).apply {
        text = value; textSize = size; setTextColor(if (muted) mutedColor else textColor)
        setPadding(dp(2), 0, dp(2), 0)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(value: String, style: String = "outlined", iconName: String = "", iconOnly: Boolean = false,
        action: () -> Unit) = MaterialButton(uiContext).apply {
        val foreground = when (style) { "filled" -> onAccentColor; "tonal" -> onTonalColor; else -> accentColor }
        val drawable = icon(iconName)
        val onlyIcon = iconOnly && drawable != null
        text = if (onlyIcon) "" else value; contentDescription = value; textSize = 14f; isAllCaps = false
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        insetTop = 0; insetBottom = 0; cornerRadius = dp(24)
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(if (onlyIcon) 12 else 16), 0, dp(if (onlyIcon) 12 else 16), 0)
        setTextColor(foreground)
        drawable?.let {
            icon = it; iconSize = dp(20); iconPadding = if (onlyIcon) 0 else dp(8)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = ColorStateList.valueOf(foreground)
            if (onlyIcon) TooltipCompat.setTooltipText(this, value)
        }
        strokeWidth = if (style in setOf("filled", "tonal", "text")) 0 else dp(1)
        strokeColor = ColorStateList.valueOf(outlineColor)
        backgroundTintList = ColorStateList.valueOf(when (style) {
            "filled" -> accentColor; "tonal" -> tonalColor; "text" -> Color.TRANSPARENT; else -> surfaceColor
        })
        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 0x30))
        setOnClickListener { action() }
    }
    private fun icon(name: String) = when (name) {
        "assistant" -> R.drawable.ic_auto_awesome_24
        "close" -> R.drawable.ic_settings_close
        "send" -> R.drawable.ic_baseline_send_24
        "check" -> R.drawable.ic_baseline_check_24
        else -> null
    }?.let { AppCompatResources.getDrawable(uiContext, it)?.mutate() }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}

private class BoundedScrollView(context: Context) : ScrollView(context) {
    var maximumHeight = Int.MAX_VALUE
        set(value) { if (field != value) { field = value; requestLayout() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
            maximumHeight else minOf(size, maximumHeight)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}

/** A focusable popup would end the original app's IME session and cancel pending generation. */
private class EditorPreservingSelect(context: Context, private val availableArea: () -> Rect,
    private val closed: () -> Unit) : MaterialAutoCompleteTextView(context) {
    private var popup: ListPopupWindow? = null
    private var dismissListener: OnDismissListener? = null

    override fun showDropDown() {
        if (!isEnabled || popup?.isShowing == true) return
        val area = availableArea()
        val location = IntArray(2).also(::getLocationOnScreen)
        val below = (area.bottom - location[1] - height).coerceAtLeast(0)
        val above = (location[1] - area.top).coerceAtLeast(0)
        val maxHeight = (256 * resources.displayMetrics.density).roundToInt()
        val padding = Rect().also { dropDownBackground?.getPadding(it) }
        val rowParent = FrameLayout(context)
        val rowWidth = (width - padding.left - padding.right).coerceAtLeast(1)
        var desiredHeight = padding.top + padding.bottom
        var row: View? = null
        // Compact rows normally take 36 dp; font scaling and wrapped labels can make them taller.
        // Size the popup from those measured rows, not the system dropdown's fixed row height.
        for (position in 0 until adapter.count) {
            row = adapter.getView(position, row, rowParent)
            row.measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            desiredHeight = minOf(maxHeight, desiredHeight + row.measuredHeight)
            if (desiredHeight == maxHeight) break
        }
        val showAbove = below < desiredHeight && above > below
        val popupHeight = minOf(desiredHeight, if (showAbove) above else below).coerceAtLeast(1)
        popup = ListPopupWindow(context).apply {
            anchorView = this@EditorPreservingSelect
            isModal = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            // Above the panel's media sublayer, below the parent IME's live encoding area.
            setWindowLayoutType(IME_PANEL_DROPDOWN_WINDOW_TYPE)
            width = this@EditorPreservingSelect.width
            height = popupHeight
            if (showAbove) verticalOffset = -this@EditorPreservingSelect.height - popupHeight
            setAdapter(this@EditorPreservingSelect.adapter)
            setBackgroundDrawable(dropDownBackground)
            setOnItemClickListener { parent, view, position, id ->
                this@EditorPreservingSelect.onItemClickListener?.onItemClick(parent, view, position, id)
                dismiss()
            }
            setOnDismissListener {
                popup = null
                dismissListener?.onDismiss()
                closed()
            }
        }.also { it.show() }
    }

    override fun isPopupShowing() = popup?.isShowing == true
    override fun dismissDropDown() { popup?.dismiss() }
    override fun setOnDismissListener(listener: OnDismissListener?) { dismissListener = listener }
}
