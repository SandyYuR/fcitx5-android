/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One consumer per connection. Admission accounts for queued AND in-flight payloads. */
internal class RemoteClipboardQueue<T>(
    scope: CoroutineScope,
    private val maxBytes: Long,
    private val maxItems: Int = 32,
    private val weight: (T) -> Long,
    consume: suspend (T) -> Unit,
) : AutoCloseable {
    private data class Entry<T>(val value: T, val bytes: Long)
    private val channel = Channel<Entry<T>>(maxItems)
    private var bytes = 0L
    private var count = 0
    private var closed = false
    private val worker = scope.launch {
        for (entry in channel) {
            try {
                consume(entry.value)
            } finally {
                synchronized(this@RemoteClipboardQueue) {
                    bytes -= entry.bytes
                    count--
                }
            }
        }
    }

    @Synchronized
    fun offer(value: T): Boolean {
        val size = weight(value)
        if (closed || !worker.isActive || size < 0 || size > maxBytes - bytes || count >= maxItems) return false
        if (!channel.trySend(Entry(value, size)).isSuccess) return false
        bytes += size
        count++
        return true
    }

    @Synchronized
    override fun close() {
        closed = true
        channel.cancel()
        worker.cancel()
    }
}
