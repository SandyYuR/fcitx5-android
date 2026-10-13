/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.os.Build
import android.annotation.SuppressLint
import android.system.Os
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class BackupFileChangedException(
    val category: String,
    val beforeLength: Long,
    val afterLength: Long,
    val beforeModified: Long,
    val afterModified: Long
) : IOException("A data file changed during backup. Please try again.")

/** Archive actual profile files; source paths are hints, never active restore destinations. */
object RimeProfileBackup {
    const val MANIFEST_NAME = "rime-profiles.json"

    @Serializable
    data class Manifest(
        val version: Int = 1,
        val externalProfiles: List<String>,
        val originalPaths: Map<String, String> = emptyMap()
    )

    fun write(externalRoot: File, state: RimeDataDirectoryState, zip: ZipOutputStream) {
        require(state.profiles.all(RimeDataDirectoryState::isValidName)) { "Invalid Rime profile names." }
        val root = externalRoot.canonicalFile
        val external = state.profiles.mapNotNull { name ->
            val source = File(state.paths.getValue(name)).canonicalFile
            val internal = File(root, "data/$name").canonicalFile
            if (source == internal) null else {
                // A profile containing the app's files would recursively include its own backup.
                if (root.path == source.path || root.path.startsWith(source.path + File.separator)) {
                    throw IOException("The Rime directory contains the application data directory.")
                }
                if (!source.isDirectory || !source.canRead()) {
                    throw IOException("An external Rime directory is unavailable. Backup was not completed.")
                }
                name to source
            }
        }.toMap()
        BackupFileTree.write(root, "external", zip) { file ->
            file == File(root, RimeDataDirectoryState.CONFIG_PATH) ||
                external.keys.any { file == File(root, "data/$it") }
        }
        external.forEach { (name, source) -> BackupFileTree.write(source, "external/data/$name", zip) }
        zip.putNextEntry(ZipEntry("external/${RimeDataDirectoryState.CONFIG_PATH}"))
        zip.write(state.portableConfig().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(MANIFEST_NAME))
        zip.write(Json.encodeToString(Manifest(version = 2, externalProfiles = external.keys.toList(),
            originalPaths = external.mapValues { it.value.absolutePath })).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Validate before any live app files are replaced. Old backups remain importable. */
    fun readManifest(extractedRoot: File): Manifest {
        val manifestFile = File(extractedRoot, MANIFEST_NAME)
        val config = File(extractedRoot, "external/${RimeDataDirectoryState.CONFIG_PATH}")
        require(manifestFile.length() <= 1024 * 1024 && config.length() <= 1024 * 1024) {
            "Rime backup metadata is too large."
        }
        // An archive made by the original PR may contain only pointers to another phone.
        // Never activate those paths as a side effect of importing a backup.
        if (config.isFile && config.readLines().any { line ->
                val key = line.substringBefore('=').trim()
                key in setOf("ExternalPaths", "ExternalPathsV2") &&
                    line.substringAfter('=', "").trim().let { it.isNotEmpty() && it != "\"\"" }
            }) {
            throw IOException("This backup contains external Rime paths without portable data. Back up those directories separately.")
        }
        if (!manifestFile.exists()) return Manifest(externalProfiles = emptyList())
        val manifest = Json.decodeFromString<Manifest>(manifestFile.readText())
        require(manifest.version in 1..2 && manifest.externalProfiles.distinct() == manifest.externalProfiles &&
            manifest.externalProfiles.all(RimeDataDirectoryState::isValidName)) {
            "Invalid Rime profile backup manifest."
        }
        require(manifest.originalPaths.keys.all { it in manifest.externalProfiles } &&
            manifest.originalPaths.values.all { RimeDataDirectoryState.isAbsolutePath(it) && '\u0000' !in it } &&
            (manifest.version == 1 || manifest.originalPaths.keys == manifest.externalProfiles.toSet())) {
            "Invalid original Rime directory paths."
        }
        manifest.externalProfiles.forEach { name ->
            require(File(extractedRoot, "external/data/$name").isDirectory) {
                "The backup is missing an external Rime directory."
            }
        }
        return manifest
    }

    fun restoredExternalProfiles(extractedRoot: File): List<String> = readManifest(extractedRoot).externalProfiles
}

/** File traversal must not silently omit unreadable directories or follow links outside a profile. */
internal object BackupFileTree {
    private data class Metadata(val length: Long, val modified: Long, val identity: Pair<Long, Long>?)

    private fun metadata(file: File, input: FileInputStream): Metadata =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val stat = Os.fstat(input.fd)
            Metadata(stat.st_size, stat.st_mtim.tv_sec * 1_000_000_000L + stat.st_mtim.tv_nsec,
                stat.st_dev to stat.st_ino)
        } else Metadata(input.channel.size(), file.lastModified() * 1_000_000L, null)

    // Plain JVM tests use android.jar stubs, which report SDK 0 and cannot stat files.
    @SuppressLint("ObsoleteSdkInt")
    private fun pathIdentity(file: File): Pair<Long, Long>? = if (Build.VERSION.SDK_INT >= 21) {
        val stat = Os.stat(file.absolutePath)
        stat.st_dev to stat.st_ino
    } else null

    fun visit(root: File, skip: (File) -> Boolean = { false }, action: (File) -> Unit) {
        val canonicalRoot = root.canonicalFile
        if (!canonicalRoot.isDirectory) throw IOException("Backup source directory is unavailable.")
        fun walk(file: File) {
            if (skip(file)) return
            if (file.canonicalFile != file.absoluteFile) throw IOException("Symbolic links are not supported in user data backups.")
            action(file)
            if (file.isDirectory) {
                val children = file.listFiles() ?: throw IOException("Unable to read a backup source directory.")
                children.forEach(::walk)
            } else if (!file.isFile) {
                throw IOException("Unsupported file in user data backup.")
            }
        }
        walk(canonicalRoot)
    }

    fun write(root: File, prefix: String, zip: ZipOutputStream, skip: (File) -> Boolean = { false }) {
        val canonicalRoot = root.canonicalFile
        visit(canonicalRoot, skip) { file ->
            val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
            val path = if (relative.isEmpty()) prefix else "$prefix/$relative"
            zip.putNextEntry(ZipEntry(if (file.isDirectory) "$path/" else path))
            if (file.isFile) {
                file.inputStream().use { input ->
                    // Path metadata and the opened shared-storage file can disagree.
                    // Compare the same descriptor before/after copying; detect replacements separately.
                    val before = metadata(file, input)
                    val identity = pathIdentity(file)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val copied = DigestInputStream(input, digest).copyTo(zip)
                    val copiedDigest = digest.digest()
                    val after = metadata(file, input)
                    var changed = copied != before.length || after.length != before.length ||
                        after.identity != before.identity || pathIdentity(file) != identity || !file.isFile
                    if (!changed && after.modified != before.modified) {
                        // Shared-storage timestamps can refresh while reading unchanged data.
                        // Accept that only after rereading this descriptor and verifying all bytes.
                        input.channel.position(0)
                        val verification = MessageDigest.getInstance("SHA-256")
                        val verifier = DigestInputStream(input, verification)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (verifier.read(buffer) != -1) { }
                        val verified = metadata(file, input)
                        changed = !copiedDigest.contentEquals(verification.digest()) ||
                            verified.length != before.length || verified.identity != before.identity ||
                            pathIdentity(file) != identity || !file.isFile
                    }
                    if (changed) {
                        val category = when {
                            file.name in setOf("LOG", "LOG.old", "LOCK", "CURRENT") -> "database_${file.name}"
                            file.extension.matches(Regex("[a-zA-Z0-9]{1,12}")) -> "extension_${file.extension.lowercase()}"
                            else -> "other"
                        }
                        throw BackupFileChangedException(category, before.length, after.length, before.modified, after.modified)
                    }
                }
            }
            zip.closeEntry()
        }
    }

    fun copy(root: File, destination: File) {
        if (!root.exists()) return
        val canonicalRoot = root.canonicalFile
        visit(canonicalRoot) { file ->
            val target = File(destination, file.relativeTo(canonicalRoot).path)
            if (file.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) throw IOException("Unable to stage imported data.")
            } else {
                file.copyTo(target, overwrite = true)
            }
        }
    }
}
