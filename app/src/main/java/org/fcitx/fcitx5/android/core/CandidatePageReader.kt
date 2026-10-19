/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * Fetch one logical page without monopolizing librime's owner for a large Paging request.
 * Each [read] must enqueue a separate owner task and return null if the captured
 * composition is no longer current. Never return a page assembled from two compositions.
 */
internal suspend fun <T> readCandidatePage(
    offset: Int,
    limit: Int,
    batchSize: Int = 8,
    read: suspend (offset: Int, limit: Int) -> List<T>?,
): List<T> {
    require(offset >= 0 && limit in 1..256 && batchSize in 1..256)
    require(offset <= Int.MAX_VALUE - limit)
    val result = ArrayList<T>(limit)
    while (result.size < limit) {
        currentCoroutineContext().ensureActive()
        val count = minOf(batchSize, limit - result.size)
        val batch = read(offset + result.size, count)
            ?: throw CancellationException("Candidate composition changed")
        check(batch.size <= count)
        result.addAll(batch)
        if (batch.size < count) break
        // The next native batch is a new queue entry, so keys already waiting on
        // rime-main run first. Also let their service jobs reach that queue.
        if (result.size < limit) yield()
    }
    return result
}
