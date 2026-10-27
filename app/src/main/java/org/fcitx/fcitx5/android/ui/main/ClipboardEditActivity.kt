/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.databinding.ActivityClipboardEditBinding
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.str
import timber.log.Timber

class ClipboardEditActivity : SettingsUiActivity() {
    override val floatingSettingsWindow: Boolean get() = true

    private val scope: CoroutineScope = MainScope()

    private lateinit var binding: ActivityClipboardEditBinding
    private val editText get() = binding.clipboardEditText

    private var entryId: Int = -1
    private var requestVersion = 0L
    private var saving = false
    private var loadJob: Job? = null
    private var saveObserver: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        binding = ActivityClipboardEditBinding.inflate(layoutInflater).apply {
            clipboardEditCancel.setOnClickListener { finish() }
            clipboardEditOk.setOnClickListener { finishEditing() }
            clipboardEditCopy.setOnClickListener { finishEditing(copy = true) }
        }
        setContentView(binding.root)
        processIntent(intent)
    }

    private fun finishEditing(copy: Boolean = false) {
        if (entryId < 0 || saving) return
        val version = requestVersion
        val str = editText.str
        saving = true
        updateControls()
        // Capture the target and text before another singleInstance Intent can replace them.
        val write = ClipboardManager.saveEditedText(entryId, str, copy)
        saveObserver = scope.launch {
            try {
                write.await()
                if (version == requestVersion) finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestVersion) {
                    Toast.makeText(this@ClipboardEditActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (version == requestVersion) {
                    saving = false
                    updateControls()
                    showEditorKeyboard(version)
                }
            }
        }
    }

    private fun updateControls() {
        val enabled = entryId >= 0 && !saving && !isFinishing && !isDestroyed
        editText.isEnabled = enabled
        binding.clipboardEditOk.isEnabled = enabled
        binding.clipboardEditCopy.isEnabled = enabled
        binding.clipboardEditCancel.isEnabled = !saving
    }

    private fun showEditorKeyboard(version: Long) {
        if (isFinishing || isDestroyed) return
        editText.post {
            if (version == requestVersion && entryId >= 0 && !saving && !isFinishing && !isDestroyed) {
                editText.requestFocus()
                inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun setEntry(entry: ClipboardEntry) {
        entryId = entry.id
        editText.setText(entry.text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        val version = ++requestVersion
        loadJob?.cancel()
        saveObserver?.cancel() // Cancels only observation, not the application-owned write.
        entryId = -1
        saving = false
        editText.setText("")
        updateControls()
        val id = intent.getIntExtra(ENTRY_ID, -1)
        val useLastEntry = intent.getBooleanExtra(LAST_ENTRY, false)
        val lastEntry = if (useLastEntry) ClipboardManager.lastEntry else null
        loadJob = scope.launch {
            try {
                val entry = if (useLastEntry) lastEntry else ClipboardManager.get(id)
                if (version != requestVersion || !isActive) return@launch
                if (entry == null || entry.deleted) {
                    Toast.makeText(this@ClipboardEditActivity, R.string.clipboard_edit_entry_missing, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    setEntry(entry)
                    updateControls()
                    showEditorKeyboard(version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestVersion) {
                    Timber.w("Unable to load clipboard entry (%s)", e.javaClass.simpleName)
                    Toast.makeText(this@ClipboardEditActivity, R.string.clipboard_edit_load_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ENTRY_ID = "id"
        const val LAST_ENTRY = "last_entry"
    }
}
