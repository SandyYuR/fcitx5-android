/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import androidx.core.content.edit
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.LoadState
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.clipboardsync.MainService
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.ClipboardSourceDeletionTarget
import org.fcitx.fcitx5.android.utils.EventStateMachine
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.core.withTheme

/** Shared cards, paging and entry actions for the keyboard page and attached search panel. */
internal class ClipboardContent(
    private val context: Context,
    private val theme: Theme,
    private val service: FcitxInputMethodService,
    private val returnToKeyboard: () -> Unit,
    private val splitText: (String) -> Unit,
    initialCategory: ClipboardCategory? = null,
    private val searchMode: Boolean = false,
    private val pasteEntry: ((ClipboardEntry) -> Unit)? = null,
    private val onLoadError: (Throwable?) -> Unit = {},
) {
    companion object {
        private const val LAST_OPENED_CATEGORY_KEY = "clipboard_last_opened_category"

        internal fun categoryPreferences() = PreferenceManager.getDefaultSharedPreferences(
            FcitxApplication.getInstance().directBootAwareContext)
    }

    private val snackbarCtx by lazy {
        context.withTheme(if (searchMode) R.style.ThemeOverlay_FcitxSettings_Snackbar else R.style.InputViewSnackbarTheme)
    }
    private val colors = if (searchMode) ClipboardColors.panel(context) else ClipboardColors.keyboard(theme)
    private var snackbarInstance: Snackbar? = null

    private lateinit var stateMachine: EventStateMachine<ClipboardStateMachine.State, ClipboardStateMachine.TransitionEvent, ClipboardStateMachine.BooleanKey>

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, it ->
        stateMachine.push(
            ClipboardListeningUpdated, ClipboardListeningEnabled to it
        )
    }

    private val prefs = AppPrefs.getInstance().clipboard
    private val sharedPreferences by lazy {
        categoryPreferences()
    }

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val clipboardEntryRadius by ThemeManager.prefs.clipboardEntryRadius

    private var currentCategory = resolveInitialCategory(initialCategory)
    private data class Filter(val category: ClipboardCategory, val query: String)
    private val filter = MutableStateFlow(Filter(currentCategory, ""))
    private val scope = CoroutineScope(service.lifecycleScope.coroutineContext +
        SupervisorJob(service.lifecycleScope.coroutineContext[Job]))
    private var adapterSubmitJob: Job? = null

    private fun resolveInitialCategory(category: ClipboardCategory?): ClipboardCategory {
        category?.let { return it }
        val saved = sharedPreferences.getString(LAST_OPENED_CATEGORY_KEY, ClipboardCategory.All.name).orEmpty()
        return ClipboardCategory.values().firstOrNull { it.name == saved } ?: ClipboardCategory.All
    }

    internal val adapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(
            theme,
            context.dp(clipboardEntryRadius.toFloat()),
            clipboardMaskSensitive,
            colors
        ) {
            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.unpin(id) }
            }

            override fun onEdit(id: Int) {
                returnToKeyboard()
                ClipboardEditor.open(service, id)
            }

            override fun onOpenFile(entry: ClipboardEntry) {
                val uri = runCatching { Uri.parse(entry.text) }.getOrNull() ?: return
                val mimeType = runCatching { context.contentResolver.getType(uri) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.type.takeIf {
                        it.isNotBlank() && it != ClipDescription.MIMETYPE_TEXT_URILIST
                    }
                    ?: "*/*"
                val target = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(chooser)
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.unknown_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onShare(entry: ClipboardEntry) {
                val target = if (entry.isUriEntry()) {
                    val uri = Uri.parse(entry.text)
                    val mimeType = runCatching { context.contentResolver.getType(uri) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: entry.type.takeIf {
                            it.isNotBlank() && it != ClipDescription.MIMETYPE_TEXT_URILIST
                        }
                        ?: "*/*"
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        clipData = ClipData.newUri(context.contentResolver, "clipboard", uri)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, entry.text)
                    }
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(chooser)
            }

            override fun shouldShowUpload(entry: ClipboardEntry): Boolean {
                return entry.text.isNotBlank() &&
                    currentCategory in setOf(ClipboardCategory.Favorites, ClipboardCategory.Local)
            }

            override fun onUpload(entry: ClipboardEntry) {
                MainService.submitCapturedClipboard(context, entry.text, "manual-upload")
            }

            override fun onSplitText(text: String) {
                splitText(text)
            }

            override fun onSearch(query: String) {
                val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(webSearchIntent)
                }
            }

            override fun onDial(number: String) {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.fromParts("tel", number, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onOpenLink(uri: android.net.Uri) {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onViewImage(uri: Uri) {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch {
                    maybeQueueRemoteSuppression(id)
                    maybeQueueMediaSourceDeletionTarget(id)
                    ClipboardManager.delete(id)
                    showUndoSnackbar(id)
                }
            }

            override fun onPaste(entry: ClipboardEntry) {
                pasteEntry?.let { it(entry); return }
                service.lifecycleScope.launch {
                    // suspend: staging the file runs on IO now (see commitClipboardEntry).
                    service.commitClipboardEntry(entry.text)
                    ClipboardManager.markUsed(entry.id)
                }
                if (clipboardReturnAfterPaste) returnToKeyboard()
            }
        }
    }

    private fun entriesPager(filter: Filter) = Pager(
        PagingConfig(
            pageSize = 16,
            enablePlaceholders = false
        )
    ) {
        ClipboardManager.entries(filter.category, filter.query)
    }

    private fun submitCategory(category: ClipboardCategory) {
        currentCategory = category
        sharedPreferences.edit { putString(LAST_OPENED_CATEGORY_KEY, category.name) }
        ui.setSelectedCategory(category)
        filter.value = filter.value.copy(category = category)
    }

    fun setQuery(query: String) {
        filter.value = filter.value.copy(query = query)
    }

    val ui by lazy {
        ClipboardUi(context, theme, searchMode, colors).apply {
            recyclerView.apply {
                layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                itemAnimator = null
                adapter = this@ClipboardContent.adapter
            }
            setSelectedCategory(currentCategory)
            setOnCategorySelectedListener(::submitCategory)
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val entry = adapter.getEntryAt(viewHolder.bindingAdapterPosition) ?: return
                    service.lifecycleScope.launch {
                        maybeQueueRemoteSuppression(entry.id)
                        maybeQueueMediaSourceDeletionTarget(entry.id)
                        ClipboardManager.delete(entry.id)
                        showUndoSnackbar(entry.id)
                    }
                }
            }).attachToRecyclerView(recyclerView)
            enableUi.enableButton.setOnClickListener {
                clipboardEnabledPref.setValue(true)
            }
            deleteAllButton.setOnClickListener {
                service.lifecycleScope.launch {
                    promptDeleteAll(ClipboardManager.haveUnpinned(currentCategory))
                }
            }
            searchButton.setOnClickListener {
                service.startClipboardSearch(currentCategory)
            }
        }
    }

    private var promptMenu: PopupMenu? = null

    private fun promptDeleteAll(skipPinned: Boolean) {
        promptMenu?.dismiss()
        promptMenu = PopupMenu(context, ui.deleteAllButton).apply {
            menu.add(buildSpannedString {
                bold {
                    color(context.styledColorOrDefault(android.R.attr.colorAccent, theme.genericActiveForegroundColor)) {
                        append(context.getString(if (skipPinned) R.string.delete_all_except_pinned else R.string.delete_all_pinned_items))
                    }
                }
            }).isEnabled = false
            menu.add(android.R.string.cancel)
            menu.item(android.R.string.ok) {
                service.lifecycleScope.launch {
                    deleteEntries(skipPinned, deleteFiles = false)
                }
            }
            setOnDismissListener {
                if (it === promptMenu) promptMenu = null
            }
            show()
        }
    }

    private val pendingDeleteIds = arrayListOf<Int>()
    private val pendingSuppressedRemoteContents = linkedSetOf<String>()
    private val pendingDeleteSourceTargets = linkedMapOf<Int, ClipboardSourceDeletionTarget>()

    private suspend fun deleteEntries(skipPinned: Boolean, deleteFiles: Boolean) {
        pendingSuppressedRemoteContents += ClipboardManager.remoteSuppressionContents(currentCategory, skipPinned)
        if (currentCategory == ClipboardCategory.Media) {
            pendingDeleteSourceTargets.putAll(ClipboardManager.mediaDeletionTargets(skipPinned))
        } else if (currentCategory == ClipboardCategory.Remote) {
            pendingDeleteSourceTargets.putAll(
                ClipboardManager.mediaDeletionTargetsBySource(
                    source = ClipboardEntry.SOURCE_REMOTE,
                    skipPinned = skipPinned
                )
            )
        }
        val ids = ClipboardManager.deleteAll(currentCategory, skipPinned)
        showUndoSnackbar(*ids)
    }

    private suspend fun maybeQueueRemoteSuppression(id: Int) {
        ClipboardManager.remoteSuppressionContent(id)?.let { pendingSuppressedRemoteContents += it }
    }

    private suspend fun maybeQueueMediaSourceDeletionTarget(id: Int) {
        ClipboardManager.mediaDeletionTarget(id)?.let { pendingDeleteSourceTargets[id] = it }
    }

    private fun notifyClipboardPluginSuppressedRemoteContents(contents: Collection<String>) {
        if (contents.isEmpty()) return
        MainService.suppressRemoteClipboardContents(context, contents)
    }

    @SuppressLint("RestrictedApi")
    private fun showUndoSnackbar(vararg id: Int) {
        id.forEach { pendingDeleteIds.add(it) }
        val str = context.resources.getString(R.string.num_items_deleted, pendingDeleteIds.size)
        snackbarInstance = Snackbar.make(snackbarCtx, ui.root, str, Snackbar.LENGTH_LONG)
            .setBackgroundTint(colors.snackbar)
            .setTextColor(colors.onSnackbar)
            .setActionTextColor(colors.snackbarAction)
            .setAction(R.string.undo) {
                service.lifecycleScope.launch {
                    ClipboardManager.undoDelete(*pendingDeleteIds.toIntArray())
                    pendingDeleteIds.clear()
                    pendingSuppressedRemoteContents.clear()
                    pendingDeleteSourceTargets.clear()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (snackbarInstance === transientBottomBar) {
                        snackbarInstance = null
                    }
                    when (event) {
                        BaseCallback.DISMISS_EVENT_SWIPE,
                        BaseCallback.DISMISS_EVENT_MANUAL,
                        BaseCallback.DISMISS_EVENT_TIMEOUT -> {
                            service.lifecycleScope.launch {
                                notifyClipboardPluginSuppressedRemoteContents(pendingSuppressedRemoteContents)
                                ClipboardManager.deleteClipboardSourceFiles(pendingDeleteSourceTargets.values)
                                ClipboardManager.realDelete()
                                pendingDeleteIds.clear()
                                pendingSuppressedRemoteContents.clear()
                                pendingDeleteSourceTargets.clear()
                            }
                        }

                        BaseCallback.DISMISS_EVENT_ACTION,
                        BaseCallback.DISMISS_EVENT_CONSECUTIVE -> {
                            // user clicked "undo" or deleted more items which makes a new snackbar
                        }
                    }
                }
            }).apply {
                val hMargin = snackbarCtx.dp(24)
                val vMargin = snackbarCtx.dp(16)
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    leftMargin = hMargin
                    rightMargin = hMargin
                    bottomMargin = vMargin
                }
                ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                    messageView.letterSpacing = 0f
                    actionView.letterSpacing = 0f
                }
                show()
            }
    }

    fun onAttached() {
        val isEmpty = ClipboardManager.itemCount == 0
        val isListening = searchMode || clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            ui.switchUiByState(it)
        }
        // manually switch to initial ui
        ui.switchUiByState(initialState)
        adapter.addLoadStateListener {
            onLoadError((it.refresh as? LoadState.Error)?.error)
            if (it.refresh is LoadState.NotLoading) {
                val empty = it.append.endOfPaginationReached && adapter.itemCount < 1
                stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to empty)
            }
        }
        submitCategory(currentCategory)
        adapterSubmitJob = scope.launch {
            filter.collectLatest { selection ->
                // The cancelled collector cannot publish an older query or category's page.
                if (selection.query.isNotEmpty()) delay(120)
                ui.recyclerView.scrollToPosition(0)
                entriesPager(selection).flow.collectLatest { adapter.submitData(it) }
            }
        }
        if (!searchMode) clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
    }

    fun onDetached() {
        if (!searchMode) clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        adapter.onDetached()
        adapterSubmitJob?.cancel()
        scope.cancel()
        promptMenu?.dismiss()
        snackbarInstance?.dismiss()
    }

}
