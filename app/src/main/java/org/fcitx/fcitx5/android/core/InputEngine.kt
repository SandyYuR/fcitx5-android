/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Lifecycle belongs to the daemon; the keyboard and settings only see FcitxAPI. */
interface InputEngine : FcitxAPI, FcitxLifecycleOwner {
    fun start()
    fun stop()
    /** Invalidate input immediately and queue native shutdown without blocking the caller. */
    fun requestStop()
    fun prepareInputSession(generation: Long, program: String, flags: CapabilityFlags, needsRime: Boolean)
    fun requireInputSession(generation: Long): Boolean
    fun captureInputKey(): InputKeyGate.Ticket?
    fun canDeliverInputKey(ticket: InputKeyGate.Ticket): Boolean
    fun releaseInputKey(ticket: InputKeyGate.Ticket)
}
