/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ActivityEditorOptionsBinding
import splitties.views.topPadding
import timber.log.Timber
import java.io.File

class EditorOptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorOptionsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: FontFallbackAdapter
    private lateinit var touchHelper: androidx.recyclerview.widget.ItemTouchHelper

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityEditorOptionsBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
                bottomMargin = navBars.bottom
            }
            binding.toolbar.topPadding = statusBars.top
            insets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.editor_options)
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupTabWidth()
        setupExternalChange()
        setupFontList()
    }

    private fun setupExternalChange() {
        updateExternalChangeValue()
        binding.externalChangeRow.setOnClickListener {
            val values = arrayOf(EXTERNAL_CHANGE_OFF, EXTERNAL_CHANGE_PROMPT, EXTERNAL_CHANGE_AUTO)
            val labels = arrayOf(
                getString(R.string.external_change_off),
                getString(R.string.external_change_prompt),
                getString(R.string.external_change_auto),
            )
            val current = prefs.getString(PREF_EXTERNAL_CHANGE, EXTERNAL_CHANGE_PROMPT)
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.external_change_detection)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    prefs.edit().putString(PREF_EXTERNAL_CHANGE, values[which]).apply()
                    updateExternalChangeValue()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateExternalChangeValue() {
        binding.externalChangeValue.setText(
            when (prefs.getString(PREF_EXTERNAL_CHANGE, EXTERNAL_CHANGE_PROMPT)) {
                EXTERNAL_CHANGE_OFF -> R.string.external_change_off
                EXTERNAL_CHANGE_AUTO -> R.string.external_change_auto
                else -> R.string.external_change_prompt
            }
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupTabWidth() {
        binding.tabWidthInput.setText(
            prefs.getInt(PREF_TAB_WIDTH, DEFAULT_TAB_WIDTH).toString()
        )
        binding.tabWidthInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.toIntOrNull() ?: return
                val clamped = raw.coerceIn(MIN_TAB_WIDTH, MAX_TAB_WIDTH)
                prefs.edit().putInt(PREF_TAB_WIDTH, clamped).apply()
                // Don't snap the EditText to the clamped value mid-edit — the user may still be
                // typing "10" and shouldn't see "16" appear after the first "1". Snap on focus loss.
            }
        })
        binding.tabWidthInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = binding.tabWidthInput.text?.toString()?.toIntOrNull() ?: DEFAULT_TAB_WIDTH
                val clamped = raw.coerceIn(MIN_TAB_WIDTH, MAX_TAB_WIDTH)
                binding.tabWidthInput.setText(clamped.toString())
                prefs.edit().putInt(PREF_TAB_WIDTH, clamped).apply()
            }
        }
    }

    private fun setupFontList() {
        val entries = scanFontEntries(this, prefs.getString(PREF_FONT_FALLBACK, null).orEmpty())
            .toMutableList()
        adapter = FontFallbackAdapter(
            entries = entries,
            onChanged = { persistFontOrder() },
            onDelete = { confirmDelete(it) },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        binding.fontList.layoutManager = LinearLayoutManager(this)
        binding.fontList.adapter = adapter
        touchHelper = FontFallbackAdapter.makeTouchHelper(adapter) { persistFontOrder() }
        touchHelper.attachToRecyclerView(binding.fontList)
        updateEmptyHint()

        binding.importFont.setOnClickListener {
            // Wildcard — many providers misreport TTFs as application/octet-stream.
            pickFont.launch(arrayOf("*/*"))
        }
    }

    private fun updateEmptyHint() {
        binding.emptyHint.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun persistFontOrder() {
        val encoded = encodeFontOrder(adapter.snapshot())
        prefs.edit().putString(PREF_FONT_FALLBACK, encoded).apply()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_font)
            .setMessage(getString(R.string.confirm_remove_font, name))
            .setPositiveButton(R.string.remove_font) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { File(FontLoader.fontsDir(this@EditorOptionsActivity), name).delete() }
                    }
                    if (adapter.removeByName(name)) {
                        persistFontOrder()
                        updateEmptyHint()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_FONT_EXTS) {
            toast(R.string.invalid_font_file)
            return
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = FontLoader.fontsDir(this@EditorOptionsActivity)
                    val target = uniqueFile(dir, sanitizeName(displayName))
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("openInputStream returned null")
                    target.name
                }.onFailure { Timber.e(it, "Failed to import font from $uri") }
            }
            val name = saved.getOrNull()
            if (name == null) {
                toast(R.string.invalid_font_file)
                return@launch
            }
            // New imports are enabled by default — fewer taps for the common "add + use" case.
            adapter.add(FontEntry(name, enabled = true))
            persistFontOrder()
            updateEmptyHint()
            android.widget.Toast.makeText(
                this@EditorOptionsActivity,
                getString(R.string.font_imported, name),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx) ?: ""
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "font"
    }

    private fun toast(resId: Int) {
        android.widget.Toast.makeText(this, getString(resId), android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PREFS_NAME = "text_editor"
        const val PREF_TAB_WIDTH = "tab_width"
        const val PREF_FONT_FALLBACK = "font_fallback_files"

        const val PREF_EXTERNAL_CHANGE = "external_change_action"
        const val EXTERNAL_CHANGE_OFF = "off"
        const val EXTERNAL_CHANGE_PROMPT = "prompt"
        const val EXTERNAL_CHANGE_AUTO = "auto"

        const val DEFAULT_TAB_WIDTH = 4
        const val MIN_TAB_WIDTH = 1
        const val MAX_TAB_WIDTH = 16

        private val ALLOWED_FONT_EXTS = setOf("ttf", "otf", "ttc", "otc")
        private const val DISABLED_PREFIX = '!'

        // Read prefs into a List<FontEntry>, reconciled with what's currently on disk: removed files
        // are dropped; files on disk not mentioned in prefs are appended in alphabetic order with
        // enabled=false (so showing up doesn't silently start using a new font).
        fun scanFontEntries(context: Context, raw: String): List<FontEntry> {
            val onDisk = FontLoader.fontsDir(context)
                .listFiles { f -> f.isFile && f.extension.lowercase() in ALLOWED_FONT_EXTS }
                ?.map { it.name }
                ?.toMutableSet()
                ?: mutableSetOf()
            val ordered = mutableListOf<FontEntry>()
            raw.split(',').forEach { token ->
                val t = token.trim()
                if (t.isEmpty()) return@forEach
                val disabled = t.startsWith(DISABLED_PREFIX)
                val name = if (disabled) t.substring(1) else t
                if (name in onDisk) {
                    ordered.add(FontEntry(name, enabled = !disabled))
                    onDisk.remove(name)
                }
            }
            onDisk.sorted().forEach { ordered.add(FontEntry(it, enabled = false)) }
            return ordered
        }

        fun encodeFontOrder(entries: List<FontEntry>): String =
            entries.joinToString(",") { if (it.enabled) it.name else "$DISABLED_PREFIX${it.name}" }

        // Sequence of enabled fonts, in order, as used by FontLoader.loadTypeface.
        fun enabledFontFiles(raw: String): List<String> =
            raw.split(',').mapNotNull { token ->
                val t = token.trim()
                when {
                    t.isEmpty() -> null
                    t.startsWith(DISABLED_PREFIX) -> null
                    else -> t
                }
            }

        private fun sanitizeName(name: String): String {
            val safe = name.replace(Regex("[/\\\\,!\\s]"), "_")
            return if (safe.isEmpty()) "font" else safe
        }

        private fun uniqueFile(dir: File, name: String): File {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            var candidate = File(dir, name)
            var i = 1
            while (candidate.exists()) {
                val suffix = if (ext.isEmpty()) "_$i" else "_$i.$ext"
                candidate = File(dir, "$base$suffix")
                i++
            }
            return candidate
        }
    }
}
