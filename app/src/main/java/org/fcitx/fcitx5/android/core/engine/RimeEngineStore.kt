/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.content.Context
import android.system.Os
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.FileOutputStream

/** Crash-consistent metadata and payload storage for user-managed native engines. */
internal class RimeEngineRepository(
    private val root: File,
    private val executionRoot: File? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val stateFile = File(root, "state.json")
    private val importedRoot = File(root, "imported")

    @Synchronized
    fun state(): RimeEngineState {
        val parsed = stateFile.takeIf(File::isFile)?.let {
            runCatching { json.decodeFromString<RimeEngineState>(it.readText()) }.getOrNull()
        } ?: RimeEngineState()
        return validate(parsed)
    }

    @Synchronized
    fun importedFile(record: ImportedRimeEngine): File {
        require(record.id.matches(SHA256))
        require(record.storedFileName == "${record.id}.so")
        val file = File(importedRoot, record.storedFileName)
        require(file.canonicalFile.parentFile == importedRoot.canonicalFile)
        return file
    }

    @Synchronized
    private fun executableFile(record: ImportedRimeEngine): File {
        val source = importedFile(record)
        val directory = executionRoot ?: return source
        directory.mkdirs()
        val destination = File(directory, record.storedFileName)
        require(destination.canonicalFile.parentFile == directory.canonicalFile)
        if (!destination.isFile || RimeEngineElf.sha256(destination) != record.staticInfo.sha256) {
            val temporary = File(directory, ".${record.id}.publishing")
            try {
                source.inputStream().use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
                }
                require(RimeEngineElf.sha256(temporary) == record.staticInfo.sha256)
                Os.chmod(temporary.absolutePath, 0b100100100)
                FileUtil.replaceAtomically(temporary, destination)
            } finally {
                temporary.delete()
            }
        }
        return destination
    }

    @Synchronized
    fun installImported(
        staged: File,
        displayName: String,
        staticInfo: RimeEngineStaticInfo,
        runtimeInfo: RimeEngineRuntimeInfo,
        profile: String,
        importedAt: Long = System.currentTimeMillis()
    ): ImportedRimeEngine {
        require(staged.isFile && staticInfo.sha256.matches(SHA256))
        require(displayName.isNotBlank() && displayName.toByteArray().size <= 128)
        require(profile.isNotBlank())
        root.mkdirs()
        importedRoot.mkdirs()
        val record = ImportedRimeEngine(
            id = staticInfo.sha256,
            displayName = displayName.trim(),
            storedFileName = "${staticInfo.sha256}.so",
            staticInfo = staticInfo,
            runtimeInfo = runtimeInfo,
            profile = profile,
            importedAt = importedAt
        )
        val destination = importedFile(record)
        if (!destination.isFile || RimeEngineElf.sha256(destination) != staticInfo.sha256) {
            val temporary = File(importedRoot, ".${record.id}.importing")
            require(!temporary.exists() || temporary.isFile)
            try {
                staged.inputStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                require(RimeEngineElf.sha256(temporary) == staticInfo.sha256) { "引擎文件在复制时发生变化" }
                // Dynamic code must not remain writable after publication.
                Os.chmod(temporary.absolutePath, 0b100100100)
                FileUtil.replaceAtomically(temporary, destination)
            } finally {
                temporary.delete()
            }
        }
        val current = state()
        val next = current.copy(imported = (current.imported.filterNot { it.id == record.id } + record)
            .sortedBy(ImportedRimeEngine::importedAt))
        write(next)
        return record
    }

    @Synchronized
    fun authorizePlugin(record: PluginRimeEngineAuthorization) {
        require(record.packageName.isNotBlank() && record.payloadSha256.matches(SHA256))
        val current = state()
        write(current.copy(plugins = (current.plugins.filterNot { it.packageName == record.packageName } + record)
            .sortedBy(PluginRimeEngineAuthorization::authorizedAt)))
    }

    @Synchronized
    fun updateImportedProfile(id: String, profile: String) {
        require(profile.isNotBlank())
        val current = state()
        require(current.imported.any { it.id == id }) { "引擎不存在" }
        write(current.copy(imported = current.imported.map { if (it.id == id) it.copy(profile = profile) else it }))
    }

    @Synchronized
    fun updatePluginProfile(packageName: String, profile: String) {
        require(profile.isNotBlank())
        val current = state()
        require(current.plugins.any { it.packageName == packageName }) { "插件授权不存在" }
        write(current.copy(plugins = current.plugins.map {
            if (it.packageName == packageName) it.copy(profile = profile) else it
        }))
    }

    @Synchronized
    fun select(selection: RimeEngineSelection, currentProfile: String? = null) {
        val current = state()
        require(exists(current, selection)) { "引擎不存在或尚未授权" }
        if (current.active == selection && current.activation == null) return
        write(current.copy(
            active = selection,
            builtinProfile = if (current.active == RimeEngineSelection.Builtin) {
                currentProfile?.takeIf(String::isNotBlank) ?: current.builtinProfile
            } else current.builtinProfile,
            activation = RimeEngineActivation(selection, current.active),
            restoreBuiltinProfile = false,
            lastFallbackReason = null
        ))
    }

    @Synchronized
    fun reconfigureActive(selection: RimeEngineSelection) {
        val current = state()
        require(current.active == selection && selection.kind != RimeEngineKind.BUILTIN) { "引擎未在使用" }
        write(current.copy(activation = RimeEngineActivation(
            selected = selection,
            previous = RimeEngineSelection.Builtin
        ), lastFallbackReason = null))
    }

    /**
     * Mark an activation attempt before any external code is loaded. If a prior attempt did not
     * reach [activationSucceeded], assume a native crash and roll back before trying again.
     */
    @Synchronized
    fun beginActivation(): Pair<RimeEngineState, Boolean> {
        val current = state()
        val activation = current.activation ?: return current to false
        if (activation.selected != current.active || !exists(current, current.active)) {
            val fallback = activation.previous.takeIf { exists(current, it) } ?: RimeEngineSelection.Builtin
            val rolledBack = current.copy(active = fallback, activation = null,
                restoreBuiltinProfile = fallback == RimeEngineSelection.Builtin,
                lastFallbackReason = "待启用引擎不可用，已恢复到先前引擎")
            write(rolledBack)
            return rolledBack to false
        }
        if (activation.attempted) {
            val fallback = activation.previous.takeIf { exists(current, it) } ?: RimeEngineSelection.Builtin
            val rolledBack = current.copy(active = fallback, activation = null,
                restoreBuiltinProfile = fallback == RimeEngineSelection.Builtin,
                lastFallbackReason = "新引擎上次启动未完成，已自动回退")
            write(rolledBack)
            return rolledBack to false
        }
        val attempted = current.copy(activation = activation.copy(attempted = true))
        write(attempted)
        return attempted to true
    }

    @Synchronized
    fun activationSucceeded(selection: RimeEngineSelection) {
        val current = state()
        if (current.active != selection) return
        val completedActivation = current.activation?.selected == selection
        if (!completedActivation && !current.restoreBuiltinProfile) return
        write(current.copy(
            activation = if (completedActivation) null else current.activation,
            restoreBuiltinProfile = false,
            lastFallbackReason = if (completedActivation) null else current.lastFallbackReason
        ))
    }

    @Synchronized
    fun activationFailed(selection: RimeEngineSelection, reason: String) {
        val current = state()
        if (current.active != selection) return
        val previous = current.activation?.previous?.takeIf { exists(current, it) }
            ?: RimeEngineSelection.Builtin
        write(current.copy(active = previous, activation = null,
            restoreBuiltinProfile = previous == RimeEngineSelection.Builtin,
            lastFallbackReason = reason.take(512)))
    }

    @Synchronized
    fun deleteImported(id: String) {
        val current = state()
        val selection = RimeEngineSelection(RimeEngineKind.IMPORTED, id)
        require(current.active != selection) { "请先切换到其他引擎" }
        val record = current.imported.firstOrNull { it.id == id } ?: return
        executionRoot?.let {
            val executable = File(it, record.storedFileName)
            check(!executable.exists() || executable.delete()) { "无法删除引擎执行副本" }
        }
        val stored = importedFile(record)
        check(!stored.exists() || stored.delete()) { "无法删除受管引擎文件" }
        write(current.copy(imported = current.imported.filterNot { it.id == id }))
    }

    @Synchronized
    fun revokePlugin(packageName: String) {
        val current = state()
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, packageName)
        require(current.active != selection) { "请先切换到其他引擎" }
        write(current.copy(plugins = current.plugins.filterNot { it.packageName == packageName }))
    }

    @Synchronized
    fun resolveActive(pluginCandidates: List<RimePluginEngineCandidate>): ResolvedRimeEngine {
        val current = state()
        return when (current.active.kind) {
            RimeEngineKind.BUILTIN -> ResolvedRimeEngine.Builtin
            RimeEngineKind.IMPORTED -> current.imported.firstOrNull { it.id == current.active.id }
                ?.takeIf { importedFile(it).isFile && RimeEngineElf.sha256(importedFile(it)) == it.staticInfo.sha256 }
                ?.let { ResolvedRimeEngine.External(current.active, it.displayName, it.profile,
                    it.staticInfo.entrySymbol, it.staticInfo.sha256, executableFile(it).absolutePath) }
                ?: ResolvedRimeEngine.Builtin
            RimeEngineKind.PLUGIN -> {
                val authorization = current.plugins.firstOrNull { it.packageName == current.active.id }
                val candidate = pluginCandidates.firstOrNull { it.packageName == current.active.id }
                if (authorization != null && candidate != null &&
                    authorization.certificateSha256 == candidate.certificateSha256 &&
                    authorization.payloadSha256 == candidate.staticInfo.sha256 &&
                    authorization.soname == candidate.soname) {
                    ResolvedRimeEngine.External(current.active, authorization.displayName,
                        authorization.profile, candidate.staticInfo.entrySymbol,
                        candidate.staticInfo.sha256, candidate.libraryPath)
                } else ResolvedRimeEngine.Builtin
            }
        }
    }

    private fun validate(value: RimeEngineState): RimeEngineState {
        if (value.schemaVersion != 1) return RimeEngineState(lastFallbackReason = "引擎列表版本不受支持，已使用内置引擎")
        val imported = value.imported.filter {
            it.id.matches(SHA256) && it.id == it.staticInfo.sha256 && it.storedFileName == "${it.id}.so" &&
                it.displayName.isNotBlank() && it.profile.isNotBlank()
        }.distinctBy(ImportedRimeEngine::id)
        val plugins = value.plugins.filter {
            it.packageName.isNotBlank() && it.payloadSha256.matches(SHA256) && it.profile.isNotBlank()
        }.distinctBy(PluginRimeEngineAuthorization::packageName)
        val sanitized = value.copy(imported = imported, plugins = plugins)
        return if (exists(sanitized, sanitized.active)) sanitized else sanitized.copy(
            active = RimeEngineSelection.Builtin,
            activation = null,
            restoreBuiltinProfile = true,
            lastFallbackReason = "已选择的引擎不存在，已使用内置引擎"
        )
    }

    private fun exists(state: RimeEngineState, selection: RimeEngineSelection) = when (selection.kind) {
        RimeEngineKind.BUILTIN -> selection == RimeEngineSelection.Builtin
        RimeEngineKind.IMPORTED -> state.imported.any { it.id == selection.id }
        RimeEngineKind.PLUGIN -> state.plugins.any { it.packageName == selection.id }
    }

    private fun write(value: RimeEngineState) {
        root.mkdirs()
        FileUtil.writeAtomically(stateFile, json.encodeToString(validate(value)) + "\n")
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal object RimeEngineStore {
    @Volatile private var instance: RimeEngineRepository? = null

    fun repository(context: Context): RimeEngineRepository = instance ?: synchronized(this) {
        instance ?: RimeEngineRepository(
            File(context.noBackupFilesDir, "rime-engines"),
            File(context.codeCacheDir, "rime-engines")
        ).also { instance = it }
    }
}
