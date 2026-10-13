/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import java.io.File
import java.io.IOException

/** A null target explicitly means the application's data/<profile> directory. */
data class RimeProfileRestorePlan(val destinations: Map<String, File?>) {
    fun validate(manifest: RimeProfileBackup.Manifest, protectedRoots: List<File>) {
        require(destinations.keys == manifest.externalProfiles.toSet()) { "Choose a destination for every external Rime directory." }
        val external = destinations.values.filterNotNull().map { it.canonicalFile }
        val protected = protectedRoots.map { it.canonicalFile }
        external.forEachIndexed { index, target ->
            require(protected.none { overlap(it, target) } && external.take(index).none { overlap(it, target) }) {
                "Restore destinations overlap each other or application data. Choose separate directories."
            }
            require(target.parentFile != null) { "Cannot replace a storage root." }
            if (target.exists() && !target.isDirectory) throw IOException("The restore destination is not a directory.")
            if (target.exists()) BackupFileTree.visit(target) { }
            var parent = target.parentFile
            while (parent != null && !parent.exists()) parent = parent.parentFile
            if (parent == null || !parent.isDirectory || !parent.canRead() || !parent.canWrite() || !parent.canExecute() ||
                (target.exists() && (!target.canRead() || !target.canWrite() || !target.canExecute()))) {
                throw IOException("The restore destination or its parent is not accessible for replacement.")
            }
        }
    }

    /** Write only user-selected external registrations, retaining the portable profile list. */
    fun configure(stagedAppRoot: File) {
        val config = File(stagedAppRoot, RimeDataDirectoryState.CONFIG_PATH)
        val lines = if (config.isFile) config.readLines().filter {
            it.substringBefore('=').trim() !in setOf("ExternalPaths", "ExternalPathsV2")
        } else emptyList()
        config.parentFile!!.mkdirs()
        config.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n") +
            RimeDataDirectoryState.externalPathConfig(destinations.mapNotNull { (name, target) ->
                target?.let { name to it.canonicalPath }
            }.toMap()))
    }

    internal fun copyApplicationData(extractedRoot: File, stagedAppRoot: File) {
        destinations.keys.forEach { name ->
            val old = File(stagedAppRoot, "data/$name")
            if (old.exists() && !old.deleteRecursively()) throw IOException("Unable to prepare restored Rime directory.")
        }
        BackupFileTree.copy(File(extractedRoot, "external"), stagedAppRoot)
        destinations.forEach { (name, target) ->
            if (target != null) {
                val payload = File(stagedAppRoot, "data/$name")
                if (payload.exists() && !payload.deleteRecursively()) throw IOException("Unable to stage external Rime data.")
            }
        }
        if (destinations.isNotEmpty()) configure(stagedAppRoot)
    }

    internal fun stageExternalProfiles(extractedRoot: File, transaction: UserDataRestoreTransaction) {
        destinations.forEach { (name, target) ->
            if (target != null) transaction.stage(target, copyExisting = false) { staged ->
                BackupFileTree.copy(File(extractedRoot, "external/data/$name"), staged)
            }
        }
    }

    companion object {
        internal fun overlap(one: File, two: File): Boolean =
            one == two || one.path.startsWith(two.path + File.separator) || two.path.startsWith(one.path + File.separator)
    }
}
