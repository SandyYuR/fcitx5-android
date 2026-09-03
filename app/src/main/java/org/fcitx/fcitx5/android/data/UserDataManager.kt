/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.CLIPBOARD_DATABASE_NAME
import org.fcitx.fcitx5.android.data.clipboard.db.CLIPBOARD_DATABASE_VERSION
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    // Allow importing user data from any build variant of Fcitx5 Android
    private const val allowedPackageNamePrefix = "org.fcitx.fcitx5.android"

    // Upstream release package name. Always written into exported metadata so that
    // upstream's strict `metadata.packageName == BuildConfig.APPLICATION_ID` check
    // accepts our exports out of the box. The SharedPreferences XML in the zip is
    // renamed to match this name for the same reason.
    private const val canonicalExportPackageName = "org.fcitx.fcitx5.android"

    private fun isAllowedPackageName(packageName: String): Boolean {
        return packageName == allowedPackageNamePrefix || packageName.startsWith("$allowedPackageNamePrefix.")
    }

    private fun writeFileTree(
        srcDir: File,
        destPrefix: String,
        dest: ZipOutputStream,
        skip: (File) -> Boolean = { false }
    ) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile && !skip(f)) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    /**
     * Write shared_prefs into the zip, renaming this build's package-specific
     * preferences XML (`<currentPkg>_preferences.xml`) to the canonical upstream name
     * (`org.fcitx.fcitx5.android_preferences.xml`) so the resulting zip is portable
     * across upstream and fx builds. Other variants' preferences XML files
     * are skipped (mirrors the importer's behavior).
     */
    private fun writeSharedPrefsTree(srcDir: File, dest: ZipOutputStream) {
        val destPrefix = "shared_prefs"
        val currentPrefsFileName = "${appContext.packageName}_preferences.xml"
        val canonicalPrefsFileName = "${canonicalExportPackageName}_preferences.xml"
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir).path
            if (related.isEmpty()) return@forEach
            if (f.isDirectory) {
                dest.putNextEntry(ZipEntry("$destPrefix/$related/"))
                return@forEach
            }
            if (!f.isFile) return@forEach
            val mappedRelated = when {
                f.name == currentPrefsFileName ->
                    related.substring(0, related.length - f.name.length) + canonicalPrefsFileName
                // Skip other variants' preferences files to avoid confusion on import.
                f.name.endsWith("_preferences.xml") -> return@forEach
                else -> related
            }
            dest.putNextEntry(ZipEntry("$destPrefix/$mappedRelated"))
            f.inputStream().use { it.copyTo(dest) }
        }
    }

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs (current variant's prefs XML renamed to canonical upstream name)
            writeSharedPrefsTree(sharedPrefsDir, zipStream)
            // databases: checkpoint first so the main db file is self-consistent, then
            // export it without the WAL/SHM side-cars (they are meaningless on another
            // device and can contradict the db they are restored next to).
            ClipboardManager.checkpointDatabase()
            writeFileTree(dataBasesDir, "databases", zipStream) { isSqliteSideCar(it.name) }
            // external
            writeFileTree(externalDir, "external", zipStream)
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata — write canonical upstream package name so upstream's import accepts it
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                canonicalExportPackageName,
                pkgInfo.versionCodeCompat,
                Const.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    /**
     * Rewrite absolute custom button icon paths in ButtonsLayout.json to the
     * portable relative form `file:button_icons/<name>`. No-op when the file is
     * absent or contains no such paths.
     */
    private val absoluteButtonIconRegex =
        Regex(""""file:[^"]*?button_icons/([^"/]+)"""")

    private fun normalizeButtonIconPaths(file: File) {
        if (!file.isFile) return
        runCatching {
            val original = file.readText()
            val rewritten = absoluteButtonIconRegex.replace(original) {
                """"file:button_icons/${it.groupValues[1]}""""
            }
            if (rewritten != original) file.writeText(rewritten)
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.copyRecursively(target, overwrite = true)
        }
    }

    /**
     * Refuse an incoming clipboard database whose schema is newer than this build's.
     *
     * The `user_version` header field holds the Room schema version, at a fixed offset in
     * the SQLite file header, so this can be checked without opening the database.
     */
    private fun rejectNewerClipboardDatabase(databasesDir: File) {
        val incoming = File(databasesDir, CLIPBOARD_DATABASE_NAME)
        if (!incoming.isFile) return
        val version = readSqliteUserVersion(incoming) ?: return
        if (version > CLIPBOARD_DATABASE_VERSION) {
            throw RuntimeException(
                appContext.getString(
                    R.string.exception_user_data_db_version_too_new,
                    version,
                    CLIPBOARD_DATABASE_VERSION
                )
            )
        }
    }

    /**
     * Read SQLite's `user_version` (big-endian int at byte offset 60 of the file header).
     * Returns null when the file is too short or does not start with the SQLite magic.
     */
    private fun readSqliteUserVersion(file: File): Int? = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(64)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < header.size) return@runCatching null
            val magic = String(header, 0, 15, Charsets.US_ASCII)
            if (magic != "SQLite format 3") return@runCatching null
            ((header[60].toInt() and 0xff) shl 24) or
                ((header[61].toInt() and 0xff) shl 16) or
                ((header[62].toInt() and 0xff) shl 8) or
                (header[63].toInt() and 0xff)
        }
    }.getOrNull()

    /** SQLite side-car files; see [copyDatabases]. */
    private fun isSqliteSideCar(name: String): Boolean =
        name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal")

    /**
     * Copy the databases directory, skipping WAL/SHM side-cars.
     *
     * A `-wal` from the exporting device does not match the `clbdb` we are writing here, and
     * restoring both can leave SQLite reading a stale or mismatched log — the imported
     * clipboard history then comes up empty or corrupt. The main database file is
     * self-consistent because the exporter checkpoints before writing it.
     */
    private fun copyDatabases(source: File, target: File) {
        if (!source.exists() || !source.isDirectory) return
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                copyDir(file, File(target, file.name))
                return@forEach
            }
            if (!file.isFile) return@forEach
            if (isSqliteSideCar(file.name)) return@forEach
            file.copyTo(File(target, file.name), overwrite = true)
        }
        // Any leftover side-car from the previous database must go too: it describes a
        // database file that no longer exists.
        target.listFiles()?.forEach { file ->
            if (file.isFile && isSqliteSideCar(file.name)) file.delete()
        }
    }

    /**
     * Copy shared_prefs directory, renaming preference files to match current package name.
     * When importing between different build variants, we select the preferences file that
     * matches the exported package name and rename it to the current package name.
     */
    private fun copySharedPrefs(source: File, target: File, exportedPackageName: String) {
        if (!source.exists() || !source.isDirectory) {
            return
        }

        target.mkdirs()

        val currentPkgName = appContext.packageName
        val exportedPrefsFileName = "${exportedPackageName}_preferences.xml"
        val exportedPrefsFile = source.listFiles()?.find { it.name == exportedPrefsFileName }

        // Copy files, renaming the exported package-specific file to current package name
        source.listFiles()?.forEach { sourceFile ->
            when {
                sourceFile == exportedPrefsFile -> {
                    sourceFile.copyTo(File(target, "${currentPkgName}_preferences.xml"), overwrite = true)
                }
                sourceFile.name.endsWith("_preferences.xml") -> {
                    // Skip other package-specific files to avoid conflicts
                }
                else -> {
                    sourceFile.copyTo(File(target, sourceFile.name), overwrite = true)
                }
            }
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val extracted = zipStream.extract(tempDir)
                val metadataFile = extracted.find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                if (!isAllowedPackageName(metadata.packageName)) {
                    errorRuntime(R.string.exception_user_data_package_name_mismatch, metadata.packageName)
                }
                // Refuse a clipboard database from a newer build before touching anything:
                // Room can only open a downgraded schema by wiping it, which used to clear
                // pinned and favorite entries with no warning at all.
                rejectNewerClipboardDatabase(File(tempDir, "databases"))
                // Release the current database's file handles before its files are replaced.
                ClipboardManager.closeDatabase()
                // Copy shared_prefs with package name renaming
                copySharedPrefs(File(tempDir, "shared_prefs"), sharedPrefsDir, metadata.packageName)
                copyDatabases(File(tempDir, "databases"), dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                // Rewrite absolute custom button icon paths (which embed the source
                // build's applicationId) to the portable relative form, so imported
                // configs keep working across build variants.
                normalizeButtonIconPaths(File(externalDir, "config/ButtonsLayout.json"))
                // keep importing recently_used for backwords compatibility
                copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
                metadata
            }
        }
    }
}