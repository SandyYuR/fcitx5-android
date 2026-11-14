/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Field decoding only; the backend keeps the guards, maintenance and execution order. */
internal object DirectRimeManagementCommands {
    enum class ProfileAction { Create, Switch, Rename, Delete, Unknown }

    data class ProfileManager(val name: String, val action: ProfileAction)
    data class ImportProfile(val archivePath: String, val destinationPath: String)
    data class SchemaSelection(val ids: List<String>)

    fun profileManager(config: RawConfig): ProfileManager {
        val name = config.findByName("Name")?.value.orEmpty()
        val action = when (config.findByName("Action")?.value.orEmpty()) {
            "create" -> ProfileAction.Create
            "switch" -> ProfileAction.Switch
            "rename" -> ProfileAction.Rename
            "delete" -> ProfileAction.Delete
            else -> ProfileAction.Unknown
        }
        // Unknown actions fail in the backend's existing mutation lease/error handler.
        return ProfileManager(name, action)
    }

    // These fields are read only when their branch reaches the existing config mutation call.
    fun externalPath(config: RawConfig): String = config.findByName("ExternalPath")?.value.orEmpty()
    fun newName(config: RawConfig): String = config.findByName("NewName")?.value.orEmpty()

    fun importProfile(config: RawConfig): ImportProfile {
        val archivePath = config.findByName("Archive")?.value ?: error("请选择方案 ZIP")
        val destinationPath = config.findByName("Destination")?.value.orEmpty()
        return ImportProfile(archivePath, destinationPath)
    }

    fun schemaSelection(config: RawConfig): SchemaSelection {
        val ids = config.findByName("Items")?.value.orEmpty().lines().mapNotNull {
            val row = it.split('\t', limit = 3)
            if (row.size == 3 && row[0] == "1") row[1] else null
        }
        return SchemaSelection(ids)
    }
}
