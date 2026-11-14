/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.File

internal enum class RimeInstallPolicy {
    SETTINGS,
    CUSTOM_UPDATE,
    WANXIANG_UPDATE,
}

internal data class PreparedRimeInstall(
    val user: File,
    val payload: File,
    val policy: RimeInstallPolicy,
)

/**
 * Applies one already-validated resource payload while the caller retains native lifecycle
 * ownership. This function is synchronous and must only be called from the native owner thread.
 */
internal object RimeProfileUpdateInstaller {
    fun install(plan: PreparedRimeInstall, stop: () -> Unit, restart: () -> Unit) {
        // A failed native stop can leave the slot unsafe. Preserve the existing behavior and let
        // the maintenance owner handle that failure instead of attempting to open it again here.
        stop()
        try {
            WanxiangUpdateTransaction.install(
                user = plan.user,
                payload = plan.payload,
                activate = restart,
                deactivate = stop,
                settings = plan.policy == RimeInstallPolicy.SETTINGS,
                generic = plan.policy == RimeInstallPolicy.CUSTOM_UPDATE,
                overrides = plan.policy == RimeInstallPolicy.WANXIANG_UPDATE,
            )
        } catch (error: Throwable) {
            try {
                // WanxiangUpdateTransaction already deactivates and attempts recovery for failures
                // inside its transaction. Retry recovery deliberately: it is idempotent after a
                // successful rollback and completes an interrupted rollback before native restart.
                WanxiangUpdateTransaction.recover(plan.user)
                restart()
            } catch (recovery: Throwable) {
                error.addSuppressed(recovery)
            }
            throw error
        }
    }
}
