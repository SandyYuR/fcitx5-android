/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SyncStateMigrationSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(): Context {
        val files = temporary.newFolder("files")
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
        }
    }
    private val executor get() = SyncStateStore::class.java.getDeclaredField("writeExecutor")
        .apply { isAccessible = true }.get(null) as ExecutorService
    private fun flush() { executor.submit {}.get(10, TimeUnit.SECONDS) }

    @Test fun failedMigrationKeepsBothTheLegacyCopyAndTheOverlayThenRetries() {
        val context = context()
        val blocked = File(context.filesDir, "clipboardsync_state").apply { writeText("blocked") }
        val prefs = context.getSharedPreferences("failure", Context.MODE_PRIVATE)
        prefs.edit().putString("suppressed_remote_items", "personal data").commit()
        val store = SyncStateStore(context)
        store.migrateFromPreferences(prefs, listOf("suppressed_remote_items"))
        flush()
        assertEquals("personal data", prefs.getString("suppressed_remote_items", null))
        assertEquals("personal data", store.read("suppressed_remote_items"))
        assertTrue(blocked.delete())
        store.write("other", "trigger retry")
        flush()
        assertFalse(prefs.contains("suppressed_remote_items"))
        assertEquals("personal data", SyncStateStore(context).read("suppressed_remote_items"))
    }

    @Test fun newerQueuedWritesAndDeletesWinOverLegacyMigration() {
        val context = context()
        val prefs = context.getSharedPreferences("newer", Context.MODE_PRIVATE)
        prefs.edit().putString("new", "legacy").putString("deleted", "legacy").commit()
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { parked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(parked.await(10, TimeUnit.SECONDS))
        val store = SyncStateStore(context)
        try {
            store.write("new", "newer")
            store.write("deleted", null)
            store.migrateFromPreferences(prefs, listOf("new", "deleted"))
            assertEquals("newer", store.read("new"))
            assertNull(store.read("deleted"))
        } finally { release.countDown() }
        flush()
        assertFalse(prefs.contains("new"))
        assertFalse(prefs.contains("deleted"))
        assertEquals("newer", SyncStateStore(context).read("new"))
        assertNull(SyncStateStore(context).read("deleted"))
    }

    @Test fun anExistingDurableValueTakesPrecedence() {
        val context = context()
        val store = SyncStateStore(context)
        store.write("key", "existing")
        flush()
        val prefs = context.getSharedPreferences("existing", Context.MODE_PRIVATE)
        prefs.edit().putString("key", "legacy").commit()
        store.migrateFromPreferences(prefs, listOf("key"))
        flush()
        assertFalse(prefs.contains("key"))
        assertEquals("existing", SyncStateStore(context).read("key"))
    }
}
