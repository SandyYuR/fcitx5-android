/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.content.Context
import androidx.work.Data
import androidx.work.workDataOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File

@Serializable
internal data class WanxiangSkippedAsset(
    val kind: WanxiangPackage.Kind,
    val name: String,
    val source: WanxiangSelection.Source,
    // Keep the failing URL for diagnostics, outside the user-facing summary.
    val details: String
)

/** Full results live beside the private job, not in WorkManager's 10 KiB Data. */
@Serializable
internal data class WanxiangUpdateResult(
    val target: String? = null,
    val noChanges: Boolean = false,
    val skipped: List<WanxiangSkippedAsset> = emptyList(),
    val noAvailableAssets: Boolean = false,
    val error: String? = null
) {
    fun skippedSummary(context: Context): String = skipped.groupBy { it.source }.entries.joinToString("\n") { (source, assets) ->
        context.getString(R.string.wanxiang_skipped_hint,
            if (source == WanxiangSelection.Source.CNB) "CNB" else "GitHub",
            assets.joinToString(", ") { "${context.getString(WanxiangUpdateWorker.kindLabel(it.kind))} (${it.name})" })
    }

    fun outputData(context: Context): Data = workDataOf(
        // JSON must stay intact. An unusually long target is available in the full result.
        WanxiangUpdateWorker.TARGET to target?.takeIf { it.length <= PREVIEW_CHARS },
        WanxiangUpdateWorker.NO_CHANGES to noChanges,
        WanxiangUpdateWorker.NO_AVAILABLE_ASSETS to noAvailableAssets,
        WanxiangUpdateWorker.SKIPPED to preview(skippedSummary(context)),
        WanxiangUpdateWorker.ERROR to error?.let(::preview)
    )

    fun save(directory: File, completed: Boolean) {
        val text = Json.encodeToString(this)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Update result exceeds limit" }
        FileUtil.writeAtomically(WanxiangPayload.child(directory, if (completed) COMPLETED else FAILED), text)
    }

    companion object {
        const val COMPLETED = "completed"
        const val FAILED = "failure.json"
        private const val MAX_BYTES = 1024 * 1024
        // Three strings of at most 1,000 UTF-16 units require at most 9,000 bytes
        // even with modified UTF-8, leaving room for Data's keys and type headers.
        private const val PREVIEW_CHARS = 1000

        private fun preview(text: String): String {
            if (text.length <= PREVIEW_CHARS) return text
            val end = (PREVIEW_CHARS - 1).let { if (text[it - 1].isHighSurrogate()) it - 1 else it }
            return text.take(end) + "…"
        }

        fun read(directory: File, completed: Boolean, legacyTarget: String? = null): WanxiangUpdateResult? {
            val file = WanxiangPayload.child(directory, if (completed) COMPLETED else FAILED)
            if (!file.isFile) return null
            require(file.length() <= MAX_BYTES) { "Update result exceeds limit" }
            return when (val text = file.readText()) {
                // Existing jobs only recorded whether installation changed any files.
                "1" -> WanxiangUpdateResult(target = legacyTarget)
                "no-changes" -> WanxiangUpdateResult(target = legacyTarget, noChanges = true)
                else -> Json.decodeFromString<WanxiangUpdateResult>(text)
            }
        }
    }
}
