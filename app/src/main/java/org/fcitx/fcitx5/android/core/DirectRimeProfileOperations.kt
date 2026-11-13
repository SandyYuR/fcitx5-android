/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.core.engine.RimeEngineRepository
import org.fcitx.fcitx5.android.data.RimeProfileImportTransaction

/** Profile mutations run synchronously on the backend's existing maintenance owner. */
internal class DirectRimeProfileOperations(
    private val config: DirectRimeConfig,
    // Keep first repository access inside the original operation's error boundary.
    private val repository: () -> RimeEngineRepository,
    private val stopForRestart: () -> Unit,
    private val restart: () -> Unit,
) {
    fun manage(
        command: DirectRimeManagementCommands.ProfileManager,
        externalPath: () -> String,
        newName: () -> String,
    ) {
        val name = command.name
        try {
            repository().profileMutationLease().use {
                when (command.action) {
                    DirectRimeManagementCommands.ProfileAction.Create -> config.create(name, externalPath())
                    DirectRimeManagementCommands.ProfileAction.Switch -> {
                        repository().requireBuiltinForProfileSwitch()
                        config.switch(name)
                        restart()
                    }
                    DirectRimeManagementCommands.ProfileAction.Rename -> {
                        repository().requireProfileUnreferenced(name)
                        val active = name == config.current
                        if (active) stopForRestart()
                        try { config.rename(name, newName()) }
                        finally { if (active) restart() }
                    }
                    DirectRimeManagementCommands.ProfileAction.Delete -> {
                        repository().requireProfileUnreferenced(name)
                        config.delete(name)
                    }
                    DirectRimeManagementCommands.ProfileAction.Unknown -> error("无效目录操作")
                }
            }
            config.error = ""
        } catch (e: Exception) { config.error = e.message.orEmpty(); throw e }
    }

    fun installImported(transaction: RimeProfileImportTransaction) {
        try {
            val target = transaction.install()
            repository().profileMutationLease().use {
                repository().requireBuiltinForProfileSwitch()
                config.create(transaction.name, target.absolutePath)
                transaction.markRegistered()
                val previous = config.current
                try { config.switch(transaction.name); restart() }
                catch (e: Exception) {
                    config.switch(previous)
                    restart()
                    throw e
                }
            }
        } catch (e: Exception) {
            transaction.recoverAfterFailure()
            throw e
        }
    }
}
