/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File
import kotlin.coroutines.resume

/** The in-app folder picker used when receiving shared files. */
internal class ShareReceiveDirectoryPicker(private val activity: Activity) {
    private val prefs by lazy { AppPrefs.getInstance() }

    fun lastDirectoryOr(defaultValue: String): String =
        if (prefs.internal.lastShareReceiveDirectoryRemembered.getValue()) {
            normalizeRelativePath(prefs.internal.lastShareReceiveDirectory.getValue()).ifBlank { defaultValue }
        } else {
            defaultValue
        }

    fun rememberDirectory(relativePath: String) {
        prefs.internal.lastShareReceiveDirectory.setValue(normalizeRelativePath(relativePath))
        prefs.internal.lastShareReceiveDirectoryRemembered.setValue(true)
    }

    fun resolveDirectory(relativePath: String): File {
        val root = importRootDir()
        val normalized = normalizeRelativePath(relativePath)
        val directory = if (normalized.isBlank()) root else File(root, normalized).canonicalFile
        require(isInsideRoot(root, directory) && (!directory.exists() || directory.isDirectory)) {
            activity.getString(R.string.share_receive_invalid_path)
        }
        return directory
    }

    suspend fun chooseTargetDirectory(
        initialRelativeDir: String,
        fileName: String? = null,
        fileCount: Int? = null
    ): String? = withContext(Dispatchers.Main) {
        if (activity.isFinishing || activity.isDestroyed) return@withContext null
        val root = importRootDir()
        val initialDirectory = runCatching { resolveDirectory(initialRelativeDir) }
            .getOrNull()?.takeIf { it.isDirectory } ?: root
        suspendCancellableDialog<String?> { continuation ->
            var currentDirectory = initialDirectory
            val density = activity.resources.displayMetrics.density
            val pathView = TextView(activity).apply {
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            val entries = mutableListOf<DirectoryEntry>()
            val adapter = ArrayAdapter<String>(
                activity,
                android.R.layout.simple_list_item_1,
                mutableListOf()
            )
            val listView = ListView(activity).apply {
                dividerHeight = 0
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (320 * density).toInt()
                )
                this.adapter = adapter
            }
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
                addView(pathView)
                addView(listView)
            }

            fun updatePath() {
                val relative = relativePathFromRoot(root, currentDirectory)
                pathView.text = when {
                    fileName != null -> activity.getString(
                        R.string.share_receive_file_path_message_single,
                        fileName,
                        displayRelativeDirectory(relative)
                    )
                    fileCount != null -> activity.getString(
                        R.string.share_receive_file_path_message_multiple,
                        fileCount,
                        displayRelativeDirectory(relative)
                    )
                    else -> activity.getString(
                        R.string.share_receive_file_path_message,
                        displayRelativeDirectory(relative)
                    )
                }
            }

            fun updateEntries() {
                entries.clear()
                entries.addAll(buildDirectoryEntries(root, currentDirectory))
                adapter.clear()
                adapter.addAll(entries.map { it.label })
                adapter.notifyDataSetChanged()
            }

            listView.setOnItemClickListener { _, _, position, _ ->
                currentDirectory = entries.getOrNull(position)?.directory ?: return@setOnItemClickListener
                updatePath()
                updateEntries()
            }

            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_receive_file_path_title)
                .setView(container)
                .setPositiveButton(R.string.import_, null)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(null)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .create()

            dialog.setOnShowListener {
                updatePath()
                updateEntries()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (continuation.isActive) continuation.resume(relativePathFromRoot(root, currentDirectory))
                    dialog.dismiss()
                }
            }
            dialog.show()
            dialog
        }
    }

    private fun importRootDir(): File =
        activity.getExternalFilesDir(null)?.canonicalFile ?: error("Cannot resolve app files dir")

    private fun normalizeRelativePath(path: String): String =
        path.replace('\\', '/').trim().trim('/').replace(Regex("/+"), "/")

    private fun relativePathFromRoot(root: File, target: File): String {
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(isInsideRoot(canonicalRoot, canonicalTarget)) {
            activity.getString(R.string.share_receive_invalid_path)
        }
        return if (canonicalTarget == canonicalRoot) "" else
            canonicalTarget.relativeTo(canonicalRoot).invariantSeparatorsPath
    }

    private fun buildDirectoryEntries(root: File, currentDirectory: File): List<DirectoryEntry> {
        val canonicalRoot = root.canonicalFile
        val entries = mutableListOf<DirectoryEntry>()
        if (currentDirectory != canonicalRoot) {
            val parent = currentDirectory.parentFile?.canonicalFile
                ?.takeIf { isInsideRoot(canonicalRoot, it) }
                ?: canonicalRoot
            entries += DirectoryEntry("../", parent)
        }
        currentDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { child ->
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                if (isInsideRoot(canonicalRoot, canonicalChild)) {
                    entries += DirectoryEntry("${child.name}/", canonicalChild)
                }
            }
        return entries
    }

    private fun isInsideRoot(root: File, target: File): Boolean =
        target == root || target.path.startsWith(root.path + File.separator)

    private fun displayRelativeDirectory(relativeDir: String): String =
        if (relativeDir.isBlank()) "/" else "/$relativeDir"

    private data class DirectoryEntry(val label: String, val directory: File)
}
