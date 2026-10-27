/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

/** Main-thread request ownership for menus whose data may arrive after their UI is gone. */
internal class InputMenuRequestTracker {
    private var current: Any? = null

    class Request internal constructor(private val current: () -> Boolean) {
        fun isCurrent(): Boolean = current()
    }

    fun newRequest(contextIsCurrent: () -> Boolean): Request {
        val identity = Any()
        current = identity
        return Request { current === identity && contextIsCurrent() }
    }

    fun invalidate() { current = null }

    suspend fun <T : Any> loadIfCurrent(request: Request, load: suspend () -> T): T? {
        if (!request.isCurrent()) return null
        val result = load()
        return result.takeIf { request.isCurrent() }
    }
}
