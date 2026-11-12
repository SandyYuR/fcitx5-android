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
    private var profileMutationOwner: Thread? = null

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
        return publishExecutable(source, File(directory, record.storedFileName), record.staticInfo)
    }

    /**
     * Copy a provider payload into an app-owned immutable snapshot. Package updates can replace
     * nativeLibraryDir between discovery and open; the snapshot hash is the authorization identity.
     */
    @Synchronized
    private fun pluginExecutableFile(
        candidate: RimePluginEngineCandidate,
        authorization: PluginRimeEngineAuthorization
    ): File {
        val directory = requireNotNull(executionRoot) { "插件引擎需要受管执行目录" }
        return publishExecutable(
            File(candidate.libraryPath),
            File(directory, "plugin-${authorization.payloadSha256}.so"),
            authorization.staticInfo
        )
    }

    private fun publishExecutable(
        source: File,
        destination: File,
        expected: RimeEngineStaticInfo
    ): File {
        require(source.isFile && source.length() in 1..RimeEngineElf.MAX_ENGINE_BYTES) {
            "外部引擎文件不存在或大小无效"
        }
        val directory = destination.parentFile!!.canonicalFile
        check(directory.isDirectory || directory.mkdirs()) { "无法创建引擎执行目录" }
        require(destination.canonicalFile.parentFile == directory)
        val validExisting = destination.isFile &&
            destination.length() in 1..RimeEngineElf.MAX_ENGINE_BYTES &&
            RimeEngineElf.sha256(destination) == expected.sha256
        if (validExisting) return destination

        val temporary = File(directory, ".${destination.name}.publishing")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= RimeEngineElf.MAX_ENGINE_BYTES) { "外部引擎文件超过大小限制" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val inspected = RimeEngineElf.inspect(temporary, expected.sourceName)
            require(inspected.sha256 == expected.sha256) { "外部引擎文件在发布时发生变化" }
            Os.chmod(temporary.absolutePath, 0b100100100)
            FileUtil.replaceAtomically(temporary, destination)
        } finally {
            temporary.delete()
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
        requireMutationAllowed()
        require(staged.isFile && staged.length() in 1..RimeEngineElf.MAX_ENGINE_BYTES &&
            staticInfo.sha256.matches(SHA256))
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
        val beforeInstall = state()
        require(beforeInstall.activation == null) { "引擎正在切换，请稍后重试" }
        val selection = RimeEngineSelection(RimeEngineKind.IMPORTED, record.id)
        if (beforeInstall.imported.any { it.id == record.id }) {
            require(beforeInstall.active != selection && beforeInstall.fallback != selection) {
                "此引擎正在使用或保留为崩溃回退；请先切换到内置引擎再重新导入"
            }
        }
        val destination = importedFile(record)
        if (!destination.isFile || destination.length() !in 1..RimeEngineElf.MAX_ENGINE_BYTES ||
            RimeEngineElf.sha256(destination) != staticInfo.sha256) {
            val temporary = File(importedRoot, ".${record.id}.importing")
            require(!temporary.exists() || temporary.isFile)
            try {
                staged.inputStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= RimeEngineElf.MAX_ENGINE_BYTES) { "引擎文件超过大小限制" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                val inspected = RimeEngineElf.inspect(temporary, staticInfo.sourceName)
                require(inspected.sha256 == staticInfo.sha256) { "引擎文件在复制时发生变化" }
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
        requireMutationAllowed()
        require(record.packageName.isNotBlank() && record.packageName.length <= 255 &&
            record.displayName.isNotBlank() && record.displayName.toByteArray().size <= 128 &&
            record.providerId.isNotBlank() && record.providerId.toByteArray().size <= 256 &&
            record.certificateSha256.matches(CERTIFICATE_DIGESTS) &&
            record.payloadSha256.matches(SHA256) &&
            record.payloadSha256 == record.staticInfo.sha256 &&
            record.soname.matches(SONAME) && record.profile.isNotBlank()) {
            "插件引擎授权记录无效"
        }
        val current = state()
        require(current.activation == null) { "引擎正在切换，请稍后重试" }
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, record.packageName)
        if (current.plugins.any { it.packageName == record.packageName }) {
            require(current.active != selection && current.fallback != selection) {
                "此插件引擎正在使用或保留为崩溃回退；请先切换到内置引擎再重新授权"
            }
        }
        val previous = current.plugins.firstOrNull { it.packageName == record.packageName }
        val next = current.copy(plugins = (current.plugins.filterNot { it.packageName == record.packageName } + record)
            .sortedBy(PluginRimeEngineAuthorization::authorizedAt))
        write(next)
        if (previous != null && previous.payloadSha256 != record.payloadSha256 &&
            next.plugins.none { it.payloadSha256 == previous.payloadSha256 }) {
            executionRoot?.let { File(it, "plugin-${previous.payloadSha256}.so").delete() }
        }
    }

    @Synchronized
    fun updateProfile(selection: RimeEngineSelection, profile: String): Boolean {
        requireMutationAllowed()
        require(profile.isNotBlank())
        val current = state()
        require(current.activation == null) { "引擎正在切换，请稍后重试" }
        require(selection.kind != RimeEngineKind.BUILTIN && exists(current, selection)) {
            "引擎不存在或尚未授权"
        }
        val active = current.active == selection
        val activation = if (active) RimeEngineActivation(
            selected = selection,
            previous = fallbackFor(current, selection),
            forceFullCheck = true
        ) else current.activation
        val next = when (selection.kind) {
            RimeEngineKind.IMPORTED -> current.copy(
                imported = current.imported.map {
                    if (it.id == selection.id) it.copy(profile = profile) else it
                },
                activation = activation,
                fallback = if (!active && current.fallback == selection)
                    RimeEngineSelection.Builtin else current.fallback,
                lastFallbackReason = if (active) null else current.lastFallbackReason
            )
            RimeEngineKind.PLUGIN -> current.copy(
                plugins = current.plugins.map {
                    if (it.packageName == selection.id) it.copy(profile = profile) else it
                },
                activation = activation,
                fallback = if (!active && current.fallback == selection)
                    RimeEngineSelection.Builtin else current.fallback,
                lastFallbackReason = if (active) null else current.lastFallbackReason
            )
            RimeEngineKind.BUILTIN -> error("内置引擎不保存独立绑定")
        }
        write(next)
        return active
    }

    @Synchronized
    fun select(selection: RimeEngineSelection, currentProfile: String? = null) {
        requireMutationAllowed()
        val current = state()
        require(current.activation == null) { "引擎正在切换，请稍后重试" }
        require(exists(current, selection)) { "引擎不存在或尚未授权" }
        if (current.active == selection) return
        write(current.copy(
            active = selection,
            builtinProfile = if (current.active == RimeEngineSelection.Builtin) {
                currentProfile?.takeIf(String::isNotBlank) ?: current.builtinProfile
            } else current.builtinProfile,
            activation = RimeEngineActivation(selection, current.active, forceFullCheck = true),
            restoreBuiltinProfile = false,
            lastFallbackReason = null
        ))
    }

    @Synchronized
    fun requireProfileMutationIdle() {
        require(profileMutationOwner == null && state().activation == null) {
            "引擎或工作目录正在切换，请稍后重试"
        }
    }

    /**
     * Reserve profile mutations without holding this repository's monitor across filesystem or
     * native deployment work. Competing UI mutations fail immediately instead of blocking main.
     */
    @Synchronized
    fun profileMutationLease(): AutoCloseable {
        require(profileMutationOwner == null && state().activation == null) {
            "引擎或工作目录正在切换，请稍后重试"
        }
        val owner = Thread.currentThread()
        profileMutationOwner = owner
        return AutoCloseable { releaseProfileMutation(owner) }
    }

    @Synchronized
    fun requireBuiltinForProfileSwitch() {
        val current = state()
        require(current.active == RimeEngineSelection.Builtin && current.activation == null) {
            "外部引擎正在使用；请在“Rime 引擎变体”页面更改它绑定的工作目录"
        }
    }

    @Synchronized
    fun requireProfileUnreferenced(profile: String) {
        val current = state()
        require(current.activation == null) { "引擎正在切换，请稍后再管理工作目录" }
        val referenced = current.imported.any { it.profile == profile } ||
            current.plugins.any { it.profile == profile } ||
            (current.active.kind != RimeEngineKind.BUILTIN && current.builtinProfile == profile)
        require(!referenced) { "此工作目录仍被内置回退或外部引擎引用，请先更改引擎绑定" }
    }

    @Synchronized
    fun activeExternalProfile(): String? {
        val current = state()
        return when (current.active.kind) {
            RimeEngineKind.BUILTIN -> null
            RimeEngineKind.IMPORTED -> current.imported.firstOrNull { it.id == current.active.id }?.profile
            RimeEngineKind.PLUGIN -> current.plugins.firstOrNull { it.packageName == current.active.id }?.profile
        }
    }

    /**
     * Mark an activation attempt before any external code is loaded. If a prior attempt did not
     * reach [activationSucceeded], assume a native crash and roll back before trying again.
     */
    @Synchronized
    fun beginActivation(): Pair<RimeEngineState, Boolean> {
        requireMutationAllowed()
        val current = state()
        val activation = current.activation
        if (activation == null) {
            if (current.active.kind == RimeEngineKind.BUILTIN) return current to false
            // Every external startup is guarded, not only the first switch. A later DSO crash
            // must not trap the input method in an endless process-restart loop.
            val guarded = current.copy(activation = RimeEngineActivation(
                selected = current.active,
                previous = fallbackFor(current, current.active),
                attempted = true,
                forceFullCheck = false,
                clearFallbackReason = false
            ))
            write(guarded)
            return guarded to false
        }
        if (activation.selected != current.active || !exists(current, current.active)) {
            val fallback = activation.previous.takeIf { exists(current, it) } ?: RimeEngineSelection.Builtin
            val fallbackGuard = fallback.takeIf { it.kind != RimeEngineKind.BUILTIN }?.let {
                RimeEngineActivation(it, RimeEngineSelection.Builtin, attempted = true,
                    forceFullCheck = true, clearFallbackReason = false)
            }
            val rolledBack = current.copy(active = fallback, activation = fallbackGuard,
                fallback = if (fallback.kind == RimeEngineKind.BUILTIN) fallback else RimeEngineSelection.Builtin,
                restoreBuiltinProfile = fallback == RimeEngineSelection.Builtin,
                lastFallbackReason = "待启用引擎不可用，已恢复到先前引擎")
            write(rolledBack)
            return rolledBack to (fallbackGuard?.forceFullCheck == true)
        }
        if (activation.attempted) {
            val fallback = activation.previous.takeIf { exists(current, it) } ?: RimeEngineSelection.Builtin
            val fallbackGuard = fallback.takeIf { it.kind != RimeEngineKind.BUILTIN }?.let {
                RimeEngineActivation(it, RimeEngineSelection.Builtin, attempted = true,
                    forceFullCheck = true, clearFallbackReason = false)
            }
            val rolledBack = current.copy(active = fallback, activation = fallbackGuard,
                fallback = if (fallback.kind == RimeEngineKind.BUILTIN) fallback else RimeEngineSelection.Builtin,
                restoreBuiltinProfile = fallback == RimeEngineSelection.Builtin,
                lastFallbackReason = "外部引擎上次启动未完成，已自动回退")
            write(rolledBack)
            return rolledBack to (fallbackGuard?.forceFullCheck == true)
        }
        val attempted = current.copy(activation = activation.copy(attempted = true))
        write(attempted)
        return attempted to activation.forceFullCheck
    }

    @Synchronized
    fun activationSucceeded(selection: RimeEngineSelection) {
        requireMutationAllowed()
        val current = state()
        if (current.active != selection) return
        val activation = current.activation
        val completedActivation = activation?.selected == selection && activation.attempted
        if (!completedActivation && !current.restoreBuiltinProfile) return
        val fallback = when {
            selection.kind == RimeEngineKind.BUILTIN -> RimeEngineSelection.Builtin
            activation != null && exists(current, activation.previous) && activation.previous != selection -> activation.previous
            else -> fallbackFor(current, selection)
        }
        write(current.copy(
            activation = if (completedActivation) null else current.activation,
            fallback = fallback,
            restoreBuiltinProfile = false,
            lastFallbackReason = if (activation?.clearFallbackReason == true) null else current.lastFallbackReason
        ))
    }

    @Synchronized
    fun activationFailed(selection: RimeEngineSelection, reason: String) {
        requireMutationAllowed()
        val current = state()
        if (current.active != selection) return
        val previous = current.activation?.previous?.takeIf { exists(current, it) }
            ?.takeIf { it != selection } ?: RimeEngineSelection.Builtin
        val fallbackGuard = previous.takeIf { it.kind != RimeEngineKind.BUILTIN }?.let {
            RimeEngineActivation(it, RimeEngineSelection.Builtin,
                forceFullCheck = true, clearFallbackReason = false)
        }
        write(current.copy(active = previous, activation = fallbackGuard,
            fallback = if (previous.kind == RimeEngineKind.BUILTIN) previous else RimeEngineSelection.Builtin,
            restoreBuiltinProfile = previous == RimeEngineSelection.Builtin,
            lastFallbackReason = reason.take(512)))
    }

    @Synchronized
    fun deleteImported(id: String) {
        requireMutationAllowed()
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
        write(current.copy(
            imported = current.imported.filterNot { it.id == id },
            fallback = if (current.fallback == selection) RimeEngineSelection.Builtin else current.fallback
        ))
    }

    @Synchronized
    fun revokePlugin(packageName: String) {
        requireMutationAllowed()
        val current = state()
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, packageName)
        require(current.active != selection) { "请先切换到其他引擎" }
        executionRoot?.let { directory ->
            current.plugins.firstOrNull { it.packageName == packageName }?.let { record ->
                File(directory, "plugin-${record.payloadSha256}.so").delete()
            }
        }
        write(current.copy(
            plugins = current.plugins.filterNot { it.packageName == packageName },
            fallback = if (current.fallback == selection) RimeEngineSelection.Builtin else current.fallback
        ))
    }

    @Synchronized
    fun resolveActive(pluginCandidates: List<RimePluginEngineCandidate>): ResolvedRimeEngine {
        val current = state()
        return when (current.active.kind) {
            RimeEngineKind.BUILTIN -> ResolvedRimeEngine.Builtin
            RimeEngineKind.IMPORTED -> current.imported.firstOrNull { it.id == current.active.id }
                ?.takeIf { importedFile(it).let { file -> file.isFile &&
                    file.length() in 1..RimeEngineElf.MAX_ENGINE_BYTES &&
                    RimeEngineElf.sha256(file) == it.staticInfo.sha256 } }
                ?.let { ResolvedRimeEngine.External(current.active, it.displayName, it.profile,
                    it.staticInfo.entrySymbol, it.staticInfo.sha256, executableFile(it).absolutePath) }
                ?: ResolvedRimeEngine.Builtin
            RimeEngineKind.PLUGIN -> {
                val authorization = current.plugins.firstOrNull { it.packageName == current.active.id }
                val candidate = pluginCandidates.firstOrNull { it.packageName == current.active.id }
                if (authorization != null && candidate != null &&
                    authorization.providerId == candidate.providerId &&
                    authorization.certificateSha256 == candidate.certificateSha256 &&
                    authorization.payloadSha256 == candidate.staticInfo.sha256 &&
                    authorization.soname == candidate.soname &&
                    authorization.staticInfo.entrySymbol == candidate.staticInfo.entrySymbol) {
                    val executable = pluginExecutableFile(candidate, authorization)
                    ResolvedRimeEngine.External(current.active, authorization.displayName,
                        authorization.profile, candidate.staticInfo.entrySymbol,
                        candidate.staticInfo.sha256, executable.absolutePath)
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
            it.packageName.isNotBlank() && it.packageName.length <= 255 &&
                it.displayName.isNotBlank() && it.displayName.toByteArray().size <= 128 &&
                it.providerId.isNotBlank() && it.providerId.toByteArray().size <= 256 &&
                it.certificateSha256.matches(CERTIFICATE_DIGESTS) &&
                it.payloadSha256.matches(SHA256) && it.payloadSha256 == it.staticInfo.sha256 &&
                it.soname.matches(SONAME) && it.profile.isNotBlank()
        }.distinctBy(PluginRimeEngineAuthorization::packageName)
        var sanitized = value.copy(imported = imported, plugins = plugins)
        if (!exists(sanitized, sanitized.active)) return sanitized.copy(
            active = RimeEngineSelection.Builtin,
            fallback = RimeEngineSelection.Builtin,
            activation = null,
            restoreBuiltinProfile = true,
            lastFallbackReason = "已选择的引擎不存在，已使用内置引擎"
        )
        val fallback = sanitized.fallback.takeIf {
            it != sanitized.active && exists(sanitized, it)
        } ?: RimeEngineSelection.Builtin
        if (sanitized.active.kind == RimeEngineKind.BUILTIN || sanitized.fallback != fallback) {
            sanitized = sanitized.copy(fallback = if (sanitized.active.kind == RimeEngineKind.BUILTIN)
                RimeEngineSelection.Builtin else fallback)
        }
        return sanitized
    }

    private fun exists(state: RimeEngineState, selection: RimeEngineSelection) = when (selection.kind) {
        RimeEngineKind.BUILTIN -> selection == RimeEngineSelection.Builtin
        RimeEngineKind.IMPORTED -> state.imported.any { it.id == selection.id }
        RimeEngineKind.PLUGIN -> state.plugins.any { it.packageName == selection.id }
    }

    private fun fallbackFor(state: RimeEngineState, excluding: RimeEngineSelection): RimeEngineSelection =
        state.fallback.takeIf { it != excluding && exists(state, it) } ?: RimeEngineSelection.Builtin

    private fun requireMutationAllowed() {
        require(profileMutationOwner == null || profileMutationOwner === Thread.currentThread()) {
            "Rime 工作目录正在变更，请稍后重试"
        }
    }

    @Synchronized
    private fun releaseProfileMutation(owner: Thread) {
        check(profileMutationOwner === owner) { "Rime 工作目录租约所有者不匹配" }
        profileMutationOwner = null
    }

    private fun write(value: RimeEngineState) {
        root.mkdirs()
        FileUtil.writeAtomically(stateFile, json.encodeToString(validate(value)) + "\n")
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        val CERTIFICATE_DIGESTS = Regex("[0-9a-f]{64}(?::[0-9a-f]{64})*")
        val SONAME = Regex("[A-Za-z0-9._+-]{1,128}\\.so")
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
