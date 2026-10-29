/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.os.Environment
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.input.voice.js.JsVoicePluginStore
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.CLIPBOARD_DATABASE_NAME
import org.fcitx.fcitx5.android.data.clipboard.db.CLIPBOARD_DATABASE_VERSION
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.ExternalRimeStorage
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.UUID

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

    private val sharedPrefsDir get() = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir get() = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir get() = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir get() = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, rimeDirectories: RimeDataDirectoryState,
               timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs (current variant's prefs XML renamed to canonical upstream name)
            JsVoicePluginStore.withSnapshot { scripts ->
                writeSharedPrefsTree(sharedPrefsDir, zipStream)
                writeFileTree(scripts, "js-voice", zipStream) { !it.name.endsWith(".js") }
            }
            writeDatabases(zipStream)
            // external
            RimeProfileBackup.write(externalDir, rimeDirectories, zipStream)
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

    private fun writeDatabases(zipStream: ZipOutputStream) {
        val snapshot = File(appContext.cacheDir, "clipboard-snapshot-${UUID.randomUUID()}.db")
        try {
            val hasSnapshot = ClipboardManager.writeDatabaseSnapshot(snapshot)
            if (!hasSnapshot && File(dataBasesDir, CLIPBOARD_DATABASE_NAME).exists()) {
                throw IOException("Clipboard database is unavailable for a consistent backup.")
            }
            writeFileTree(dataBasesDir, "databases", zipStream) {
                isSqliteSideCar(it.name) || it.name == CLIPBOARD_DATABASE_NAME
            }
            if (hasSnapshot) {
                zipStream.putNextEntry(ZipEntry("databases/$CLIPBOARD_DATABASE_NAME"))
                snapshot.inputStream().use { it.copyTo(zipStream) }
                zipStream.closeEntry()
            }
        } finally {
            SQLiteDatabase.deleteDatabase(snapshot)
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
        BackupFileTree.copy(source, target)
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
     * prepareImport has already recovered the incoming clipboard database with its own
     * WAL/journal and replaced it with a validated standalone snapshot. Side-cars from
     * the previous installed database must not survive the directory replacement.
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
        // Remove old sidecars only for replaced databases. Unreplaced databases may
        // still have valid WAL entries after the old process was stopped.
        source.listFiles()?.filter { it.isFile && !isSqliteSideCar(it.name) }?.forEach { incoming ->
            listOf("-wal", "-shm", "-journal").forEach { suffix -> removeStaleFile(File(target, incoming.name + suffix)) }
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
                    val destination = File(target, "${currentPkgName}_preferences.xml")
                    sourceFile.copyTo(destination, overwrite = true)
                    removeStaleFile(File(destination.path + ".bak"))
                }
                sourceFile.name.endsWith("_preferences.xml") || sourceFile.name.endsWith(".xml.bak") -> {
                    // Skip other package-specific files to avoid conflicts
                }
                else -> {
                    val destination = File(target, sourceFile.name)
                    sourceFile.copyTo(destination, overwrite = true)
                    removeStaleFile(File(destination.path + ".bak"))
                }
            }
        }
    }

    private fun removeStaleFile(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot remove stale restore file: ${file.name}")
    }

    class PreparedImport internal constructor(
        internal val directory: File,
        val metadata: Metadata,
        val rimeProfiles: RimeProfileBackup.Manifest
    ) : AutoCloseable {
        override fun close() { directory.deleteRecursively() }
    }

    internal fun openPrepared(directory: File): PreparedImport {
        val metadataFile = File(directory, "metadata.json")
        require(metadataFile.isFile && metadataFile.length() <= 1024 * 1024)
        val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
        require(isAllowedPackageName(metadata.packageName))
        return PreparedImport(directory, metadata, RimeProfileBackup.readManifest(directory))
    }

    /** Extract and validate once, before asking for destinations or stopping the engine. */
    fun prepareImport(src: InputStream): PreparedImport {
        val tempDir = File(appContext.cacheDir, "user-data-import-${UUID.randomUUID()}")
        if (!tempDir.mkdir()) throw IOException("Unable to prepare user data import.")
        try {
            ZipInputStream(src).use { zipStream ->
                zipStream.extract(tempDir)
                val metadataFile = File(tempDir, "metadata.json").takeIf { it.isFile }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                require(metadataFile.length() <= 1024 * 1024) { "Backup metadata is too large." }
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                if (!isAllowedPackageName(metadata.packageName)) {
                    errorRuntime(R.string.exception_user_data_package_name_mismatch, metadata.packageName)
                }
                // Refuse a clipboard database from a newer build before touching anything:
                // Room can only open a downgraded schema by wiping it, which used to clear
                // pinned and favorite entries with no warning at all.
                val databases = File(tempDir, "databases")
                rejectNewerClipboardDatabase(databases)
                normalizeImportedClipboardDatabase(databases)
                SharedPreferencesRestore.normalize(File(tempDir, "shared_prefs"))
                JsVoicePluginStore.validateBackup(tempDir)
                // user_version may itself have been committed only to WAL.
                rejectNewerClipboardDatabase(databases)
                return PreparedImport(tempDir, metadata, RimeProfileBackup.readManifest(tempDir))
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            throw e
        }
    }

    private fun normalizeImportedClipboardDatabase(directory: File) {
        val incoming = File(directory, CLIPBOARD_DATABASE_NAME)
        if (!incoming.isFile) {
            if (File(incoming.path + "-wal").length() > 0L) {
                throw IOException("Clipboard backup contains WAL without its database.")
            }
            return
        }
        val snapshot = File(directory, "clipboard-recovered-${UUID.randomUUID()}.db")
        try {
            SqliteSnapshots.recoverStaged(incoming, snapshot)
            if (!snapshot.renameTo(incoming)) throw IOException("Cannot publish recovered clipboard database.")
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = File(incoming.path + suffix)
                if (sidecar.exists() && !sidecar.delete()) throw IOException("Cannot remove recovered SQLite sidecar.")
            }
        } finally {
            SQLiteDatabase.deleteDatabase(snapshot)
        }
    }

    /** Recheck after confirmation too: mounts, permissions and directory contents can change. */
    fun validateRestore(prepared: PreparedImport, plan: RimeProfileRestorePlan) {
        require(prepared.directory.isDirectory) { "The prepared backup is no longer available." }
        val primary = Environment.getExternalStorageDirectory().canonicalPath
        plan.destinations.values.filterNotNull().forEach { target ->
            val volume = ExternalRimeStorage.restoreVolumeRoot(target.canonicalPath, primary)
                ?: throw IOException("Choose a Rime subdirectory on local shared storage.")
            if (!File(volume).isDirectory) throw IOException("The destination storage volume is unavailable.")
        }
        plan.validate(prepared.rimeProfiles, listOf(File(appContext.applicationInfo.dataDir), externalDir))
    }

    /** The caller stops Fcitx only after the user has confirmed the complete restore plan. */
    internal fun restore(prepared: PreparedImport, plan: RimeProfileRestorePlan,
                         journal: UserDataRestoreJournal? = null, closeClipboard: Boolean = true) = runCatching {
        validateRestore(prepared, plan)
        val tempDir = prepared.directory
        val metadata = prepared.metadata
        JsVoicePluginStore.validateBackup(tempDir)
        if (closeClipboard) ClipboardManager.closeDatabase()
        UserDataRestoreTransaction(journal).use { restore ->
            restore.stage(sharedPrefsDir) { staged ->
                copySharedPrefs(File(tempDir, "shared_prefs"), staged, metadata.packageName)
            }
            restore.stage(File(appContext.filesDir, "js-voice")) { staged ->
                copyDir(File(tempDir, "js-voice"), staged)
            }
            restore.stage(dataBasesDir) { staged ->
                copyDatabases(File(tempDir, "databases"), staged)
            }
            restore.stage(externalDir) { staged ->
                plan.copyApplicationData(tempDir, staged)
                normalizeButtonIconPaths(File(staged, "config/ButtonsLayout.json"))
            }
            if (File(tempDir, "recently_used").exists()) {
                restore.stage(recentlyUsedDir) { staged ->
                    copyDir(File(tempDir, "recently_used"), staged)
                }
            }
            plan.stageExternalProfiles(tempDir, restore)
            restore.commit()
        }
        metadata
    }
}
