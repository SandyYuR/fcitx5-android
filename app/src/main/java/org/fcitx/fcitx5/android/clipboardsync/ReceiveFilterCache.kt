/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

/** Invalidation cannot be overwritten by a constructor that read an older preference state. */
internal class ReceiveFilterCache<T : Any> {
    private var cached: T? = null

    @Synchronized fun getOrLoad(load: () -> T): T = cached ?: load().also { cached = it }
    @Synchronized fun invalidate() { cached = null }
}
