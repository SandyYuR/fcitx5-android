/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.status

import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.core.FcitxAPI

/** Maintenance errors remain failures for backup callers, but are feedback for a menu click. */
internal suspend fun FcitxAPI.activateStatusAreaAction(id: Int, reportFailure: suspend (Exception) -> Unit) {
    try {
        activateAction(id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        reportFailure(failure)
    }
}
