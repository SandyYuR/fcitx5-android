/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.util.UUID

object UserDataBackupManager {
    /** Stop native writers while copying; cancellation must still resume the engine. */
    suspend fun prepare(timestamp: Long = System.currentTimeMillis()): File = withContext(NonCancellable) {
        val name = "UserDataBackup-${UUID.randomUUID()}"
        val connection = FcitxDaemon.connect(name)
        var archive: File? = null
        try {
            val state = connection.runOnReady {
                save()
                RimeDataDirectoryState.fromConfig(getAddonSubConfig("rime", "profile-manager"),
                    appContext.getExternalFilesDir(null)!!.resolve("data"))
            }
            val ready = withContext(Dispatchers.IO) {
                UserDataBackupArchive.prepare(appContext.cacheDir,
                    pause = { FcitxDaemon.beginUserDataBackup() },
                    resume = { FcitxDaemon.finishUserDataBackup() }) {
                    UserDataManager.export(it, state, timestamp).getOrThrow()
                }
            }
            archive = ready
            connection.runOnReady { }
            ready
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { archive?.delete() }
            throw e
        } finally { FcitxDaemon.disconnect(name) }
    }
}
