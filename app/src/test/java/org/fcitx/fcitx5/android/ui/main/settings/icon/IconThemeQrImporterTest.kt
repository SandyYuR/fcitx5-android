/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = android.app.Application::class)
class IconThemeQrImporterTest {
    private val theme = IconTheme("二维码测试", icons = mapOf("toolbar.more" to "★"))
    private fun chunks(theme: IconTheme = this.theme) = IconThemeQrTransferCodec.encodeIconThemeToChunks(theme)
        .chunks.map { it.encode() }

    @Test fun imageImportDecodesAndSavesOnWorkerThenReturnsToCaller() = runBlocking {
        val caller = Thread.currentThread()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            var saved: IconTheme? = null
            val importer = IconThemeQrImporter(worker) {
                assertNotSame(caller, Thread.currentThread())
                saved = it
            }
            assertEquals(theme, importer.importImageChunks(chunks()))
            assertEquals(theme, saved)
            assertSame(caller, Thread.currentThread())
        }
    }

    @Test fun cameraFinalChunkSavesOnWorker() = runBlocking {
        val caller = Thread.currentThread()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val saved = mutableListOf<IconTheme>()
            val importer = IconThemeQrImporter(worker) { assertNotSame(caller, Thread.currentThread()); saved += it }
            val result = importer.addScan(chunks().single()) as IconThemeQrImporter.ScanResult.Progress
            assertEquals(theme, result.theme)
            assertEquals(listOf(theme), saved)
        }
    }

    @Test fun incompleteAndDuplicateChunksDoNotWriteAndFinishOnlyOnce() = runBlocking {
        val random = Random(7)
        val large = theme.copy(author = (1..8000).map { ('!'..'~').random(random) }.joinToString(""))
        val parts = chunks(large)
        assertTrue(parts.size > 1)
        val saved = mutableListOf<IconTheme>()
        val importer = IconThemeQrImporter(saveTheme = { saved += it })
        val first = importer.addScan(parts.first()) as IconThemeQrImporter.ScanResult.Progress
        assertNull(first.theme)
        val duplicate = importer.addScan(parts.first()) as IconThemeQrImporter.ScanResult.Progress
        assertTrue(duplicate.progress.duplicate)
        assertTrue(saved.isEmpty())
        parts.drop(1).forEach { importer.addScan(it) }
        assertEquals(listOf(large), saved)
    }

    @Test fun wrongTransferTypeDoesNotPolluteTheCameraCollector() = runBlocking {
        val saved = mutableListOf<IconTheme>()
        val importer = IconThemeQrImporter(saveTheme = { saved += it })
        val wrong = LayoutQrTransferCodec.encodeJsonToChunks("{}", LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT)
        assertEquals(IconThemeQrImporter.ScanResult.WrongType(LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT),
            importer.addScan(wrong.chunks.single().encode()))
        assertEquals(theme, (importer.addScan(chunks().single()) as IconThemeQrImporter.ScanResult.Progress).theme)
        assertEquals(listOf(theme), saved)
    }

    @Test fun invalidScanDoesNotWrite() = runBlocking {
        val importer = IconThemeQrImporter(saveTheme = { fail("Invalid scan must not write") })
        assertEquals(IconThemeQrImporter.ScanResult.Invalid, importer.addScan("not a QR chunk"))
    }

    @Test fun legacyRawThemeDocumentStillImports() = runBlocking {
        var saved: IconTheme? = null
        val importer = IconThemeQrImporter(saveTheme = { saved = it })
        val raw = LayoutQrTransferCodec.encodeJsonToChunks(Json.encodeToString(theme)).chunks.map { it.encode() }
        assertEquals(theme, importer.importImageChunks(raw))
        assertEquals(theme, saved)
    }

    @Test fun malformedEnvelopeAndUnknownSchemaNeverWrite() = runBlocking {
        val importer = IconThemeQrImporter(saveTheme = { fail("Invalid envelope must not write") })
        for (raw in listOf("{}", "{\"schema\":\"future\",\"name\":\"legacy-looking\"}")) {
            val parts = LayoutQrTransferCodec.encodeJsonToChunks(raw).chunks.map { it.encode() }
            assertTrue(runCatching { importer.importImageChunks(parts) }.isFailure)
        }
    }

    @Test fun saveFailureIsPropagatedWithoutRetryingAnotherDecoder() = runBlocking {
        var writes = 0
        val expected = java.io.IOException("disk full")
        val importer = IconThemeQrImporter(saveTheme = { writes++; throw expected })
        val failure = runCatching { importer.importImageChunks(chunks()) }.exceptionOrNull()
        // Coroutine stack recovery may copy IOException when crossing the dispatcher boundary.
        assertTrue(failure is java.io.IOException)
        assertEquals(expected.message, failure?.message)
        assertEquals(1, writes)
    }

    @Test fun cancellationBeforeWorkerStartsDoesNotDecodeOrSave() = runBlocking {
        val pending = ArrayDeque<Runnable>()
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val importer = IconThemeQrImporter(worker) { fail("Cancelled import must not write") }
        val load = async(start = CoroutineStart.UNDISPATCHED) { importer.importImageChunks(chunks()) }
        load.cancel()
        while (pending.isNotEmpty()) pending.removeFirst().run()
        load.join()
        assertTrue(load.isCancelled)
    }
}
