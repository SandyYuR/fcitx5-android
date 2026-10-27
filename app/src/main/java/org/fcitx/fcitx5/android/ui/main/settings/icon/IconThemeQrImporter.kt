/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.QrChunkCollector

/** Owns one scan's mutable collector; decompression, parsing and writes all run off Main. */
internal class IconThemeQrImporter(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val saveTheme: (IconTheme) -> Unit = { IconThemeManager.saveTheme(it) },
) {
    sealed interface ScanResult {
        data object Invalid : ScanResult
        data class WrongType(val type: Char) : ScanResult
        data class Progress(val progress: QrChunkCollector.Progress, val theme: IconTheme?) : ScanResult
    }

    private val collector = QrChunkCollector()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun importImageChunks(chunks: List<String>): IconTheme = mutex.withLock {
        withContext(dispatcher) { decodeAndSave(LayoutQrTransferCodec.decodeChunksToJson(chunks)) }
    }

    suspend fun addScan(raw: String): ScanResult = mutex.withLock {
        withContext(dispatcher) {
            val header = JsonFileQrShareManager.parseQrPayload(raw) ?: return@withContext ScanResult.Invalid
            val type = LayoutQrTransferCodec.detectTransferType(header.transferId)
            if (type != null && type != LayoutQrTransferCodec.TRANSFER_TYPE_ICON_THEME) {
                return@withContext ScanResult.WrongType(type)
            }
            val progress = collector.addAndMaybeAssemble(raw) ?: return@withContext ScanResult.Invalid
            ScanResult.Progress(progress, progress.completedJson?.let { decodeAndSave(it) })
        }
    }

    private suspend fun decodeAndSave(raw: String): IconTheme {
        currentCoroutineContext().ensureActive()
        // Retain legacy raw IconTheme documents, but never retry a failed write as another format.
        val theme = if (IconThemeQrTransferCodec.detectSchema(raw) == null) {
            json.decodeFromString<IconTheme>(raw)
        } else {
            IconThemeQrTransferCodec.decodeIconThemeFromJson(raw)
        }
        currentCoroutineContext().ensureActive()
        saveTheme(theme)
        return theme
    }
}
