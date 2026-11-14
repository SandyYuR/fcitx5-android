/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes maintenance and its binding recovery on the existing native owner. */
internal class DirectRimeMaintenanceCoordinator<B>(private val owner: Owner<B>) {
    /** Backend retains the live binding, generation, running flag, gate and event queue. */
    interface Owner<B> {
        val started: Boolean
        val inputSession: Long
        fun enterMaintenance(): Pair<Long, B?>
        fun leaveMaintenance()
        suspend fun onOwner(operation: () -> Unit)
        fun clearBinding(generation: Long)
        fun publishMaintenanceStatus(generation: Long)
        fun showUnavailable(message: String)
        fun refreshSchemas()
        fun completeActivation()
        fun failActivation(error: Exception): Boolean
        fun hasUsableBinding(generation: Long): Boolean
        fun currentBinding(generation: Long): B?
        fun prepareBinding(generation: Long, previous: B)
        fun beginBinding(generation: Long)
    }

    private val mutex = Mutex()

    suspend fun run(refreshAfterOperation: Boolean = true, operation: () -> Unit) = mutex.withLock {
        // Keep the original barrier outside try: entering can fail before recovery is active.
        val (token, previousBinding) = owner.enterMaintenance()
        try {
            owner.onOwner {
                owner.clearBinding(token)
                owner.publishMaintenanceStatus(token)
                owner.showUnavailable("正在部署，请稍候…")
                operation()
                if (refreshAfterOperation) owner.refreshSchemas()
                owner.completeActivation()
                restoreBinding(token, previousBinding)
            }
        } catch (error: Exception) {
            try {
                withContext(NonCancellable) {
                    owner.onOwner {
                        val failedExternal = owner.failActivation(error)
                        if (token == owner.inputSession) {
                            if (!failedExternal) restoreBinding(token, previousBinding)
                            if (error !is CancellationException &&
                                error !is DirectRimeEngineOperations.EngineSwitchRecoveredException) {
                                owner.showUnavailable(error.message ?: "操作失败，请检查方案文件")
                            }
                        }
                    }
                }
            } catch (recovery: Exception) {
                if (recovery !== error) error.addSuppressed(recovery)
            }
            throw error
        } finally {
            owner.leaveMaintenance()
        }
    }

    private fun restoreBinding(token: Long, previous: B?) {
        if (!owner.started || token != owner.inputSession || owner.hasUsableBinding(token)) return
        val current = owner.currentBinding(token)
        if (current == null && previous != null) owner.prepareBinding(token, previous)
        // Re-read after preparing: editor metadata may have changed while maintenance ran.
        if (owner.currentBinding(token) != null) owner.beginBinding(token)
        else owner.clearBinding(token)
    }
}
