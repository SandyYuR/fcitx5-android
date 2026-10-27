/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClipboardUrlHistoryTest {
    @Test fun firstSystemCopyIsFilteredWithoutPluginsAndPluginsSeeTheFilteredUrl() = runBlocking {
        ClipboardManager.init(appContext)
        val oldTransformer = ClipboardManager.transformer
        val marker = UUID.randomUUID().toString()
        suspend fun finishWork() {
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(10_000) {
                ClipboardManager.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            }
        }
        try {
            ClipboardManager.transformer = null
            val plain = "https://example.test/?id=$marker&value=a%26b"
            appContext.clipboardManager.setPrimaryClip(ClipData.newPlainText("fixture", "$plain&utm_source=pc"))
            ClipboardManager.onPrimaryClipChanged()
            finishWork()
            assertEquals(plain, ClipboardManager.searchEntries(marker).single().text)

            val observed = AtomicReference<String>()
            ClipboardManager.transformer = { text ->
                assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                observed.set(text)
                "$text&plugin=1"
            }
            val second = "https://example.test/?id=$marker-second&value=%2526"
            // Robolectric's clock is fixed; give independent copies distinct platform
            // timestamps so the production duplicate-notification guard can distinguish them.
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            appContext.clipboardManager.setPrimaryClip(ClipData.newPlainText("fixture", "$second&utm_source=pc"))
            ClipboardManager.onPrimaryClipChanged()
            finishWork()
            assertEquals(second, observed.get())
            assertEquals("$second&plugin=1", ClipboardManager.searchEntries("$marker-second").single().text)
        } finally {
            ClipboardManager.transformer = oldTransformer
            finishWork()
            ClipboardManager.closeDatabase()
        }
    }
}
