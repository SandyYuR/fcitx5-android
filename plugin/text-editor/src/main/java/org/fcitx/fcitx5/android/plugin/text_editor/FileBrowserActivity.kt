/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ActivityFileBrowserBinding
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ItemFileEntryBinding
import splitties.views.topPadding

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding

    private var rootTree: DocumentFile? = null
    private var currentDir: DocumentFile? = null
    private val entries = mutableListOf<Entry>()
    private val adapter = FileAdapter()

    private data class Entry(
        val doc: DocumentFile?,
        val name: String,
        val isParent: Boolean,
        val isDirectory: Boolean
    )

    private lateinit var prefs: SharedPreferences

    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            if (rootTree == null) {
                toast(getString(R.string.grant_access_denied))
            }
            // Stay on the empty-state screen so the user can retry.
            return@registerForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers don't allow persisting; still usable for this session.
        }
        prefs.edit().putString(PREF_TREE_URI, uri.toString()).apply()
        openTree(uri)
    }

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Persisting depends on the provider; continue with transient grant.
        }
        val doc = DocumentFile.fromSingleUri(this, uri)
        val displayName = doc?.name ?: uri.lastPathSegment ?: "?"
        openFileUri(uri, displayName, doc?.type ?: contentResolver.getType(uri), doc?.length() ?: -1L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        enableEdgeToEdge()
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            binding.recyclerView.setPadding(0, 0, 0, navBars.bottom)
            insets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(!isTaskRoot)
            setTitle(R.string.edit_user_files)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val root = rootTree ?: run { finish(); return }
                val current = currentDir ?: run { finish(); return }
                if (current.uri == root.uri) {
                    finish()
                    return
                }
                val parent = current.parentFile
                if (parent != null && isInsideRoot(parent, root)) {
                    navigateTo(parent)
                } else {
                    navigateTo(root)
                }
            }
        })

        // Try to resume from a persisted grant. Fall back to picker if none / revoked.
        val saved = prefs.getString(PREF_TREE_URI, null)?.let(Uri::parse)
        val persistedOk = saved != null && contentResolver.persistedUriPermissions.any {
            it.uri == saved && it.isReadPermission && it.isWritePermission
        }
        if (saved != null && persistedOk) {
            openTree(saved)
        } else {
            showEmptyStateAndPick()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(R.string.change_root_folder).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                launchPicker()
                true
            }
        }
        menu.add(R.string.open_single_file).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                pickFile.launch(arrayOf("*/*"))
                true
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning from editor (file size may have changed)
        currentDir?.let { navigateTo(it, preserveScroll = true) }
    }

    private fun showEmptyStateAndPick() {
        binding.pathBar.text = ""
        entries.clear()
        adapter.notifyDataSetChanged()
        binding.emptyView.text = getString(R.string.grant_access_message)
        binding.emptyView.visibility = View.VISIBLE
        launchPicker()
    }

    private fun launchPicker() {
        val initial = guessInitialUri()
        try {
            pickTree.launch(initial)
        } catch (e: Exception) {
            // If the launch fails (e.g. no SAF activity), fall back to a plain picker.
            pickTree.launch(null)
        }
    }

    private fun guessInitialUri(): Uri? {
        // Try plausible main-app package names; the first installed one wins.
        val candidates = linkedSetOf(
            BuildConfig.MAIN_APPLICATION_ID,
            "org.fcitx.fcitx5.android.fx",
            "org.fcitx.fcitx5.android.fx.debug",
            "org.fcitx.fcitx5.android",
            "org.fcitx.fcitx5.android.debug"
        )
        for (pkg in candidates) {
            if (isPackageInstalled(pkg)) {
                return DocumentsContract.buildTreeDocumentUri("$pkg.provider", "files")
            }
        }
        return null
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun openTree(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.canRead()) {
            // Grant is gone — clear preference and re-prompt.
            prefs.edit().remove(PREF_TREE_URI).apply()
            showEmptyStateAndPick()
            return
        }
        rootTree = root
        navigateTo(root)
    }

    private fun navigateTo(dir: DocumentFile, preserveScroll: Boolean = false) {
        val root = rootTree ?: return
        currentDir = dir
        binding.pathBar.text = displayPath(dir, root)
        val children = dir.listFiles().toList()
        val (dirs, files) = children.partition { it.isDirectory }
        val sortedDirs = dirs.sortedBy { (it.name ?: "").lowercase() }
        val sortedFiles = files.sortedBy { (it.name ?: "").lowercase() }

        entries.clear()
        if (dir.uri != root.uri) {
            entries.add(Entry(dir.parentFile ?: root, getString(R.string.parent_directory), isParent = true, isDirectory = true))
        }
        sortedDirs.forEach { entries.add(Entry(it, it.name ?: "?", isParent = false, isDirectory = true)) }
        sortedFiles.forEach { entries.add(Entry(it, it.name ?: "?", isParent = false, isDirectory = false)) }

        adapter.notifyDataSetChanged()
        binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyView.text = getString(R.string.empty_directory)
        if (!preserveScroll) {
            binding.recyclerView.scrollToPosition(0)
        }
    }

    private fun displayPath(dir: DocumentFile, root: DocumentFile): String {
        if (dir.uri == root.uri) return "/"
        // Walk up to root collecting names. Tree URIs can be compared by string equality.
        val parts = mutableListOf<String>()
        var node: DocumentFile? = dir
        while (node != null && node.uri != root.uri) {
            parts.add(0, node.name ?: "?")
            node = node.parentFile
        }
        return "/" + parts.joinToString("/")
    }

    private fun isInsideRoot(file: DocumentFile, root: DocumentFile): Boolean {
        var node: DocumentFile? = file
        while (node != null) {
            if (node.uri == root.uri) return true
            node = node.parentFile
        }
        return false
    }

    private fun onEntryClick(entry: Entry) {
        val doc = entry.doc ?: return
        if (entry.isParent || entry.isDirectory) {
            navigateTo(doc)
            return
        }
        openFileUri(doc.uri, entry.name, doc.type, doc.length())
    }

    private fun openFileUri(uri: Uri, displayName: String, mime: String?, length: Long) {
        if (!TextFileSupport.isProbablyTextFile(contentResolver, uri, displayName, mime)) {
            toast(getString(R.string.binary_file_unsupported))
            return
        }
        if (length > TextFileSupport.MAX_FILE_SIZE) {
            toast(getString(R.string.file_too_large, formatSize(length)))
            return
        }
        startActivity(
            Intent(this, TextFileEditActivity::class.java).apply {
                data = uri
                putExtra(TextFileEditActivity.EXTRA_DISPLAY_NAME, displayName)
            }
        )
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
            val itemBinding = ItemFileEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return FileVH(itemBinding)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: FileVH, position: Int) {
            val entry = entries[position]
            holder.bind(entry)
            holder.itemView.setOnClickListener { onEntryClick(entry) }
            holder.itemView.setOnLongClickListener { onEntryLongClick(entry) }
        }
    }

    private fun onEntryLongClick(entry: Entry): Boolean {
        if (entry.isParent) return false
        val doc = entry.doc ?: return false
        val items = arrayOf(
            getString(R.string.rename),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(doc, entry.name, entry.isDirectory)
                    1 -> showDeleteConfirm(doc, entry.name)
                }
            }
            .show()
        return true
    }

    private fun showRenameDialog(doc: DocumentFile, currentName: String, isDirectory: Boolean) {
        val editText = EditText(this).apply {
            setText(currentName)
            // Select the basename so the user can retype it without losing the extension.
            val dot = currentName.lastIndexOf('.')
            val selectionEnd = if (!isDirectory && dot > 0) dot else currentName.length
            setSelection(0, selectionEnd)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isEmpty() || newName == currentName) return@setPositiveButton
                val ok = try {
                    doc.renameTo(newName)
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    toast(getString(R.string.renamed))
                    currentDir?.let { navigateTo(it, preserveScroll = true) }
                } else {
                    toast(getString(R.string.rename_failed, newName))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        editText.requestFocus()
    }

    private fun showDeleteConfirm(doc: DocumentFile, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.confirm_delete, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                val ok = try {
                    doc.delete()
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    toast(getString(R.string.deleted))
                    currentDir?.let { navigateTo(it, preserveScroll = true) }
                } else {
                    toast(getString(R.string.delete_failed, name))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class FileVH(val binding: ItemFileEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: Entry) {
            when {
                entry.isParent -> {
                    binding.icon.text = "↩"
                    binding.name.text = entry.name
                    binding.info.text = ""
                    binding.info.visibility = View.GONE
                }
                entry.isDirectory -> {
                    binding.icon.text = "📁"
                    binding.name.text = entry.name
                    // DocumentFile lacks a cheap "child count" — leave the info blank for folders.
                    binding.info.text = ""
                    binding.info.visibility = View.GONE
                }
                else -> {
                    binding.icon.text = "📄"
                    binding.name.text = entry.name
                    binding.info.text = formatSize(entry.doc?.length() ?: 0L)
                    binding.info.visibility = View.VISIBLE
                }
            }
        }
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
        private const val PREFS_NAME = "text_editor"
        private const val PREF_TREE_URI = "tree_uri"
    }
}
