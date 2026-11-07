/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.testing

import org.fcitx.fcitx5.android.core.InputKeyGate
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

internal interface FcitxDaemonTestDelegate {
    fun connect(name: String): FcitxConnection = error("Unexpected daemon connect: $name")
    fun disconnect(name: String): Unit = error("Unexpected daemon disconnect: $name")
    fun getCurrentInputSession(): Long = error("Unexpected daemon currentInputSession")
    fun requireInputSession(): Boolean = error("Unexpected daemon requireInputSession")
    fun captureInputKey(): InputKeyGate.Ticket? = error("Unexpected daemon captureInputKey")
    fun canDeliverInputKey(ticket: InputKeyGate.Ticket): Boolean = error("Unexpected daemon canDeliverInputKey")
    fun releaseInputKey(ticket: InputKeyGate.Ticket): Unit = error("Unexpected daemon releaseInputKey")
}

/** Kotlin object instances retain their shadow across reused sandboxes. Keep one type and reset only its behavior. */
@Implements(FcitxDaemon::class, isInAndroidSdk = false)
internal class FcitxDaemonTestShadow {
    companion object {
        @Volatile private var delegate: FcitxDaemonTestDelegate? = null

        fun install(value: FcitxDaemonTestDelegate) { delegate = value }

        @JvmStatic @Resetter fun reset() { delegate = null }

        private fun current() = checkNotNull(delegate) { "Install a fresh FcitxDaemon test delegate before using the daemon" }
    }

    @Implementation fun connect(name: String) = current().connect(name)
    @Implementation fun disconnect(name: String) = current().disconnect(name)
    @Implementation fun getCurrentInputSession() = current().getCurrentInputSession()
    @Implementation fun requireInputSession() = current().requireInputSession()
    @Implementation fun captureInputKey() = current().captureInputKey()
    @Implementation fun canDeliverInputKey(ticket: InputKeyGate.Ticket) = current().canDeliverInputKey(ticket)
    @Implementation fun releaseInputKey(ticket: InputKeyGate.Ticket) = current().releaseInputKey(ticket)
}
