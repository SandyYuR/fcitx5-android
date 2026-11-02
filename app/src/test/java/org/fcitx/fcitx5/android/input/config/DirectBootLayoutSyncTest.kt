/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.FileObserver
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class, shadows = [DirectoryFileObserverShadow::class])
class DirectBootLayoutSyncTest {
    private var sync: DirectBootLayoutSync? = null
    private val context get() = RuntimeEnvironment.getApplication()
    private val root get() = context.getExternalFilesDir(null)!!
    private val config get() = File(root, "config")
    private val destination get() = File(context.createDeviceProtectedStorageContext().filesDir, "layout.json")
    private val first = """{"default":[[{"type":"AlphabetKey","main":"a"}]]}"""
    private val second = """{"default":[[{"type":"AlphabetKey","main":"b"}]]}"""

    private open class TestProvider(private val directory: File?) : ConfigProvider {
        override fun textKeyboardLayoutFile() = directory?.let { File(it, "TextKeyboardLayout.json") }
        override fun textKeyboardLayoutJson() = if (directory == null) JsonObject(emptyMap()) else null
        override fun popupPresetFile() = directory?.let { File(it, "PopupPreset.json") }
        override fun fontsetFile(): File? = null
        override fun buttonsLayoutConfigFile() = directory?.let { File(it, "ButtonsLayout.json") }
        override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
            Result.failure(UnsupportedOperationException())
    }

    private class MountContext(base: Context) : ContextWrapper(base) {
        val mounted = AtomicBoolean(false)
        val calls = AtomicInteger()
        val firstLookup = CountDownLatch(1)
        override fun getExternalFilesDir(type: String?): File? {
            calls.incrementAndGet()
            val result = if (mounted.get()) super.getExternalFilesDir(type) else null
            firstLookup.countDown()
            return result
        }
    }

    @After fun close() = runBlocking {
        sync?.close()
        sync?.join()
        for (field in listOf("textLayoutListeners", "popupPresetListeners", "fontsetListeners",
            "buttonsLayoutListeners", "iconChangeListeners")) {
            ReflectionHelpers.getField<MutableSet<() -> Unit>>(ConfigProviders, field).clear()
        }
        ConfigProviders.provider = TestProvider(null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun write(name: String = "TextKeyboardLayout.json", content: String = first): File =
        File(config.apply { mkdirs() }, name).apply { writeText(content) }

    private fun start(profileName: String = "default", syncContext: Context = context): ManagedPreference.PString {
        val prefs = context.getSharedPreferences("layout-sync", 0)
        val profile = ManagedPreference.PString(prefs, "profile", "default")
        profile.setValue(profileName)
        sync = DirectBootLayoutSync(syncContext, profile, destination, initialRetryMillis = 25L)
        return profile
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Layout sync did not finish")
            Thread.sleep(10)
        }
    }

    private fun awaitContent(expected: String) = awaitCondition {
        destination.isFile && destination.readText() == expected
    }

    @Test fun startupAndProfileChangesSyncWithoutShowingAKeyboard() {
        write()
        write("TextKeyboardLayout.custom.json", second)
        val profile = start()
        awaitContent(first)
        profile.setValue("custom")
        profile.fireChange()
        awaitContent(second)
        profile.setValue("missing")
        profile.fireChange()
        awaitCondition { !destination.exists() }
    }

    @Test fun completedWritesAndDeletionsRefreshTheSnapshot() {
        val source = write()
        start()
        awaitContent(first)
        source.writeText(second)
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, source.name)
        awaitContent(second)
        assertTrue(source.delete())
        DirectoryFileObserverShadow.emit(config, FileObserver.DELETE, source.name)
        awaitCondition { !destination.exists() }
    }

    @Test fun replacingTheConfigDirectoryRearmsTheWatcher() {
        write()
        start()
        awaitContent(first)
        val starts = DirectoryFileObserverShadow.starts(config)
        assertTrue(config.deleteRecursively())
        write(content = second)
        DirectoryFileObserverShadow.emit(root, FileObserver.MOVED_TO, "config")
        awaitContent(second)
        assertTrue(DirectoryFileObserverShadow.starts(config) > starts)
        val source = write()
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, source.name)
        awaitContent(first)
    }

    @Test fun missingConfigDirectoryIsWatchedWhenItAppears() {
        config.deleteRecursively()
        destination.writeText(first)
        start()
        awaitCondition { !destination.exists() }
        assertFalse(config.exists())
        write(content = second)
        DirectoryFileObserverShadow.emit(root, FileObserver.CREATE, "config")
        awaitContent(second)
        assertTrue(DirectoryFileObserverShadow.isWatching(config))
    }

    private fun verifySharedWatching(syncFirst: Boolean) {
        val source = write()
        val layouts = AtomicInteger()
        val buttons = AtomicInteger()
        if (syncFirst) { start(); awaitContent(first) }
        ConfigProviders.provider = TestProvider(config)
        ConfigProviders.addTextKeyboardLayoutListener { layouts.incrementAndGet() }
        ConfigProviders.addButtonsLayoutListener { buttons.incrementAndGet() }
        if (!syncFirst) { start(); awaitContent(first) }
        assertEquals("Only one native watcher may own config", 1, DirectoryFileObserverShadow.starts(config))
        source.writeText(second)
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, source.name)
        awaitContent(second)
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, "ButtonsLayout.json")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, layouts.get())
        assertEquals(1, buttons.get())
        assertTrue(source.delete())
        DirectoryFileObserverShadow.emit(config, FileObserver.DELETE, source.name)
        awaitCondition { !destination.exists() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, layouts.get())
    }

    @Test fun showingKeyboardAfterStartingSyncKeepsBothConsumersWorking() = verifySharedWatching(true)

    @Test fun startingSyncAfterShowingKeyboardKeepsBothConsumersWorking() = verifySharedWatching(false)

    @Test fun stoppingSyncKeepsConfigHotReloadWorking() = runBlocking {
        verifySharedWatching(true)
        sync!!.close()
        sync!!.join()
        val calls = AtomicInteger()
        ConfigProviders.addTextKeyboardLayoutListener { calls.incrementAndGet() }
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, "TextKeyboardLayout.json")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls.get())
    }

    @Test fun restartingConfigProviderKeepsSnapshotSyncWorking() {
        verifySharedWatching(false)
        ConfigProviders.provider = TestProvider(null)
        val source = write(content = second)
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, source.name)
        awaitContent(second)
        assertEquals(1, DirectoryFileObserverShadow.starts(config))
    }

    @Test fun storageRecoveryRetriesWithoutPreferenceChangeOrRestart() {
        val source = write(content = second)
        destination.writeText(first)
        val mount = MountContext(context)
        start(syncContext = mount)
        assertTrue(mount.firstLookup.await(5, TimeUnit.SECONDS))
        assertEquals(first, destination.readText())
        mount.mounted.set(true)
        awaitContent(second)
        assertTrue(mount.calls.get() >= 2)
        source.writeText(first)
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, source.name)
        awaitContent(first)
    }

    @Test fun closeCancelsStorageRecoveryRetries() = runBlocking {
        destination.writeText(first)
        val mount = MountContext(context)
        start(syncContext = mount)
        assertTrue(mount.firstLookup.await(5, TimeUnit.SECONDS))
        sync!!.close()
        sync!!.join()
        val calls = mount.calls.get()
        mount.mounted.set(true)
        Thread.sleep(150)
        assertEquals(calls, mount.calls.get())
        assertEquals(first, destination.readText())
    }

    @Test fun unrelatedLayoutsAndBackupFilesDoNotTriggerSnapshotReads() {
        val source = write()
        start()
        awaitContent(first)
        source.writeText(second)
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, "TextKeyboardLayout.other.json")
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, "TextKeyboardLayout.json.bak")
        Thread.sleep(150)
        assertEquals(first, destination.readText())
        DirectoryFileObserverShadow.emit(config, FileObserver.CLOSE_WRITE, source.name)
        awaitContent(second)
    }
}
