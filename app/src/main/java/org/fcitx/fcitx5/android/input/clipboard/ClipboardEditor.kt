/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.input.voice.js.JsAppDialog
import org.fcitx.fcitx5.android.input.voice.js.JsAppInputConnection
import org.fcitx.fcitx5.android.utils.toast

/** Native clipboard editing shares the IME panel; it never starts a new Activity or JS runtime. */
internal object ClipboardEditor {
    fun open(service: FcitxInputMethodService, id: Int) {
        if (id < 0 || service.editorSession.capture()?.isCurrent() != true) return
        if (VoiceInputProviderManager.isActive()) VoiceInputProviderManager.stop(service)
        ClipboardSearchController.stop()
        service.jsAppDismiss?.invoke()
        var session: Session? = null
        try { session = Session(service, id); session.start() }
        catch (failure: Exception) {
            session?.dialog?.dismiss()
            service.toast(failure.message ?: service.getString(R.string.clipboard_edit_load_failed))
        }
    }

    internal class Session(
        private val service: FcitxInputMethodService,
        private val id: Int,
        keyboardView: () -> android.view.View? = { service.inputView?.keyboardView },
        private val present: (JsAppDialog) -> Unit = { service.showDialog(it) },
    ) {
        private val token = service.editorSession.capture()
        private val connection = service.applicationInputConnection
        private val scope = CoroutineScope(service.lifecycleScope.coroutineContext + SupervisorJob(service.lifecycleScope.coroutineContext[Job]))
        private var closed = false
        private var ready = false
        private var saving = false
        // The native editor preserves existing long clipboard entries. Script panels retain 64 KiB.
        internal val dialog = JsAppDialog(service, ::event, ::close, keyboardView, inputLimit = Int.MAX_VALUE)
        private val dismissPanel: () -> Unit = { dialog.dismiss() }

        private fun isCurrent() = !closed && token?.isCurrent() == true && service.applicationInputConnection === connection

        fun start() {
            if (!isCurrent() || connection == null) { dialog.dismiss(); return }
            service.finishComposing()
            service.resetJsAppEditorState()
            service.jsAppInputConnection = JsAppInputConnection.blocked(service)
            service.jsAppDismiss = dismissPanel
            dialog.render(spec(""))
            dialog.setBusy(true)
            present(dialog)
            if (!dialog.isShowing) { dialog.dismiss(); return }
            scope.launch {
                try {
                    val entry = ClipboardManager.get(id)
                    if (!isCurrent()) { dialog.dismiss(); return@launch }
                    if (entry == null || entry.deleted) {
                        dialog.showError(service.getString(R.string.clipboard_edit_entry_missing))
                    } else {
                        ready = true
                        dialog.setValue("clipboard", entry.text)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (isCurrent()) dialog.showError(service.getString(R.string.clipboard_edit_load_failed))
                } finally {
                    if (isCurrent()) {
                        dialog.setBusy(false)
                        dialog.setEnabled("clipboard", ready)
                        dialog.setEnabled("confirm", ready)
                        dialog.setEnabled("copy", ready)
                    }
                }
            }
        }

        private fun event(event: String, values: JsonObject) {
            if (!isCurrent()) { dialog.dismiss(); return }
            if (event == "cancel" || event == "close") { dialog.dismiss(); return }
            if (event !in setOf("confirm", "copy") || !ready || saving) return
            val text = values["clipboard"]?.jsonPrimitive?.content ?: return
            saving = true
            dialog.showError("")
            dialog.setBusy(true)
            dialog.setEnabled("cancel", false)
            // This accepted transaction survives closing the panel, just like the Activity editor.
            val saved = ClipboardManager.saveEditedText(id, text, copy = event == "copy")
            scope.launch {
                try {
                    saved.await()
                    if (isCurrent()) dialog.dismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (isCurrent()) dialog.showError(service.getString(R.string.save_failed))
                } finally {
                    saving = false
                    if (isCurrent()) {
                        dialog.setEnabled("cancel", true)
                        dialog.setBusy(false)
                    }
                }
            }
        }

        private fun spec(text: String) = buildJsonObject {
            put("maxWidth", 360); put("maxHeight", 330)
            putJsonObject("layout") {
                put("type", "column"); put("gap", 4)
                putJsonArray("children") {
                    add(buildJsonObject {
                        put("type", "header")
                        putJsonArray("children") { add(buildJsonObject {
                            put("type", "text"); put("text", service.getString(R.string.clipboard_edit_text_hint))
                            put("size", 18); put("bold", true)
                        }) }
                    })
                    add(buildJsonObject {
                        put("type", "input"); put("id", "clipboard"); put("multiline", true)
                        put("height", 132); put("value", text)
                    })
                    add(buildJsonObject {
                        put("type", "actions")
                        putJsonArray("children") {
                            for ((id, label) in listOf("copy" to android.R.string.copy, "cancel" to android.R.string.cancel, "confirm" to android.R.string.ok)) {
                                add(buildJsonObject {
                                    put("type", "button"); put("id", id); put("label", service.getString(label))
                                    put("style", if (id == "confirm") "filled" else "text")
                                })
                            }
                        }
                    })
                }
            }
        }

        private fun close() {
            if (closed) return
            closed = true
            scope.cancel()
            if (service.jsAppDismiss === dismissPanel) {
                service.jsAppInputConnection = null
                service.jsAppDismiss = null
                service.resetJsAppEditorState()
            }
        }
    }
}
