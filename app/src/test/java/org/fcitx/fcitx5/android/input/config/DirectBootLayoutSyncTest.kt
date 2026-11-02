/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import android.app.Application
import android.os.FileObserver
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class DirectBootLayoutSyncTest {
    private var sync: DirectBootLayoutSync? = null
    private val context get() = RuntimeEnvironment.getApplication()
    private val config get() = File(context.getExternalFilesDir(null), "config").apply { mkdirs() }
    private val destination get() = File(context.createDeviceProtectedStorageContext().filesDir, "layout.json")
    private val first = """{"default":[[{"type":"AlphabetKey","main":"a"}]]}"""
    private val second = """{"default":[[{"type":"AlphabetKey","main":"b"}]]}"""

    @After fun close() = runBlocking { sync?.close(); sync?.join(); Unit }

    private fun start(profileName: String = "default"): ManagedPreference.PString {
        val prefs = context.getSharedPreferences("layout-sync", 0)
        val profile = ManagedPreference.PString(prefs, "profile", "default")
        profile.setValue(profileName)
        sync = DirectBootLayoutSync(context, profile, destination)
        return profile
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Layout sync did not finish")
            Thread.sleep(10)
        }
    }

    private fun awaitContent(expected: String) = awaitCondition {
        destination.isFile && destination.readText() == expected
    }

    @Test fun startupAndProfileChangesSyncWithoutShowingAKeyboard() {
        File(config, "TextKeyboardLayout.json").writeText(first)
        File(config, "TextKeyboardLayout.custom.json").writeText(second)
        val profile = start()
        awaitContent(first)
        profile.setValue("custom")
        // AppPrefs dispatches SharedPreferences changes to this managed listener.
        profile.fireChange()
        awaitContent(second)
        profile.setValue("missing")
        profile.fireChange()
        awaitCondition { !destination.exists() }
    }

    @Test fun completedWritesAndDeletionsRefreshTheSnapshot() {
        val source = File(config, "TextKeyboardLayout.json").apply { writeText(first) }
        start()
        awaitContent(first)
        val watcher = ReflectionHelpers.getField<FileObserver>(sync, "configWatcher")
        source.writeText(second)
        watcher.onEvent(FileObserver.CLOSE_WRITE, source.name)
        awaitContent(second)
        assertTrue(source.delete())
        watcher.onEvent(FileObserver.DELETE, source.name)
        awaitCondition { !destination.exists() }
    }

    @Test fun replacingTheConfigDirectoryRearmsTheWatcher() {
        File(config, "TextKeyboardLayout.json").writeText(first)
        start()
        awaitContent(first)
        val oldWatcher = ReflectionHelpers.getField<FileObserver>(sync, "configWatcher")
        assertTrue(config.deleteRecursively())
        File(config, "TextKeyboardLayout.json").writeText(second)
        val rootWatcher = ReflectionHelpers.getField<FileObserver>(sync, "rootWatcher")
        rootWatcher.onEvent(FileObserver.MOVED_TO, "config")
        awaitContent(second)
        assertNotSame(oldWatcher, ReflectionHelpers.getField<FileObserver>(sync, "configWatcher"))
    }
}
