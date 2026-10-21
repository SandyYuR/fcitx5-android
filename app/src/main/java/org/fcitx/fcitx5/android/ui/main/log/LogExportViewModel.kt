/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/** Retains an export across picker/activity recreation; no Activity or live log list is retained. */
internal class LogExportViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    enum class Phase { IDLE, PREPARING, READY, PICKING, WRITING, SUCCESS, FAILURE }
    data class State(val phase: Phase, val error: Exception? = null) {
        val busy get() = phase != Phase.IDLE && phase != Phase.SUCCESS && phase != Phase.FAILURE
    }

    private val store = LogExportStore(application.cacheDir)
    private var pending: File? = store.restore(saved[FILE])
    private val mutableState = MutableStateFlow(State(when {
        saved.get<String>(PHASE) == Phase.PICKING.name -> Phase.PICKING
        pending == null -> Phase.IDLE
        else -> Phase.READY // Retry with a new document after process death during a write.
    }))
    val state = mutableState.asStateFlow()

    private fun change(phase: Phase, error: Exception? = null) {
        saved[FILE] = pending?.name
        saved[PHASE] = phase.name
        mutableState.value = State(phase, error)
    }

    fun prepare(header: String, lines: List<String>) {
        if (state.value.busy) return
        change(Phase.PREPARING)
        viewModelScope.launch {
            try {
                pending = withContext(Dispatchers.IO) {
                    store.removeExpired()
                    store.prepare(header, lines)
                }
                change(Phase.READY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("prepare", e)
            }
        }
    }

    fun launchPicker(): Boolean {
        if (state.value.phase != Phase.READY) return false
        change(Phase.PICKING)
        return true
    }

    fun pickerFailed(e: Exception) = fail("picker", e)

    fun destination(uri: Uri?) {
        if (state.value.phase != Phase.PICKING) return
        val file = pending
        if (uri == null) {
            discard()
            return
        }
        change(Phase.WRITING)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    try {
                        store.copy(file ?: throw IOException("Log snapshot is no longer available")) {
                            resolver.openOutputStream(uri, "w")
                        }
                    } catch (e: Exception) {
                        // CreateDocument supplies a new document. Do not leave a successful-looking
                        // zero-byte/partial export after an interrupted provider write.
                        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                            .getOrDefault(false)
                        Timber.w("Log export destination cleanup: deleted=%s", deleted)
                        throw e
                    }
                    file.delete()
                }
                pending = null
                change(Phase.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("write", e)
            }
        }
    }

    private fun fail(phase: String, error: Exception) {
        Timber.e(error, "Log export failed in %s", phase)
        change(Phase.FAILURE, error)
    }

    fun consumeResult() {
        if (state.value.phase == Phase.SUCCESS || state.value.phase == Phase.FAILURE) discard()
    }

    private fun discard() {
        val file = pending
        pending = null
        change(Phase.IDLE)
        viewModelScope.launch(Dispatchers.IO) { file?.delete() }
    }

    companion object {
        private const val FILE = "log_export_snapshot"
        private const val PHASE = "log_export_phase"
    }
}
