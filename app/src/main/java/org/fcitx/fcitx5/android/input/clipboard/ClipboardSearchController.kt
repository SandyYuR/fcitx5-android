/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.doOnTextChanged
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.input.voice.js.JsAppDialog
import org.fcitx.fcitx5.android.input.voice.js.JsAppEditText
import org.fcitx.fcitx5.android.input.voice.js.JsAppInputConnection
import org.fcitx.fcitx5.android.utils.settingsColor
import splitties.views.dsl.core.withTheme
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp

/** Native clipboard browser above the keyboard, with the AI panel's local input connection. */
internal object ClipboardSearchController {
    private var session: Session? = null
    val isActive: Boolean get() = session != null

    fun open(service: FcitxInputMethodService, category: ClipboardCategory?,
             returnToKeyboard: () -> Unit, splitText: (String) -> Unit) {
        if (service.editorSession.capture()?.isCurrent() != true || service.applicationInputConnection == null) return
        if (VoiceInputProviderManager.isActive()) VoiceInputProviderManager.stop(service)
        service.jsAppDismiss?.invoke()
        var created: Session? = null
        try {
            created = Session(service, category, returnToKeyboard, splitText,
                onClosed = { if (session === it) session = null })
            session = created
            created.start()
        } catch (failure: Exception) {
            created?.dialog?.dismiss()
            service.toast(failure.message ?: service.getString(R.string.unknown_error))
        }
    }

    fun stop() { session?.dialog?.dismiss() }

    internal class Session(
        private val service: FcitxInputMethodService,
        category: ClipboardCategory? = null,
        private val returnToKeyboard: () -> Unit = {},
        private val splitText: (String) -> Unit = {},
        keyboardView: () -> View? = { service.inputView?.keyboardView },
        private val present: (JsAppDialog) -> Unit = { service.showDialog(it) },
        private val onClosed: (Session) -> Unit = {},
    ) {
        private val token = service.editorSession.capture()
        private val connection = service.applicationInputConnection
        private var closed = false
        private var contentStarted = false
        internal val dialog = JsAppDialog(service, { _, _ -> }, ::close, keyboardView)
        private val dismissPanel: () -> Unit = { dialog.dismiss() }
        private val context = dialog.panelContext.withTheme(R.style.ThemeOverlay_FcitxSettings_ClipboardMenu)
        private val textColor = context.settingsColor(com.google.android.material.R.attr.colorOnSurface)
        private val mutedColor = context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        private val colors = ClipboardColors.panel(context)

        internal val query: EditText = JsAppEditText(context).apply {
            tag = "clipboard_query"
            textSize = 16f
            setTextColor(textColor); setHintTextColor(mutedColor)
            highlightColor = ColorUtils.setAlphaComponent(colors.primary, 0x40)
            hint = context.getString(R.string.clipboard_search_hint)
            background = null
            minHeight = 0; minimumHeight = 0
            includeFontPadding = false
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            showSoftInputOnFocus = false
            filters = arrayOf(InputFilter.LengthFilter(65536))
        }

        internal val content by lazy {
            ClipboardContent(context, ThemeManager.activeTheme, service,
                returnToKeyboard = { dialog.dismiss(); returnToKeyboard() },
                splitText = { dialog.dismiss(); splitText(it) },
                initialCategory = category, searchMode = true, pasteEntry = ::paste,
                onLoadError = { failure ->
                    if (isCurrent()) dialog.showError(if (failure == null) "" else service.getString(R.string.unknown_error))
                })
        }

        private fun isCurrent() = !closed && token?.isCurrent() == true &&
            service.applicationInputConnection === connection

        fun start() {
            if (!isCurrent() || connection == null) { dialog.dismiss(); return }
            service.finishComposing()
            service.resetJsAppEditorState()
            service.jsAppInputConnection = JsAppInputConnection.blocked(service)
            service.jsAppDismiss = dismissPanel
            val inputBox = dialog.nativeInputBox(query).apply {
                endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
                setEndIconTintList(ColorStateList.valueOf(mutedColor))
                endIconMinSize = context.dp(48)
                setEndIconOnClickListener {
                    service.resetJsAppEditorState()
                    query.setText("")
                    query.requestFocus()
                }
            }
            val closeButton = MaterialButton(context).apply {
                tag = "clipboard_search_close"
                text = ""
                contentDescription = context.getString(android.R.string.cancel)
                setIconResource(R.drawable.ic_settings_close)
                iconTint = ColorStateList.valueOf(mutedColor)
                iconSize = context.dp(20); iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                minWidth = context.dp(48); minimumWidth = context.dp(48)
                minHeight = context.dp(48); minimumHeight = context.dp(48)
                setPadding(context.dp(12), 0, context.dp(12), 0)
                insetTop = 0; insetBottom = 0; cornerRadius = context.dp(24)
                strokeWidth = 0
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                rippleColor = ColorStateList.valueOf(colors.ripple)
                setOnClickListener { dialog.dismiss() }
            }
            val footer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val inputHeight = maxOf(context.dp(48), query.lineHeight + query.paddingTop + query.paddingBottom + context.dp(8))
                    addView(inputBox, LinearLayout.LayoutParams(0, inputHeight, 1f))
                    addView(closeButton, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                        marginStart = context.dp(4)
                    })
                }, LinearLayout.LayoutParams(-1, -2))
                addView(content.ui.categoryBar, LinearLayout.LayoutParams(-1, -2))
            }
            val handle = FrameLayout(context).apply {
                minimumHeight = context.dp(12)
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(mutedColor); cornerRadius = context.dp(2).toFloat()
                    }
                }, FrameLayout.LayoutParams(context.dp(28), context.dp(4), Gravity.CENTER))
            }
            query.doOnTextChanged { text, _, _, _ ->
                if (isCurrent()) content.setQuery(text?.toString().orEmpty()) else dialog.dismiss()
            }
            content.ui.recyclerView.tag = "clipboard_search_results"
            dialog.setNativeContent(content.ui.root, footer, query, handle)
            contentStarted = true
            content.onAttached()
            present(dialog)
            if (!dialog.isShowing) dialog.dismiss()
        }

        private fun paste(entry: ClipboardEntry) {
            if (!isCurrent()) { dialog.dismiss(); return }
            dialog.dismiss()
            service.lifecycleScope.launch {
                // A file can suspend while staging. The service rechecks this connection after IO.
                if (token?.isCurrent() != true || service.applicationInputConnection !== connection ||
                    service.jsAppInputConnection != null) return@launch
                if (service.commitClipboardEntry(entry.text)) ClipboardManager.markUsed(entry.id)
            }
        }

        private fun close() {
            if (closed) return
            closed = true
            if (contentStarted) content.onDetached()
            if (service.jsAppDismiss === dismissPanel) {
                service.jsAppInputConnection = null
                service.jsAppDismiss = null
                service.resetJsAppEditorState()
            }
            onClosed(this)
        }
    }
}
