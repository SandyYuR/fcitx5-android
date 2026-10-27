/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Expose the client API, retaining explicit forwarding for the presentation acknowledgement. */
internal class InputEngineApi(private val engine: InputEngine) : FcitxAPI by engine {
    override suspend fun statusHintPresented(hintId: Long, frameUptimeMillis: Long) {
        engine.statusHintPresented(hintId, frameUptimeMillis)
    }
}
