/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ActivityTextFileEditBinding
import splitties.views.topPadding
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets

class TextFileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextFileEditBinding
    private lateinit var docUri: Uri
    private lateinit var prefs: SharedPreferences
    private var displayName: String = ""
    private var originalText: String = ""
    private var saveItem: MenuItem? = null
    private var undoItem: MenuItem? = null
    private var redoItem: MenuItem? = null
    private var wordWrap: Boolean = true
    private var showWhitespace: Boolean = false
    private var useTab: Boolean = true
    private var fileSizeBytes: Long = 0L
    // Snapshot of the on-disk file at load/save time, used to detect external modifications when
    // the activity returns to the foreground. loadedFileSize < 0 means "not loaded yet".
    private var loadedLastModified: Long = 0L
    private var loadedFileSize: Long = -1L
    private var externalChangeDialogShowing: Boolean = false
    private var externalCheckInFlight: Boolean = false
    private var externalPollScheduled: Boolean = false
    private val externalChangePoll = Runnable {
        externalPollScheduled = false
        maybeCheckExternalChange()
        scheduleExternalChangePoll()
    }
    // Above TextFileSupport.LARGE_FILE_THRESHOLD: skip TextMate grammar and disable autocompletion,
    // both of which scan the whole buffer and stall the UI on multi-MB files.
    private var isLargeFile: Boolean = false
    private var deferredSyntaxHighlightPending: Boolean = false
    private var lowMemoryMode: Boolean = false
    private var lowMemoryNoticeShown: Boolean = false
    private var largeFilePager: LargeFilePager? = null
    private var largeFileFullyLoaded: Boolean = false
    private var largeFileLoadInFlight: Boolean = false
    private var largeFileDirty: Boolean = false
    private var suppressLargeFileDirtyTracking: Boolean = false
    private val largeFilePagerMutex = Mutex()
    private val crashHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Unhandled editor coroutine error")
        if (::binding.isInitialized) {
            binding.root.post {
                toast(getString(R.string.editor_runtime_recovered))
            }
        }
    }

    // Per-file draft on disk, used to survive process death. Filename hashes the URI so different
    // documents don't collide and special characters don't break paths.
    private val draftFile: File by lazy {
        val key = docUri.toString()
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(cacheDir, "fileedit/$hex.draft")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        docUri = uri
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            ?: DocumentFile.fromSingleUri(this, uri)?.name
            ?: uri.lastPathSegment
            ?: "?"

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        wordWrap = prefs.getBoolean(PREF_WORD_WRAP, true)
        showWhitespace = prefs.getBoolean(PREF_SHOW_WHITESPACE, false)
        useTab = prefs.getBoolean(PREF_USE_TAB, true)
        fileSizeBytes = fileLength()
        isLargeFile = TextFileSupport.isLargeFile(fileSizeBytes)
        deferredSyntaxHighlightPending = TextFileSupport.shouldUseDeferredSyntaxHighlight(
            fileSizeBytes,
            displayName,
        )
        // Force word-wrap off for large files — wrap layout reflows the entire buffer on width
        // changes. Don't persist this override; the user's saved pref still applies to small files.
        if (isLargeFile) wordWrap = false

        enableEdgeToEdge()
        binding = ActivityTextFileEditBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            // The keyBar floats above the IME, and the editor is constraint-anchored to keyBar's
            // top — so shrinking the editor when the keyboard opens just means pushing keyBar up.
            // bottomMargin (not padding) so the editor view actually shrinks; sora's completion
            // popup uses editor.getHeight() as its bottom bound, padding would leave it overlapping
            // the keyboard.
            binding.keyBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = maxOf(navBars.bottom, ime.bottom)
            }
            insets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            title = displayName
            subtitle = sizeSubtitle()
        }

        binding.editor.apply {
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            TextMateSetup.applyTheme(isDark, assets)
            colorScheme = TextMateSetup.createColorScheme(assets)
            // Must be after the assignment above: setColorScheme triggers attachEditor →
            // setTheme → colors.clear(), which wipes any setColor() done before attach.
            colorScheme.setColor(
                EditorColorScheme.NON_PRINTABLE_CHAR,
                if (isDark) 0x1ACCCCCC else 0x1A444444
            )
            // Matched-bracket highlight: sora enables matching by default but its color slots are
            // near-invisible on these TextMate themes. Render a translucent pill behind the pair.
            colorScheme.setColor(
                EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND,
                if (isDark) 0x40FFFFFF else 0x30000000
            )
            colorScheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, 0)
            colorScheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, 0)
            setEditorLanguage(
                TextMateSetup.createLanguage(initialScopeName(), assets, useTab)
            )
            // Note: don't install OnlineBracketsMatcher here — sora's setText() (called from
            // loadFile() later) runs styleDelegate.reset() which nulls bracketsProvider. Install
            // it after setText instead. Same caveat for the tab/space toggle path that re-creates
            // the language below.
            setTextSize(14f)
            // Falls back to Typeface.MONOSPACE when the user hasn't imported any fonts.
            applyUserTypeface(this)
            tabWidth = readTabWidth()
            setWordwrap(wordWrap)
            nonPrintablePaintingFlags = whitespaceFlags()
            props.disallowSuggestions = true
            props.cacheRenderNodeForLongLines = !isLargeFile
            // Default true: when the current line is entirely whitespace, Backspace deletes the
            // whole line + the preceding line break, surprising users who expected to delete just
            // one space/tab from the indent.
            props.deleteEmptyLineFast = false
            // overrideSymbolPairs is the parent of whatever Language.getSymbolPairs() returns —
            // our TextMate grammars ship without languageConfiguration files (no autoClosingPairs),
            // and PlainLanguage's pair set is empty, so without this nothing would auto-pair.
            installDefaultSymbolPairs(props.overrideSymbolPairs)
            if (isLargeFile) {
                getComponent(EditorAutoCompletion::class.java).isEnabled = false
                setHighlightCurrentBlock(false)
                setHighlightCurrentLine(false)
                setHighlightBracketPair(false)
                setDiagnostics(null)
            }
        }

        binding.editor.subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) {
            if (isLargeFile && !suppressLargeFileDirtyTracking) {
                largeFileDirty = true
            }
            // sora's UndoManager dispatches ContentChangeEvent *before* updating its stackPointer
            // (see UndoManager.undo/redo: action.undo(content) fires the event, then stackPointer--).
            // Reading canUndo/canRedo synchronously here returns the pre-undo state, which is why
            // undo stays enabled after undoing all the way back. Defer to the next frame so the
            // stack pointer has settled.
            binding.editor.post { updateMenuState() }
        }
        binding.editor.subscribeAlways(PublishSearchResultEvent::class.java) {
            updateMatchInfo()
        }
        binding.editor.subscribeAlways(ScrollEvent::class.java) {
            if (isLargeFile) tryLoadNextLargeFilePage()
        }

        setupSearchBar()
        setupKeyBar()

        loadFile()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchBar.visibility == View.VISIBLE) {
                    closeSearchBar()
                    return
                }
                if (isDirty()) {
                    AlertDialog.Builder(this@TextFileEditActivity)
                        .setTitle(R.string.unsaved_changes)
                        .setMessage(R.string.confirm_discard_changes)
                        .setPositiveButton(R.string.discard_changes) { _, _ -> finish() }
                        .setNeutralButton(R.string.save) { _, _ ->
                            saveFile { finish() }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            enterLowMemoryMode(level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        enterLowMemoryMode(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveItem = menu.add(R.string.save).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { saveFile(); true }
        }
        undoItem = menu.add(R.string.undo).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                if (binding.editor.canUndo()) binding.editor.undo()
                true
            }
        }
        redoItem = menu.add(R.string.redo).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                if (binding.editor.canRedo()) binding.editor.redo()
                true
            }
        }
        menu.add(R.string.find_replace).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { openSearchBar(); true }
        }
        menu.add(R.string.editor_options).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                startActivity(Intent(this@TextFileEditActivity, EditorOptionsActivity::class.java))
                true
            }
        }
        menu.add(R.string.word_wrap).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isCheckable = true
            isChecked = wordWrap
            isEnabled = !isLargeFile
            setOnMenuItemClickListener {
                wordWrap = !wordWrap
                isChecked = wordWrap
                binding.editor.setWordwrap(wordWrap)
                prefs.edit().putBoolean(PREF_WORD_WRAP, wordWrap).apply()
                true
            }
        }
        menu.add(R.string.show_whitespace).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isCheckable = true
            isChecked = showWhitespace
            setOnMenuItemClickListener {
                showWhitespace = !showWhitespace
                isChecked = showWhitespace
                binding.editor.nonPrintablePaintingFlags = whitespaceFlags()
                prefs.edit().putBoolean(PREF_SHOW_WHITESPACE, showWhitespace).apply()
                true
            }
        }
        menu.add(R.string.tab_inserts_spaces).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isCheckable = true
            isChecked = !useTab
            setOnMenuItemClickListener {
                useTab = !useTab
                isChecked = !useTab
                binding.editor.setEditorLanguage(
                    TextMateSetup.createLanguage(activeScopeName(), assets, useTab)
                )
                // setEditorLanguage runs styleDelegate.reset() → reinstall bracket matcher.
                binding.editor.installOnlineBracketsMatcher()
                prefs.edit().putBoolean(PREF_USE_TAB, useTab).apply()
                true
            }
        }
        updateMenuState()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        val editor = binding.editor
        val newTabWidth = readTabWidth()
        if (editor.tabWidth != newTabWidth) editor.tabWidth = newTabWidth
        applyUserTypeface(editor)
        maybeCheckExternalChange()
        scheduleExternalChangePoll()
    }

    override fun onPause() {
        super.onPause()
        stopExternalChangePolling()
        persistDraftOnPause()
    }

    private fun scheduleExternalChangePoll() {
        if (externalPollScheduled || !::binding.isInitialized) return
        val mode = prefs.getString(
            EditorOptionsActivity.PREF_EXTERNAL_CHANGE,
            EditorOptionsActivity.EXTERNAL_CHANGE_PROMPT
        )
        // Poll only while the feature is enabled.
        if (mode == EditorOptionsActivity.EXTERNAL_CHANGE_OFF) return
        externalPollScheduled = true
        binding.root.postDelayed(externalChangePoll, EXTERNAL_CHANGE_POLL_INTERVAL_MS)
    }

    private fun stopExternalChangePolling() {
        externalPollScheduled = false
        if (::binding.isInitialized) binding.root.removeCallbacks(externalChangePoll)
    }

    private fun captureFileSnapshot() {
        loadedLastModified = fileLastModified()
        loadedFileSize = fileLength()
    }

    private fun maybeCheckExternalChange() {
        // Not loaded yet, a prompt is already up, or a check is already running.
        if (loadedFileSize < 0 || externalChangeDialogShowing || externalCheckInFlight) return
        val mode = prefs.getString(
            EditorOptionsActivity.PREF_EXTERNAL_CHANGE,
            EditorOptionsActivity.EXTERNAL_CHANGE_PROMPT
        )
        if (mode == EditorOptionsActivity.EXTERNAL_CHANGE_OFF) return
        externalCheckInFlight = true
        lifecycleScope.launch(crashHandler) {
            // Some providers stat slowly — keep the metadata query off the main thread so polling
            // never janks the editor.
            val currentModified: Long
            val currentSize: Long
            withContext(Dispatchers.IO) {
                currentModified = fileLastModified()
                currentSize = fileLength()
            }
            externalCheckInFlight = false
            if (externalChangeDialogShowing) return@launch
            if (currentModified == loadedLastModified && currentSize == loadedFileSize) return@launch
            // Never silently discard unsaved edits — fall back to prompting even in auto mode.
            if (mode == EditorOptionsActivity.EXTERNAL_CHANGE_AUTO && !isDirty()) {
                reloadFromDisk()
            } else {
                promptExternalChange()
            }
        }
    }

    private fun promptExternalChange() {
        if (externalChangeDialogShowing) return
        externalChangeDialogShowing = true
        val message = if (isDirty()) {
            R.string.external_change_dialog_message_dirty
        } else {
            R.string.external_change_dialog_message
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.external_change_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.reload) { _, _ ->
                externalChangeDialogShowing = false
                reloadFromDisk()
            }
            .setNegativeButton(R.string.keep_editing) { _, _ ->
                externalChangeDialogShowing = false
                // Accept the current on-disk state as the new baseline so we stop re-prompting
                // for this same external change.
                captureFileSnapshot()
            }
            .setOnCancelListener {
                externalChangeDialogShowing = false
                captureFileSnapshot()
            }
            .show()
    }

    private fun reloadFromDisk() {
        if (isLargeFile) {
            runCatching { largeFilePager?.close() }
            largeFilePager = null
            largeFileFullyLoaded = false
            largeFileLoadInFlight = false
            largeFileDirty = false
            lifecycleScope.launch(crashHandler) {
                loadLargeFilePaged()
                toast(getString(R.string.file_reloaded))
            }
            return
        }
        lifecycleScope.launch(crashHandler) {
            val original = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(docUri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("openInputStream returned null")
                }
            } catch (e: Exception) {
                toast(getString(R.string.error_open_file, e.message ?: displayName))
                return@launch
            }
            originalText = original
            val cursor = binding.editor.cursor
            val prevLine = cursor.leftLine
            val prevColumn = cursor.leftColumn
            binding.editor.setText(original)
            // sora's setText triggers styleDelegate.reset(), which nulls bracketsProvider.
            binding.editor.installOnlineBracketsMatcher()
            runCatching {
                val line = prevLine.coerceIn(0, binding.editor.text.lineCount - 1)
                val col = prevColumn.coerceIn(0, binding.editor.text.getColumnCount(line))
                binding.editor.setSelection(line, col)
            }
            withContext(Dispatchers.IO) { runCatching { draftFile.delete() } }
            captureFileSnapshot()
            updateMenuState()
            toast(getString(R.string.file_reloaded))
        }
    }

    private fun readTabWidth(): Int =
        prefs.getInt(EditorOptionsActivity.PREF_TAB_WIDTH, EditorOptionsActivity.DEFAULT_TAB_WIDTH)
            .coerceIn(EditorOptionsActivity.MIN_TAB_WIDTH, EditorOptionsActivity.MAX_TAB_WIDTH)

    private fun applyUserTypeface(editor: io.github.rosemoe.sora.widget.CodeEditor) {
        val raw = prefs.getString(EditorOptionsActivity.PREF_FONT_FALLBACK, null).orEmpty()
        val files = EditorOptionsActivity.enabledFontFiles(raw)
        val tf = FontLoader.loadTypeface(this, files)
        // Identity check — FontLoader caches and returns the same Typeface across calls when the
        // resolved set hasn't changed; avoids resetting the editor's typeface (and the layout
        // recompute it triggers) on every onResume.
        if (editor.typefaceText !== tf) editor.typefaceText = tf
        if (editor.typefaceLineNumber !== tf) editor.typefaceLineNumber = tf
    }

    // Suppress TextMate highlighting for large files — the grammar tokenizer is the main source
    // of jank above a few MB. Falling back to a null scope makes TextMateSetup use PlainLanguage.
    private fun activeScopeName(): String? =
        if (isLargeFile || lowMemoryMode) null else TextFileSupport.detectScopeName(displayName)

    private fun initialScopeName(): String? =
        if (deferredSyntaxHighlightPending) null else activeScopeName()

    private fun installDefaultSymbolPairs(target: SymbolPairMatch) {
        target.putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
        target.putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
        target.putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
        // Quotes also surround a selection — typing " with text selected wraps it instead of replacing.
        val surroundOnSelection = object : SymbolPairMatch.SymbolPair.SymbolPairEx {
            override fun shouldDoAutoSurround(content: io.github.rosemoe.sora.text.Content): Boolean =
                content.cursor.isSelected
        }
        target.putPair('"', SymbolPairMatch.SymbolPair("\"", "\"", surroundOnSelection))
        target.putPair('\'', SymbolPairMatch.SymbolPair("'", "'", surroundOnSelection))
        target.putPair('`', SymbolPairMatch.SymbolPair("`", "`", surroundOnSelection))
    }

    private fun whitespaceFlags(): Int =
        if (showWhitespace) {
            // FOR_EMPTY_LINE is what paints leading whitespace on lines whose content is *only*
            // whitespace — without it, indented blank lines look completely blank.
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
                CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
                CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        } else {
            0
        }

    private fun setupSearchBar() {
        binding.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                if (query.isEmpty()) {
                    binding.editor.searcher.stopSearch()
                } else {
                    binding.editor.searcher.search(
                        query,
                        EditorSearcher.SearchOptions(/* caseInsensitive = */ false, /* useRegex = */ false)
                    )
                }
                updateMatchInfo()
            }
        })
        binding.findPrev.setOnClickListener {
            if (binding.editor.searcher.hasQuery()) binding.editor.searcher.gotoPrevious()
        }
        binding.findNext.setOnClickListener {
            if (binding.editor.searcher.hasQuery()) binding.editor.searcher.gotoNext()
        }
        binding.searchClose.setOnClickListener { closeSearchBar() }
        binding.replaceOne.setOnClickListener {
            if (!binding.editor.searcher.hasQuery()) return@setOnClickListener
            binding.editor.searcher.replaceThis(binding.replaceInput.text.toString())
        }
        binding.replaceAll.setOnClickListener {
            if (!binding.editor.searcher.hasQuery()) return@setOnClickListener
            binding.editor.searcher.replaceAll(binding.replaceInput.text.toString())
        }
    }

    private fun openSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        binding.findInput.requestFocus()
        val query = binding.findInput.text?.toString().orEmpty()
        if (query.isNotEmpty()) {
            binding.editor.searcher.search(
                query,
                EditorSearcher.SearchOptions(false, false)
            )
        }
        updateMatchInfo()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearchBar() {
        binding.editor.searcher.stopSearch()
        binding.searchBar.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.findInput.windowToken, 0)
        binding.editor.requestFocus()
    }

    private fun setupKeyBar() {
        binding.keySave.setOnClickListener {
            if (isDirty()) saveFile()
        }
        binding.keyUndo.setOnClickListener {
            if (binding.editor.canUndo()) binding.editor.undo()
        }
        binding.keyRedo.setOnClickListener {
            if (binding.editor.canRedo()) binding.editor.redo()
        }
        binding.keyTab.setOnClickListener { sendKey(KeyEvent.KEYCODE_TAB) }
        binding.keyHome.setOnClickListener { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        binding.keyEnd.setOnClickListener { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        binding.keyLeft.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        binding.keyUp.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        binding.keyDown.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        binding.keyRight.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        // Recompute even-fill vs scroll whenever the keyBar's width changes (orientation,
        // multi-window). The first pass also runs after the initial layout via post().
        binding.keysScroll.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) applyKeyBarDistribution()
        }
        binding.keysScroll.post { applyKeyBarDistribution() }
    }

    // Even-fill when all keys fit the viewport; fall back to natural width + horizontal scroll
    // when they don't. Avoids leaving a big empty gap on tablets/landscape and keeps small phones
    // scrollable instead of squishing keys below their minimum tap target.
    private fun applyKeyBarDistribution() {
        val container = binding.keysContainer
        val viewport = binding.keysScroll.width
        if (viewport <= 0 || container.childCount == 0) return
        // EditorKey style declares 30dp per key — that's the target width when not stretching.
        // Use it instead of measuring TextView text bounds, which would be narrower than the tap target.
        val naturalTotal = keyBarKeyWidthPx * container.childCount
        val fits = naturalTotal <= viewport
        val targetContainerWidth =
            if (fits) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (container.layoutParams.width != targetContainerWidth) {
            container.layoutParams.width = targetContainerWidth
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            if (fits) {
                lp.width = 0
                lp.weight = 1f
            } else {
                lp.width = keyBarKeyWidthPx
                lp.weight = 0f
            }
            child.layoutParams = lp
        }
        container.requestLayout()
    }

    // EditorKey style declares 30dp; cache the pixel value so re-distribution doesn't have to
    // re-resolve it on every pass.
    private val keyBarKeyWidthPx: Int by lazy {
        (30 * resources.displayMetrics.density).toInt()
    }

    private fun sendKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        binding.editor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        binding.editor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun updateMatchInfo() {
        val searcher = binding.editor.searcher
        val query = binding.findInput.text?.toString().orEmpty()
        binding.matchInfo.text = when {
            query.isEmpty() -> ""
            !searcher.hasQuery() -> ""
            searcher.matchedPositionCount == 0 -> getString(R.string.no_matches)
            else -> "${searcher.currentMatchedPositionIndex + 1}/${searcher.matchedPositionCount}"
        }
    }

    private fun updateMenuState() {
        val dirty = isDirty()
        val canUndo = binding.editor.canUndo()
        val canRedo = binding.editor.canRedo()
        saveItem?.isEnabled = dirty
        undoItem?.isEnabled = canUndo
        redoItem?.isEnabled = canRedo
        binding.keySave.isEnabled = dirty
        binding.keySave.alpha = if (dirty) 1f else 0.4f
        binding.keyUndo.isEnabled = canUndo
        binding.keyUndo.alpha = if (canUndo) 1f else 0.4f
        binding.keyRedo.isEnabled = canRedo
        binding.keyRedo.alpha = if (canRedo) 1f else 0.4f
        supportActionBar?.subtitle = if (dirty) {
            getString(R.string.unsaved_changes)
        } else {
            sizeSubtitle()
        }
    }

    private fun sizeSubtitle(): String {
        val size = formatSize(fileLength())
        return if (isLargeFile) "$size · ${getString(R.string.plain_mode)}" else size
    }

    private fun fileLength(): Long =
        DocumentFile.fromSingleUri(this, docUri)?.length() ?: 0L

    private fun fileLastModified(): Long =
        DocumentFile.fromSingleUri(this, docUri)?.lastModified() ?: 0L

    private fun loadFile() {
        lifecycleScope.launch(crashHandler) {
            if (isLargeFile) {
                loadLargeFilePaged()
                return@launch
            }
            val original = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(docUri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("openInputStream returned null")
                }
            } catch (e: Exception) {
                toast(getString(R.string.error_open_file, e.message ?: displayName))
                finish()
                return@launch
            }
            originalText = original
            captureFileSnapshot()
            // Only restore a draft when it is at least as fresh as the on-disk file —
            // otherwise the file has been edited externally and the draft is stale.
            val draft = withContext(Dispatchers.IO) {
                runCatching {
                    val f = draftFile
                    if (f.exists() && f.lastModified() >= fileLastModified()) f.readText() else null
                }.getOrNull()
            }
            binding.editor.setText(draft ?: original)
            // sora's setText triggers styleDelegate.reset(), which nulls bracketsProvider —
            // reinstall after the content is in place.
            binding.editor.installOnlineBracketsMatcher()
            scheduleDeferredSyntaxHighlightIfNeeded()
            updateMenuState()
        }
    }

    private fun scheduleDeferredSyntaxHighlightIfNeeded() {
        if (!deferredSyntaxHighlightPending || lowMemoryMode || isLargeFile) return
        val scope = TextFileSupport.detectScopeName(displayName) ?: run {
            deferredSyntaxHighlightPending = false
            return
        }
        binding.editor.postDelayed({
            if (!deferredSyntaxHighlightPending || lowMemoryMode || isLargeFile || isFinishing) {
                return@postDelayed
            }
            runCatching {
                binding.editor.setEditorLanguage(
                    TextMateSetup.createLanguage(scope, assets, useTab)
                )
                binding.editor.installOnlineBracketsMatcher()
                deferredSyntaxHighlightPending = false
            }.onFailure {
                Timber.e(it, "Deferred highlight activation failed")
                deferredSyntaxHighlightPending = false
            }
        }, DEFERRED_HIGHLIGHT_DELAY_MS)
    }

    private suspend fun loadLargeFilePaged() {
        val pager = try {
            withContext(Dispatchers.IO) { LargeFilePager(this@TextFileEditActivity, docUri) }
        } catch (e: Exception) {
            toast(getString(R.string.error_open_file, e.message ?: displayName))
            finish()
            return
        }
        largeFilePager = pager
        val firstPage = try {
            withContext(Dispatchers.IO) { pager.readNextTextPage().orEmpty() }
        } catch (e: Exception) {
            pager.close()
            largeFilePager = null
            toast(getString(R.string.error_open_file, e.message ?: displayName))
            finish()
            return
        }
        originalText = ""
        largeFileDirty = false
        captureFileSnapshot()
        withSuppressedLargeFileDirtyTracking {
            binding.editor.setText(firstPage)
        }
        // sora's setText triggers styleDelegate.reset(), which nulls bracketsProvider — reinstall
        // after the content is in place.
        binding.editor.installOnlineBracketsMatcher()
        largeFileFullyLoaded = pager.isFullyConsumed
        if (largeFileFullyLoaded) {
            pager.close()
            largeFilePager = null
        }
        updateMenuState()
        // If first page does not fill the viewport, continue paging immediately.
        tryLoadNextLargeFilePage(force = true)
    }

    private fun tryLoadNextLargeFilePage(force: Boolean = false) {
        if (!isLargeFile || largeFileLoadInFlight || largeFileFullyLoaded) return
        val pager = largeFilePager ?: return
        val remainingScroll = binding.editor.scrollMaxY - binding.editor.offsetY
        if (!force && remainingScroll > binding.editor.height * PREFETCH_VIEWPORT_MULTIPLIER) return

        largeFileLoadInFlight = true
        lifecycleScope.launch(crashHandler) {
            try {
                largeFilePagerMutex.withLock {
                    val nextPage = withContext(Dispatchers.IO) { pager.readNextTextPage() }
                    if (nextPage.isNullOrEmpty()) {
                        if (pager.isFullyConsumed) {
                            largeFileFullyLoaded = true
                            pager.close()
                            largeFilePager = null
                        }
                    } else {
                        withSuppressedLargeFileDirtyTracking {
                            appendTextToEditor(nextPage)
                        }
                        if (pager.isFullyConsumed) {
                            largeFileFullyLoaded = true
                            pager.close()
                            largeFilePager = null
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed paging read for $docUri")
                largeFileFullyLoaded = true
                runCatching { pager.close() }
                largeFilePager = null
                toast(getString(R.string.error_open_file, e.message ?: displayName))
            } finally {
                largeFileLoadInFlight = false
                updateMenuState()
            }
        }
    }

    private fun appendTextToEditor(text: String) {
        if (text.isEmpty()) return
        val content = binding.editor.text
        val lastLine = content.lineCount - 1
        val lastColumn = content.getColumnCount(lastLine)
        content.insert(lastLine, lastColumn, text)
    }

    private inline fun withSuppressedLargeFileDirtyTracking(block: () -> Unit) {
        val old = suppressLargeFileDirtyTracking
        suppressLargeFileDirtyTracking = true
        try {
            block()
        } finally {
            suppressLargeFileDirtyTracking = old
        }
    }

    private fun isDirty(): Boolean {
        if (isLargeFile) return largeFileDirty
        return binding.editor.text.toString() != originalText
    }

    private fun saveFile(onSuccess: (() -> Unit)? = null) {
        lifecycleScope.launch(crashHandler) {
            try {
                if (isLargeFile) {
                    loadRemainingLargeFilePagesForSave()
                }
                val content = binding.editor.text.toString()
                withContext(Dispatchers.IO) {
                    // "wt" = truncate-and-write. Without 't', some providers append rather than
                    // overwrite, leaving stale tail bytes when the new content is shorter.
                    contentResolver.openOutputStream(docUri, "wt")?.use {
                        it.write(content.toByteArray())
                    } ?: error("openOutputStream returned null")
                    runCatching { draftFile.delete() }
                }
                originalText = content
                if (isLargeFile) {
                    largeFileDirty = false
                }
                captureFileSnapshot()
                toast(getString(R.string.saved))
                updateMenuState()
                onSuccess?.invoke()
            } catch (e: Exception) {
                Timber.e(e, "Failed to save $docUri")
                toast(getString(R.string.error_save_file, e.message ?: ""))
            }
        }
    }

    private fun persistDraftOnPause() {
        if (!::docUri.isInitialized || !::binding.isInitialized || isLargeFile) return
        val current = binding.editor.text.toString()
        try {
            if (current != originalText) {
                draftFile.parentFile?.mkdirs()
                draftFile.writeText(current)
            } else if (draftFile.exists()) {
                draftFile.delete()
            }
        } catch (_: Exception) {
            // Best-effort; losing a draft is recoverable, crashing here is not.
        }
    }

    private suspend fun loadRemainingLargeFilePagesForSave() {
        if (!isLargeFile || largeFileFullyLoaded) return
        val pager = largeFilePager ?: return
        largeFileLoadInFlight = true
        try {
            largeFilePagerMutex.withLock {
                while (true) {
                    val nextPage = withContext(Dispatchers.IO) { pager.readNextTextPage() } ?: break
                    if (nextPage.isNotEmpty()) {
                        withSuppressedLargeFileDirtyTracking {
                            appendTextToEditor(nextPage)
                        }
                    }
                }
                if (pager.isFullyConsumed) {
                    largeFileFullyLoaded = true
                    pager.close()
                    largeFilePager = null
                }
            }
        } finally {
            largeFileLoadInFlight = false
        }
    }

    private fun enterLowMemoryMode(level: Int) {
        if (!::binding.isInitialized || lowMemoryMode) return
        lowMemoryMode = true
        deferredSyntaxHighlightPending = false
        runCatching {
            binding.editor.apply {
                setStyles(null)
                setDiagnostics(null)
                setEditorLanguage(TextMateSetup.createLanguage(null, assets, useTab))
                installOnlineBracketsMatcher()
                getComponent(EditorAutoCompletion::class.java).isEnabled = false
                props.cacheRenderNodeForLongLines = false
                props.disallowSuggestions = true
            }
            if (!lowMemoryNoticeShown) {
                lowMemoryNoticeShown = true
                toast(getString(R.string.low_memory_features_disabled))
            }
            Timber.w("Entered low memory mode, level=$level")
        }.onFailure {
            Timber.e(it, "Failed to degrade editor on low memory")
        }
    }

    override fun onDestroy() {
        runCatching { largeFilePager?.close() }
        largeFilePager = null
        if (::binding.isInitialized) binding.editor.release()
        super.onDestroy()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        private const val PREFS_NAME = "text_editor"
        private const val PREF_WORD_WRAP = "word_wrap"
        private const val PREF_SHOW_WHITESPACE = "show_whitespace"
        private const val PREF_USE_TAB = "use_tab"
        private const val LARGE_FILE_PAGE_BYTES = 256 * 1024
        private const val PREFETCH_VIEWPORT_MULTIPLIER = 2
        private const val DEFERRED_HIGHLIGHT_DELAY_MS = 180L
        private const val EXTERNAL_CHANGE_POLL_INTERVAL_MS = 3000L
    }

    private class LargeFilePager(
        private val context: Context,
        private val uri: Uri,
    ) : AutoCloseable {

        private var pfd: ParcelFileDescriptor? = null
        private var channel: FileChannel? = null
        private var mappedSize: Long = 0L
        private var mappedOffset: Long = 0L
        private var stream: BufferedInputStream? = null
        private var sourceExhausted: Boolean = false
        private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        private var carry: ByteArray = ByteArray(0)

        val isFullyConsumed: Boolean
            get() = sourceExhausted && carry.isEmpty()

        init {
            if (!openMappedSource()) {
                openStreamSource()
            }
        }

        fun readNextTextPage(): String? {
            if (isFullyConsumed) return null
            val bytes = when {
                channel != null -> readMappedBytes(LARGE_FILE_PAGE_BYTES)
                stream != null -> readStreamBytes(LARGE_FILE_PAGE_BYTES)
                else -> ByteArray(0)
            }
            if (bytes.isEmpty() && isFullyConsumed) {
                return null
            }
            return decodeUtf8(bytes, sourceExhausted)
        }

        private fun openMappedSource(): Boolean {
            return try {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
                val fileChannel = FileInputStream(descriptor.fileDescriptor).channel
                mappedSize = fileChannel.size()
                pfd = descriptor
                channel = fileChannel
                sourceExhausted = mappedSize == 0L
                true
            } catch (_: Exception) {
                runCatching { channel?.close() }
                runCatching { pfd?.close() }
                channel = null
                pfd = null
                false
            }
        }

        private fun openStreamSource() {
            stream = BufferedInputStream(
                context.contentResolver.openInputStream(uri)
                    ?: error("openInputStream returned null")
            )
            sourceExhausted = false
        }

        private fun readMappedBytes(maxBytes: Int): ByteArray {
            val fileChannel = channel ?: return ByteArray(0)
            val remaining = mappedSize - mappedOffset
            if (remaining <= 0) {
                sourceExhausted = true
                return ByteArray(0)
            }
            val toRead = minOf(maxBytes.toLong(), remaining).toInt()
            val mapped = fileChannel.map(FileChannel.MapMode.READ_ONLY, mappedOffset, toRead.toLong())
            val out = ByteArray(toRead)
            mapped.get(out)
            mappedOffset += toRead
            if (mappedOffset >= mappedSize) sourceExhausted = true
            return out
        }

        private fun readStreamBytes(maxBytes: Int): ByteArray {
            val input = stream ?: return ByteArray(0)
            val buf = ByteArray(maxBytes)
            val read = input.read(buf)
            if (read < 0) {
                sourceExhausted = true
                return ByteArray(0)
            }
            return if (read == buf.size) buf else buf.copyOf(read)
        }

        private fun decodeUtf8(bytes: ByteArray, endInput: Boolean): String {
            val merged = if (carry.isEmpty()) {
                bytes
            } else {
                ByteArray(carry.size + bytes.size).also {
                    carry.copyInto(it, 0)
                    bytes.copyInto(it, carry.size)
                }
            }
            val inBuffer = ByteBuffer.wrap(merged)
            var outBuffer = CharBuffer.allocate((merged.size * decoder.maxCharsPerByte()).toInt() + 8)
            while (true) {
                val result = decoder.decode(inBuffer, outBuffer, endInput)
                if (result.isOverflow) {
                    val grown = CharBuffer.allocate(outBuffer.capacity() * 2)
                    outBuffer.flip()
                    grown.put(outBuffer)
                    outBuffer = grown
                    continue
                }
                if (result.isError) result.throwException()
                break
            }
            if (endInput) {
                while (true) {
                    val flush = decoder.flush(outBuffer)
                    if (flush.isOverflow) {
                        val grown = CharBuffer.allocate(outBuffer.capacity() * 2)
                        outBuffer.flip()
                        grown.put(outBuffer)
                        outBuffer = grown
                        continue
                    }
                    if (flush.isError) flush.throwException()
                    break
                }
                decoder.reset()
                carry = ByteArray(0)
            } else {
                val remain = inBuffer.remaining()
                carry = ByteArray(remain)
                if (remain > 0) inBuffer.get(carry)
            }
            outBuffer.flip()
            return outBuffer.toString()
        }

        override fun close() {
            runCatching { stream?.close() }
            runCatching { channel?.close() }
            runCatching { pfd?.close() }
            stream = null
            channel = null
            pfd = null
            sourceExhausted = true
            carry = ByteArray(0)
            decoder.reset()
        }
    }
}
