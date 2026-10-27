package org.fcitx.fcitx5.android.data

import android.content.res.Configuration
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.DirectRimeConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.behavior.UserDataRestoreActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadow.api.Shadow
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class UserDataRestoreStartupTest {
    private val context get() = RuntimeEnvironment.getApplication() as FcitxApplication
    private class ProcessStopped : Error()

    @After fun awaitBackgroundCleanup() = kotlinx.coroutines.runBlocking {
        // A real process owns its files for the lifetime of its cleanup worker.
        // Test teardown must not remove the fixture while that worker traverses it.
        ReflectionHelpers.getStaticField<kotlinx.coroutines.Job?>(UserDataRestoreRuntime::class.java, "cleanupJob")?.join()
        Unit
    }

    @Test fun startupRollsBackBeforeAPreferenceFileIsOpened() {
        val target = File(context.applicationInfo.dataDir, "restore-startup-${UUID.randomUUID()}")
        target.mkdir(); File(target, "prefs.xml").writeText("original preferences")
        val journal = UserDataRestoreJournal(context.noBackupFilesDir.canonicalFile)
        var count = 0
        val transaction = UserDataRestoreTransaction(journal) { from, to ->
            check(from.renameTo(to)); if (++count == 2) throw ProcessStopped(); true
        }
        transaction.stage(target, false) { File(it, "prefs.xml").writeText("interrupted import") }
        try { transaction.commit() } catch (_: ProcessStopped) { }
        UserDataRestoreRuntime.recoverBeforeInitialization(context)
        assertEquals("original preferences", File(target, "prefs.xml").readText())
        assertTrue(UserDataRestoreRuntime.retryRecovery(context).success)
        assertFalse(File(context.noBackupFilesDir, "user-data-restore.json").exists())
        target.deleteRecursively()
    }

    @Test fun unresolvedInternalConflictBlocksInitializationAndCanBeRetried() {
        val target = File(context.applicationInfo.dataDir, "restore-startup-${UUID.randomUUID()}")
        target.mkdir(); File(target, "data").writeText("original")
        val transaction = UserDataRestoreTransaction(UserDataRestoreJournal(context.noBackupFilesDir.canonicalFile)) { from, to ->
            check(from.renameTo(to)); throw ProcessStopped()
        }
        transaction.stage(target, false) { File(it, "data").writeText("import") }
        try { transaction.commit() } catch (_: ProcessStopped) { }
        target.mkdir(); File(target, "foreign").writeText("keep")
        assertTrue(runCatching { UserDataRestoreRuntime.recoverBeforeInitialization(context) }.isFailure)
        assertFalse(UserDataRestoreRuntime.retryRecovery(context).success)
        assertEquals("keep", File(target, "foreign").readText())
        target.deleteRecursively()
        assertTrue(UserDataRestoreRuntime.retryRecovery(context).success)
        assertEquals("original", File(target, "data").readText()); target.deleteRecursively()
    }

    @Test fun restoreActivityIsPrivateAndRunsInAnIsolatedProcess() {
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, UserDataRestoreActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals("${context.packageName}:restore", info.processName)
    }

    @Test fun isolatedProcessConfigurationChangeDoesNotInitializeThemeState() {
        ReflectionHelpers.setField(context, "restoreProcess", true)
        try { context.onConfigurationChanged(Configuration(context.resources.configuration)) }
        finally { ReflectionHelpers.setField(context, "restoreProcess", false) }
    }

    @Test fun invalidRequestIsRejectedWithoutReadingAnExternalFile() {
        val outcome = runCatching { UserDataRestoreRuntime.execute(context, "../../external", -1) }
        assertTrue(outcome.isFailure)
    }

    @Test fun isolatedApplicationDoesNotOpenPreferencesOrTheNativeEngine() {
        val previous = ReflectionHelpers.getStaticField<AppPrefs?>(AppPrefs::class.java, "instance")
        ReflectionHelpers.setStaticField(AppPrefs::class.java, "instance", null)
        ReflectionHelpers.setField(context, "restoreProcess", true)
        try {
            Shadow.directlyOn<Void>(context, FcitxApplication::class.java.name, "onCreate")
            context.onConfigurationChanged(Configuration(context.resources.configuration))
            assertNull(ReflectionHelpers.getStaticField<AppPrefs?>(AppPrefs::class.java, "instance"))
            assertSame(context, FcitxApplication.getInstance())
        } finally {
            ReflectionHelpers.setStaticField(AppPrefs::class.java, "instance", previous)
            ReflectionHelpers.setField(context, "restoreProcess", false)
        }
    }

    @Test fun pendingOptionalProfileAllowsSettingsButBlocksProfileOperations() {
        val volume = File(android.os.Environment.getExternalStorageDirectory(), "optional-volume-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        val target = File(volume, "profile").apply { mkdir(); File(this, "data").writeText("original") }
        val config = DirectRimeConfig(context).apply { load(); create("pending", target.path) }
        val transaction = UserDataRestoreTransaction(UserDataRestoreJournal(context.noBackupFilesDir.canonicalFile)) { from, to ->
            check(from.renameTo(to)); throw ProcessStopped()
        }
        transaction.stage(target, false) { File(it, "data").writeText("import") }
        try { transaction.commit() } catch (_: ProcessStopped) { }
        target.mkdir(); File(target, "foreign").writeText("keep")
        UserDataRestoreRuntime.recoverBeforeInitialization(context)
        assertTrue(runCatching { config.switch("pending") }.isFailure)
        assertTrue(runCatching { config.rename("pending", "renamed") }.isFailure)
        assertTrue(runCatching { config.delete("pending") }.isFailure)
        assertEquals("rime", config.current)
        assertEquals("keep", File(target, "foreign").readText())
        target.deleteRecursively()
        config.switch("pending")
        assertEquals("original", File(target, "data").readText())
        awaitBackgroundCleanup()
        volume.deleteRecursively()
    }

    @Test fun startupWithAnActiveRestoreFailsImmediatelyInsteadOfWaitingForItsLock() {
        val file = File(context.noBackupFilesDir, "user-data-restore.lock")
        java.io.RandomAccessFile(file, "rw").use { handle -> handle.channel.lock().use {
            val failure = runCatching { UserDataRestoreRuntime.recoverBeforeInitialization(context) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure!!.message!!.contains("still running"))
        } }
        UserDataRestoreRuntime.recoverBeforeInitialization(context)
    }

    @Test fun aReadyProfileDoesNotWaitForTheBackgroundCleanupLock() {
        UserDataRestoreRuntime.recoverBeforeInitialization(context)
        val file = File(context.noBackupFilesDir, "user-data-restore.lock")
        java.io.RandomAccessFile(file, "rw").use { handle -> handle.channel.lock().use {
            // Trying to lock the same file here would throw OverlappingFileLockException.
            // The process startup decision already guarantees these live roots are ready.
            UserDataRestoreRuntime.requirePathReady(context, File(context.applicationInfo.dataDir, "shared_prefs"))
        } }
    }

    private fun prepared(directory: File) = UserDataManager.PreparedImport(directory,
        UserDataManager.Metadata(context.packageName, 1, "test", 0), RimeProfileBackup.Manifest(externalProfiles = emptyList()))

    @Test fun aStablePlatformCacheAliasProducesACanonicalPrivateRequest() {
        val alias = File(context.cacheDir.parentFile, "cache-alias-${UUID.randomUUID()}")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), context.cacheDir.toPath())
        val owned = File(alias, "user-data-import-${UUID.randomUUID()}").apply { check(mkdir()) }
        val wrapped = object : android.content.ContextWrapper(context) { override fun getCacheDir() = alias }
        try {
            val token = UserDataRestoreRuntime.prepare(wrapped, prepared(owned), RimeProfileRestorePlan(emptyMap()))
            val request = File(context.noBackupFilesDir, "restore-requests/$token.json")
            assertEquals(owned.canonicalPath, org.json.JSONObject(request.readText()).getString("directory"))
            assertTrue(request.delete())
        } finally { owned.deleteRecursively(); alias.delete() }
    }

    @Test fun aQuarantineLeafCannotRedirectToAnotherOwnedQuarantine() {
        val target = File(context.cacheDir, "user-data-import-${UUID.randomUUID()}").apply { check(mkdir()) }
        val link = File(context.cacheDir, "user-data-import-${UUID.randomUUID()}")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        try {
            assertTrue(runCatching { UserDataRestoreRuntime.prepare(context, prepared(link), RimeProfileRestorePlan(emptyMap())) }.isFailure)
            assertTrue(target.isDirectory)
        } finally { link.delete(); target.deleteRecursively() }
    }

    @Test fun aQuarantineLeafCannotRedirectOutsideThePrivateCache() {
        val name = "user-data-import-${UUID.randomUUID()}"
        val target = File(context.filesDir, name).apply { check(mkdir()) }
        val link = File(context.cacheDir, name)
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        try {
            assertTrue(runCatching { UserDataRestoreRuntime.prepare(context, prepared(link), RimeProfileRestorePlan(emptyMap())) }.isFailure)
            assertTrue(target.isDirectory)
        } finally { link.delete(); target.deleteRecursively() }
    }
}
