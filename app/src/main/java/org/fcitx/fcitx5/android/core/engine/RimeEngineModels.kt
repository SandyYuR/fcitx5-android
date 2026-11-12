/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import kotlinx.serialization.Serializable

@Serializable
enum class RimeEngineKind { BUILTIN, IMPORTED, PLUGIN }

@Serializable
data class RimeEngineSelection(
    val kind: RimeEngineKind = RimeEngineKind.BUILTIN,
    val id: String = BUILTIN_ID
) {
    init {
        require(id.isNotBlank())
        require(kind != RimeEngineKind.BUILTIN || id == BUILTIN_ID)
    }

    companion object {
        const val BUILTIN_ID = "builtin"
        val Builtin = RimeEngineSelection()
    }
}

/** Information obtained without executing the selected file. */
@Serializable
data class RimeEngineStaticInfo(
    val sha256: String,
    val size: Long,
    val abi: String,
    val bits: Int,
    val soname: String? = null,
    val buildId: String? = null,
    val entrySymbol: String,
    val neededLibraries: List<String>,
    val loadAlignment: Long,
    val sourceName: String
)

/** Information returned by the disposable native probe after explicit authorization. */
@Serializable
data class RimeEngineRuntimeInfo(
    val librimeVersion: String,
    val apiDataSize: Int,
    val capabilities: List<String>
)

@Serializable
data class ImportedRimeEngine(
    val id: String,
    val displayName: String,
    val storedFileName: String,
    val staticInfo: RimeEngineStaticInfo,
    val runtimeInfo: RimeEngineRuntimeInfo,
    val profile: String,
    val importedAt: Long
)

@Serializable
data class PluginRimeEngineAuthorization(
    val packageName: String,
    val displayName: String,
    val providerId: String,
    val certificateSha256: String,
    val payloadSha256: String,
    val soname: String,
    val staticInfo: RimeEngineStaticInfo,
    val runtimeInfo: RimeEngineRuntimeInfo,
    val profile: String,
    val authorizedAt: Long
)

@Serializable
data class RimeEngineActivation(
    val selected: RimeEngineSelection,
    val previous: RimeEngineSelection,
    val attempted: Boolean = false,
    /** Explicit switches/profile changes require a full deployment; ordinary startup guards do not. */
    val forceFullCheck: Boolean = true,
    /** Explicit user choices clear an old fallback notice; automatic recovery must preserve it. */
    val clearFallbackReason: Boolean = true
)

@Serializable
data class RimeEngineState(
    val schemaVersion: Int = 1,
    val active: RimeEngineSelection = RimeEngineSelection.Builtin,
    /** Last known-good engine to restore if [active] cannot complete a later startup. */
    val fallback: RimeEngineSelection = RimeEngineSelection.Builtin,
    val builtinProfile: String? = null,
    val imported: List<ImportedRimeEngine> = emptyList(),
    val plugins: List<PluginRimeEngineAuthorization> = emptyList(),
    val activation: RimeEngineActivation? = null,
    val restoreBuiltinProfile: Boolean = false,
    val lastFallbackReason: String? = null
)

data class RimePluginEngineCandidate(
    val packageName: String,
    val displayName: String,
    val providerId: String,
    val apiVersion: Int,
    val rimeApiAbi: Int,
    val soname: String,
    val declaredLibrimeVersion: String?,
    val channel: String?,
    val certificateSha256: String,
    val libraryPath: String,
    val staticInfo: RimeEngineStaticInfo
)

sealed interface ResolvedRimeEngine {
    val selection: RimeEngineSelection
    val displayName: String
    val profile: String?
    val entrySymbol: String?

    data object Builtin : ResolvedRimeEngine {
        override val selection = RimeEngineSelection.Builtin
        override val displayName = "内置引擎"
        override val profile: String? = null
        override val entrySymbol: String? = null
    }

    data class External(
        override val selection: RimeEngineSelection,
        override val displayName: String,
        override val profile: String,
        override val entrySymbol: String,
        val payloadSha256: String,
        val filePath: String
    ) : ResolvedRimeEngine
}
