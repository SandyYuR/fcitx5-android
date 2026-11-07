/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.stats.InputStats
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.Random
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class StatsImageRendererTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pngChunksHaveValidCrcAndDecodeEveryRowAcrossCompressionBoundaries() {
        val width = 73
        val height = 1200
        val random = Random(42)
        val expected = Array(height) { IntArray(width) { random.nextInt() or -0x1000000 } }
        val output = ByteArrayOutputStream()
        StatsPngWriter(output, width, height).use { writer -> expected.forEach(writer::writeRow); writer.finish() }
        val png = output.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(png))
        assertEquals(0x89504E470D0A1A0AuL.toLong(), input.readLong())
        val chunks = mutableListOf<String>()
        while (input.available() > 0) {
            val count = input.readInt()
            val type = ByteArray(4).also(input::readFully)
            val data = ByteArray(count).also(input::readFully)
            val crc = CRC32().apply { update(type); update(data) }.value
            assertEquals(crc, input.readInt().toLong() and 0xffffffffL)
            chunks += type.toString(Charsets.US_ASCII)
        }
        assertEquals("IHDR", chunks.first())
        assertEquals("IEND", chunks.last())
        assertTrue(chunks.count { it == "IDAT" } > 1)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        try {
            assertEquals(width, decoded.width); assertEquals(height, decoded.height)
            for (y in listOf(0, 511, 512, 1023, 1199)) for (x in listOf(0, 36, 72)) {
                assertEquals("Pixel $x,$y", expected[y][x], decoded.getPixel(x, y))
            }
        } finally { decoded.recycle() }
    }

    @Test fun tiledViewExportPreservesFullHeightAndCancellationRemovesPartialOutput() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val paint = android.graphics.Paint()
            val view = object : View(activity) {
                override fun onDraw(canvas: Canvas) {
                    paint.color = Color.RED; canvas.drawRect(0f, 0f, 128f, 512f, paint)
                    paint.color = Color.GREEN; canvas.drawRect(0f, 512f, 128f, 1024f, paint)
                    paint.color = Color.BLUE; canvas.drawRect(0f, 1024f, 128f, 1300f, paint)
                }
            }
            view.layout(0, 0, 128, 1300)
            val file = File(temporary.root, "report.png")
            val exported = activity.lifecycleScope.async { StatsImageRenderer.render(view, file) }
            await(exported)
            runBlocking { exported.await() }
            val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
            try {
                assertEquals(1300, decoded.height)
                assertEquals(Color.RED, decoded.getPixel(64, 511))
                assertEquals(Color.GREEN, decoded.getPixel(64, 512))
                assertEquals(Color.GREEN, decoded.getPixel(64, 1023))
                assertEquals(Color.BLUE, decoded.getPixel(64, 1024))
                assertEquals(Color.BLUE, decoded.getPixel(64, 1299))
            } finally { decoded.recycle() }
            val cancelledFile = File(temporary.root, "cancelled.png")
            val cancelledView = object : View(activity) {
                override fun onDraw(canvas: Canvas) { throw CancellationException("Cancelled image export") }
            }.apply { layout(0, 0, 64, 600) }
            val cancelled = activity.lifecycleScope.async { StatsImageRenderer.render(cancelledView, cancelledFile) }
            await(cancelled)
            assertTrue(runCatching { runBlocking { cancelled.await() } }.exceptionOrNull() is CancellationException)
            assertFalse(cancelledFile.exists())
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun clearingStatisticsDuringDrawingCancelsAndRemovesTheImage() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val stats = InputStats(controller.get(), "share-clear-during-draw.db")
        try {
            val snapshot = runBlocking {
                withTimeout(10_000) { stats.settings.first { it.loaded } }
                stats.setEnabled(true)
                stats.recordCommit("one", "One", "中文", 3)
                stats.snapshot()
            }
            var cleared = false
            val view = object : View(controller.get()) {
                override fun onDraw(canvas: Canvas) {
                    if (!cleared) {
                        cleared = true
                        runBlocking { stats.clearAllData() }
                    }
                }
            }.apply { layout(0, 0, 64, 1300) }
            val file = File(temporary.root, "cancelled-after-clear.png")
            val exported = controller.get().lifecycleScope.async {
                StatsImageRenderer.render(view, file) {
                    if (snapshot.recordingEpoch != stats.recordingEpoch) throw CancellationException("Statistics cleared")
                }
            }
            await(exported)
            assertTrue(cleared)
            assertTrue(runCatching { runBlocking { exported.await() } }.exceptionOrNull() is CancellationException)
            assertFalse(file.exists())
            assertTrue(runBlocking { stats.snapshot() }.schemas.isEmpty())
        } finally {
            runBlocking { stats.close() }
            controller.get().finish(); controller.pause().stop().destroy()
        }
    }

    @Test fun aClearBetweenDirectoryCreationAndOpeningCannotRecreateAnOldImage() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val stats = InputStats(controller.get(), "share-clear-before-open.db")
        try {
            val snapshot = runBlocking {
                withTimeout(10_000) { stats.settings.first { it.loaded } }
                stats.setEnabled(true)
                stats.snapshot()
            }
            val folder = File(controller.get().cacheDir, "shared/input-statistics")
            assertFalse(folder.exists())
            val file = File(folder, "00000000-0000-0000-0000-000000000000.png")
            val view = View(controller.get()).apply { layout(0, 0, 64, 600) }
            var cleared = false
            val exported = controller.get().lifecycleScope.async {
                StatsImageRenderer.render(view, file) {
                    if (!cleared && folder.isDirectory && !file.exists()) {
                        runBlocking { stats.clearAllData() }
                        cleared = true
                        // Return once to model a clear winning immediately after the
                        // pre-open check. The later guard must still reject this image.
                    } else if (snapshot.recordingEpoch != stats.recordingEpoch) {
                        throw CancellationException("Statistics cleared")
                    }
                }
            }
            await(exported)
            assertTrue(cleared)
            assertTrue(runCatching { runBlocking { exported.await() } }.exceptionOrNull() is CancellationException)
            assertFalse(file.exists())
            assertFalse(folder.exists())
        } finally {
            runBlocking { stats.close() }
            controller.get().finish(); controller.pause().stop().destroy()
        }
    }

    private fun await(job: Deferred<*>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!job.isCompleted) {
            check(System.nanoTime() < deadline) { "Image export did not finish" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }
}
