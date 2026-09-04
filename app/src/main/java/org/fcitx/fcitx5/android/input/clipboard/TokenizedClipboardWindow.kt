/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.ClipData
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.mechdancer.dependency.manager.must

class TokenizedClipboardWindow(
    private val sourceText: String
) : InputWindow.SimpleInputWindow<TokenizedClipboardWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme: Theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private var tokens = emptyList<ClipboardToken>()
    private val adapter by lazy {
        TokenizedClipboardAdapter(theme) { selectedCount, totalCount ->
            ui.updateSelectionState(selectedCount, totalCount)
        }
    }

    private lateinit var ui: TokenizedClipboardUi

    override fun onCreateView() = TokenizedClipboardUi(context, theme).apply {
        ui = this
        recyclerView.layoutManager = FlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
        }
        recyclerView.adapter = adapter
        recyclerView.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(
                rv: androidx.recyclerview.widget.RecyclerView,
                e: android.view.MotionEvent
            ): Boolean = adapter.handleRecyclerTouch(e, rv)

            override fun onTouchEvent(
                rv: androidx.recyclerview.widget.RecyclerView,
                e: android.view.MotionEvent
            ) {
                adapter.handleRecyclerTouch(e, rv)
            }
        })
        backButton.setOnClickListener {
            windowManager.attachWindow(ClipboardWindow())
        }
        copyButton.setOnClickListener {
            val joined = currentSelectionText()
            if (joined.isBlank()) {
                Toast.makeText(context, R.string.tokenized_clipboard_empty_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            context.clipboardManager.setPrimaryClip(ClipData.newPlainText("TokenizedClipboard", joined))
            Toast.makeText(context, R.string.tokenized_clipboard_copied, Toast.LENGTH_SHORT).show()
        }
        selectAllButton.setOnClickListener {
            adapter.toggleSelectAll()
        }
        invertSelectionButton.setOnClickListener {
            adapter.invertSelection()
        }
        clearSelectionButton.setOnClickListener {
            adapter.clearSelection()
        }
        sendButton.setOnClickListener {
            val joined = currentSelectionText()
            if (joined.isBlank()) {
                Toast.makeText(context, R.string.tokenized_clipboard_empty_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            service.lifecycleScope.launch {
                service.commitClipboardEntry(joined)
            }
            adapter.clearSelection()
        }
    }.root

    /**
     * Tokenizing job for the current attach. Tracked so [onDetached] can cancel it: it used
     * to run on the service scope with no cancellation, so leaving and re-entering the window
     * stacked up jobs that each pushed their result into an adapter no longer in the layout.
     */
    private var tokenizeJob: Job? = null

    override fun onAttached() {
        ui.setEmptyState(true, isLoading = true)
        tokenizeJob?.cancel()
        tokenizeJob = service.lifecycleScope.launch(Dispatchers.Default) {
            val result = ClipboardTextTokenizer.tokenize(sourceText)
            // withContext, not a nested launch: the nested launch escaped this job, so
            // cancelling it could not stop the UI update.
            withContext(Dispatchers.Main) {
                tokens = result
                adapter.submitTokens(result)
                ui.setEmptyState(result.isEmpty(), isLoading = false)
            }
        }
    }

    override fun onDetached() {
        tokenizeJob?.cancel()
        tokenizeJob = null
    }

    private fun currentSelectionText(): String =
        ClipboardTextTokenizer.joinSelection(sourceText, adapter.selectedTokens())
}
