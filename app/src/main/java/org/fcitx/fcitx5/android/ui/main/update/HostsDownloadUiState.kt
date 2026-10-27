/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import android.net.Uri
import androidx.work.WorkInfo

internal object HostsDownloadUiState {
    fun apply(state: AssetUiState, work: WorkInfo): AssetUiState {
        val done = work.state == WorkInfo.State.SUCCEEDED
        val data = if (done) work.outputData else work.progress
        val bytes = data.getLong(HostsDownloadWorker.BYTES, 0).coerceAtLeast(0)
        val total = if (done) bytes else data.getLong(HostsDownloadWorker.TOTAL, state.asset.sizeBytes).coerceAtLeast(0)
        val uri = work.outputData.getString(HostsDownloadWorker.URI)?.let(Uri::parse)
        val status = when (work.state) {
            WorkInfo.State.SUCCEEDED -> if (uri != null) AssetStatus.FINISHED else AssetStatus.FAILED
            WorkInfo.State.FAILED -> AssetStatus.FAILED
            WorkInfo.State.CANCELLED -> AssetStatus.CANCELED
            else -> AssetStatus.DOWNLOADING
        }
        return state.copy(status = status, downloadId = null, workId = work.id, installUri = if (done) uri else null,
            downloadedBytes = bytes, totalBytes = total,
            progressPercent = if (done) 100 else if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0,
            speedBytesPerSec = if (work.state == WorkInfo.State.RUNNING) data.getLong(HostsDownloadWorker.SPEED, 0) else 0,
            error = work.outputData.getString(HostsDownloadWorker.ERROR))
    }
}
