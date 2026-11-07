/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.sync.Mutex

/** Both updaters share submission ordering; the engine owner serializes deployment itself. */
internal object RimeUpdateCoordinator {
    const val CUSTOM = "rime-custom-update"
    const val WANXIANG = "wanxiang-update"
    val commands = Mutex()
    fun requireOtherIdle(context: Context, own: String) {
        val manager = WorkManager.getInstance(context)
        val other = if (own == CUSTOM) WANXIANG else CUSTOM
        require(manager.getWorkInfosForUniqueWork(other).get().none { !it.state.isFinished }) {
            "已有方案更新正在进行，请等待完成或取消后重试"
        }
    }
}
