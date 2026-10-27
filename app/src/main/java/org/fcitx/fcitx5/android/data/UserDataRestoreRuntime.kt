/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.ui.main.settings.behavior.UserDataRestoreActivity
import org.fcitx.fcitx5.android.utils.ExternalRimeStorage
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** The restore process holds this lock before stopping the old main process. */
internal object UserDataRestoreRuntime {
    private val processLock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cleanupJob: Job? = null
    private var cleanupDirectory: File? = null
    private data class ReadyState(val directory: File, val pending: List<File>)
    @Volatile private var readyState: ReadyState? = null
    @Serializable private data class Request(val directory: String, val destinations: Map<String, String?>)
    data class Outcome(val success: Boolean, val message: String)
    private fun unlocked(context: Context) = Build.VERSION.SDK_INT < 24 ||
        (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
    fun isRestoreProcess(context: Context): Boolean = runCatching {
        File("/proc/self/cmdline").inputStream().use { input ->
            val bytes = ByteArray(1024); val n = input.read(bytes)
            String(bytes, 0, n.coerceAtLeast(0)).substringBefore('\u0000') == "${context.packageName}:restore"
        }
    }.getOrDefault(false)
    private fun journal(context: Context) = UserDataRestoreJournal(context.noBackupFilesDir.canonicalFile, anchor = { target ->
        val data = File(context.applicationInfo.dataDir).canonicalFile
        if (RimeProfileRestorePlan.overlap(data, target)) data else {
            val primary = android.os.Environment.getExternalStorageDirectory().canonicalPath
            if (target.path.startsWith("$primary/Android/data/${context.packageName}/")) File(primary)
            else ExternalRimeStorage.restoreVolumeRoot(target.path, primary)?.let(::File)
                ?: throw IOException("Cannot identify restore storage volume")
        }
    })
    private fun <T> locked(context: Context, wait: Boolean = true, action: () -> T): T = synchronized(processLock) {
        val root = context.noBackupFilesDir.canonicalFile
        val lock = File(root, "user-data-restore.lock")
        require(lock.canonicalFile == lock.absoluteFile)
        RandomAccessFile(lock, "rw").use { handle ->
            val held = if (wait) handle.channel.lock() else try { handle.channel.tryLock() }
                catch (_: OverlappingFileLockException) { null }
                ?: throw IOException("User data restore is still running. Continue in the recovery screen.")
            held.use { return action() }
        }
    }

    private fun scheduleCleanup(context: Context) = synchronized(processLock) {
        val directory = context.noBackupFilesDir.canonicalFile
        if (cleanupJob?.isActive == true && cleanupDirectory == directory) return@synchronized
        cleanupDirectory = directory
        cleanupJob = cleanupScope.launch {
            try { locked(context) {
                val result = journal(context).cleanupResolved()
                readyState = ReadyState(directory, result.pending)
            } }
            catch (e: Exception) { timber.log.Timber.w(e, "Restore cleanup retained for a later retry") }
        }
    }
    private fun requestFile(context: Context, token: String): File {
        require(UUID.fromString(token).toString() == token)
        val parent = File(context.noBackupFilesDir.canonicalFile, "restore-requests")
        require(parent.canonicalFile == parent.absoluteFile)
        val file = File(parent, "$token.json")
        require(file.canonicalFile == file.absoluteFile)
        return file
    }
    private fun preparedDirectory(context: Context, path: String): File {
        val file = File(path)
        val resolved = file.canonicalFile
        // Context paths can contain stable platform aliases. Normalize that trusted
        // parent, while rejecting a leaf redirected to another quarantine or outside it.
        require(resolved.parentFile == context.cacheDir.canonicalFile && resolved.name == file.name &&
            file.name.matches(Regex("user-data-import-[0-9a-f-]{36}"))) { "Invalid prepared restore directory" }
        return resolved
    }
    fun prepare(context: Context, backup: UserDataManager.PreparedImport, plan: RimeProfileRestorePlan): String {
        val token = UUID.randomUUID().toString()
        val request = Request(preparedDirectory(context, backup.directory.path).path,
            plan.destinations.mapValues { it.value?.canonicalPath })
        FileUtil.writeAtomically(requestFile(context, token), Json.encodeToString(request))
        return token
    }
    fun intent(context: Context, token: String) = Intent(context, UserDataRestoreActivity::class.java)
        .putExtra("restore_token", token).putExtra("restore_main_pid", Process.myPid())

    fun recoverBeforeInitialization(context: Context) {
        if (!unlocked(context)) return
        // A concurrent isolated restore may take minutes. Fail into its progress UI
        // instead of blocking Application attachment until the system declares an ANR.
        locked(context, wait = false) {
            val result = journal(context).recover(cleanup = false)
            val data = File(context.applicationInfo.dataDir).canonicalFile
            val external = context.getExternalFilesDir(null)?.canonicalFile
            val ownExternalPrefix = android.os.Environment.getExternalStorageDirectory().canonicalPath +
                "/Android/data/${context.packageName}/"
            val required = result.pending.filter { RimeProfileRestorePlan.overlap(it, data) ||
                it.path.startsWith(ownExternalPrefix) ||
                (external != null && RimeProfileRestorePlan.overlap(it, external)) }
            if (required.isNotEmpty()) throw IOException("Restore recovery needs attention: ${required.joinToString { it.path }}\n${result.warnings.joinToString("\n")}")
            readyState = ReadyState(context.noBackupFilesDir.canonicalFile, result.pending)
            if (result.cleanupPending) scheduleCleanup(context)
        }
    }
    fun requirePathReady(context: Context, path: File) {
        if (!unlocked(context)) return
        val directory = context.noBackupFilesDir.canonicalFile
        val target = path.canonicalFile
        // Any new restore must kill this main process before changing live roots.
        // Its startup decision therefore remains valid while hidden cleanup runs.
        readyState?.takeIf { it.directory == directory }?.let { state ->
            if (state.pending.none { RimeProfileRestorePlan.overlap(it, target) }) return
        }
        locked(context) {
            val result = journal(context).recover(cleanup = false)
            readyState = ReadyState(directory, result.pending)
            if (result.pending.any { RimeProfileRestorePlan.overlap(it, target) })
                throw IOException("Storage recovery is pending for ${path.path}. Reconnect the volume and retry.")
            if (result.cleanupPending) scheduleCleanup(context)
        }
    }
    fun launchRecovery(context: Context, cause: Exception) {
        context.startActivity(Intent(context, UserDataRestoreActivity::class.java)
            .putExtra("restore_error", cause.toString().take(16000))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun stopMain(context: Context, originalPid: Int) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = manager.runningAppProcesses.orEmpty().filter {
            it.uid == Process.myUid() && it.processName == context.packageName && it.pid != Process.myPid()
        }.map { it.pid }.toMutableSet()
        if (originalPid != Process.myPid() && originalPid > 0) {
            val name = runCatching { File("/proc/$originalPid/cmdline").readText().substringBefore('\u0000') }.getOrNull()
            if (name == context.packageName) processes += originalPid
        }
        processes.forEach(Process::killProcess)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (processes.any { pid ->
                runCatching { File("/proc/$pid/stat").readText().substringAfterLast(')').trim().first() != 'Z' }.getOrDefault(false)
            }) {
            if (System.nanoTime() > deadline) throw IOException("The old input process has not stopped")
            Thread.sleep(10)
        }
    }

    fun execute(context: Context, token: String, originalPid: Int): Outcome = locked(context) {
        val requestPath = requestFile(context, token)
        var prepared: UserDataManager.PreparedImport? = null
        val state = journal(context)
        try {
            // A new main process must pass the same startup lock before opening prefs/Room.
            stopMain(context, originalPid)
            require(requestPath.isFile && requestPath.length() <= 1024 * 1024) { "Prepared restore request is unavailable" }
            val request = Json.decodeFromString<Request>(requestPath.readText())
            val directory = preparedDirectory(context, request.directory)
            val backup = UserDataManager.openPrepared(directory).also { prepared = it }
            val plan = RimeProfileRestorePlan(request.destinations.mapValues { it.value?.let(::File) })
            UserDataManager.restore(backup, plan, journal = state, closeClipboard = false).getOrThrow()
            val recovery = state.recover()
            Outcome(true, context.getString(org.fcitx.fcitx5.android.R.string.user_data_imported,
                org.fcitx.fcitx5.android.utils.formatDateTime(backup.metadata.exportTime)) +
                recovery.warnings.joinToString("\n", prefix = if (recovery.warnings.isEmpty()) "" else "\n"))
        } catch (e: Exception) {
            val recovery = runCatching { state.recover() }.getOrNull()
            Outcome(recovery?.committed == true, e.toString() + "\n" + recovery?.warnings.orEmpty().joinToString("\n"))
        } finally {
            prepared?.close()
            requestPath.delete()
        }
    }
    fun retryRecovery(context: Context): Outcome = locked(context) {
        val result = journal(context).recover()
        Outcome(result.pending.isEmpty(), (result.pending.map { it.path } + result.warnings).joinToString("\n"))
    }
}
