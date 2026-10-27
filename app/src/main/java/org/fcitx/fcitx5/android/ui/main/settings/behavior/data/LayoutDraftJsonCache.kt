/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

/** Prepare JSON from detached history documents; the live editor is only inspected on Main. */
@MainThread
internal class LayoutDraftJsonCache(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val encode: suspend (LayoutDocument) -> String = { document ->
        LayoutJsonUtils.formatJsonCompact(LayoutJsonUtils.convertToSaveJson(
            document.entries, document.heights, document.landscapeHeights,
            document.auxConfigs, document.auxKeys)) + "\n"
    }
) {
    private data class Request(val id: Long, val document: LayoutDocument, val done: CompletableDeferred<Unit>)
    private data class Ready(val document: LayoutDocument, val json: String)
    private var revision = 0L
    private var requested: LayoutDocument? = null
    private var requestedDone: CompletableDeferred<Unit>? = null
    private var pending: Request? = null
    private var ready: Ready? = null
    private var worker: Job? = null

    fun prepare(document: LayoutDocument): Job {
        if (!scope.isActive) return CompletableDeferred(Unit)
        if (requested === document && (requestedDone?.isActive == true || ready?.document === document)) {
            return requireNotNull(requestedDone)
        }
        // The owner may be cleared before its worker gets a chance to start. Tie the
        // completion handle to its lifetime too, so that request cannot remain pending.
        val request = Request(++revision, document, CompletableDeferred(scope.coroutineContext[Job]))
        // Formatting is CPU work and may not observe cancellation promptly. Keep one worker
        // and just the newest pending document, rather than spawning overlapping encoders.
        pending?.done?.complete(Unit)
        pending = request
        requested = document
        requestedDone = request.done
        ready = null
        if (worker?.isActive != true) worker = scope.launch {
            try {
                while (isActive) {
                    val next = pending ?: break
                    pending = null
                    try {
                        val encoder = encode
                        val json = withContext(dispatcher) { encoder(next.document) }
                        if (next.id == revision) ready = Ready(next.document, json)
                    } catch (cancel: CancellationException) {
                        next.done.cancel(cancel)
                        throw cancel
                    } catch (error: Exception) {
                        if (next.id == revision) requested = null
                        android.util.Log.w("LayoutDraftJsonCache", "Draft preparation failed (${error.javaClass.simpleName})")
                    } finally {
                        next.done.complete(Unit)
                    }
                }
            } finally {
                pending?.done?.cancel()
                pending = null
            }
        }
        return request.done
    }

    /** A mismatch or unfinished request uses the existing synchronous save fallback. */
    fun valueFor(manager: LayoutDataManager): String? {
        val completed = ready ?: return null
        if (!scope.isActive || !runCatching { completed.document.matches(manager) }.getOrDefault(false)) return null
        return completed.json
    }

    fun clear() {
        revision++
        requested = null
        requestedDone = null
        ready = null
        pending?.done?.complete(Unit)
        pending = null
        // An in-progress encoder can finish without publishing; newer requests still share
        // this worker, keeping at most one encoder active even after rapid undo/clear/edit.
    }

    private fun LayoutDocument.matches(manager: LayoutDataManager): Boolean =
        entries == manager.entries && heights == manager.layoutHeightPercentOverrides &&
            landscapeHeights == manager.layoutHeightPercentOverridesLandscape &&
            auxConfigs == manager.layoutAuxBarConfigs && auxKeys == manager.layoutAuxBarKeys
}
