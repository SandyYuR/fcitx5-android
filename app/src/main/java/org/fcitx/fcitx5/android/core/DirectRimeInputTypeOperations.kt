/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.Native
import org.fcitx.fcitx5.android.core.engine.RimeInputType
import org.fcitx.fcitx5.android.core.engine.RimeInputTypes
import org.fcitx.fcitx5.android.core.engine.RimeInputTypesRepository
import timber.log.Timber
import java.io.File

/** Owner-thread management operations; all live state is projected from DirectRimeBackend. */
internal class DirectRimeInputTypeOperations(
    private val owner: Owner,
    private val nativeState: (String, Boolean) -> Array<String> = Native::inputTypeState,
    private val nativeSelect: (String, String, Boolean, Long) -> Boolean = Native::selectInputType,
) {
    interface Owner {
        val started: Boolean
        val maintenance: Boolean
        val evo: Boolean
        val generation: Long
        val profile: String
        val user: File
        val engineIdentity: String
        fun requireAvailable()
        fun requireProfile(profile: String, path: String)
        fun refresh()
    }

    fun read(schema: String): RawConfig {
        owner.requireAvailable()
        val target = target()
        val state = state(schema) ?: return RawConfig()
        requireTarget(target)
        return RawConfig(arrayOf(
            RawConfig("Schema", state.schemaId),
            RawConfig("File", state.customFile.absolutePath),
            RawConfig("Types", state.types.map { RawConfig(it.option, it.label) }.toTypedArray()),
            RawConfig("Enabled", state.enabled.joinToString("\n")),
            RawConfig("Deployed", state.deployed.joinToString("\n")),
            RawConfig("Selected", state.selected.orEmpty()),
            RawConfig("Reset", state.reset.orEmpty()),
            RawConfig("Editable", owner.evo && state.selectableTypes.isNotEmpty()),
            RawConfig("Target", target.subItems.orEmpty().map { it }.toTypedArray()),
        ))
    }

    fun select(command: RawConfig, checkCancelled: () -> Unit) {
        val target = command.findByName("Target") ?: error("缺少输入类型操作目标")
        checkCancelled()
        requireTarget(target)
        val schema = command.findByName("Schema")?.value ?: error("缺少输入方案")
        val option = command.findByName("Option")?.value ?: error("缺少输入类型")
        require(owner.evo) { "当前引擎不支持输入类型切换" }
        val current = state(schema) ?: error("当前方案未声明输入类型")
        require(current.selectableTypes.any { it.option == option }) { "该输入类型尚未启用或部署" }
        val previous = current.selected ?: error("无法读取当前输入类型")
        val publicationGeneration = owner.generation
        RimeInputTypesRepository.select(owner.user, option, schema) {
            // Keep cancellation and publication inside the file transaction, including the
            // interval spent in JNI. A rejected operation restores both effective choices.
            checkCancelled()
            requireTarget(target)
            // This is a profile preference, valid across editor focus changes. Native still
            // checks the compiled choice and maintenance state without creating an editor.
            if (!nativeSelect(schema, option, owner.evo, -1)) false else try {
                checkCancelled()
                requireTarget(target)
                if (publicationGeneration == owner.generation) owner.refresh()
                checkCancelled()
                requireTarget(target)
                true
            } catch (failure: Throwable) {
                try {
                    // The owner serializes binding changes. A generation invalidated by another
                    // thread must not prevent restoring the same engine/profile preference.
                    requireRollbackBinding(target)
                    check(nativeSelect(schema, previous, owner.evo, -1)) { "无法恢复原输入类型" }
                    if (!owner.maintenance && publicationGeneration == owner.generation)
                        owner.refresh()
                } catch (rollback: Throwable) {
                    if (rollback !== failure) failure.addSuppressed(rollback)
                }
                throw failure
            }
        }
    }

    /** Called after native initialization/deployment; native cleared its compiled preference map. */
    fun restorePreferences(schemas: List<String>) {
        if (!owner.evo || !owner.started) return
        for (schema in schemas) try {
            val value = state(schema) ?: continue
            val reset = value.reset ?: continue
            if (value.selectableTypes.any { it.option == reset }) nativeSelect(schema, reset, true, -1)
        } catch (error: Exception) {
            // Invalid user YAML must not make an otherwise usable native session unavailable.
            Timber.w(error, "Failed to restore input type preference for %s", schema)
        }
    }

    private fun state(schema: String): RimeInputTypes? {
        val state = RimeInputTypesRepository.read(owner.user, schema) ?: return null
        val deployed = if (owner.evo) nativeState(schema, true).toList() else emptyList()
        return state.copy(deployed = deployed.drop(1), selected = deployed.firstOrNull())
    }

    private fun target() = RawConfig(arrayOf(
        RawConfig("Profile", owner.profile), RawConfig("Path", owner.user.canonicalPath),
        RawConfig("Engine", owner.engineIdentity),
    ))

    private fun requireTarget(target: RawConfig) {
        owner.requireAvailable()
        require(owner.started && !owner.maintenance) { "引擎正在维护或不可用" }
        require(target.findByName("Engine")?.value == owner.engineIdentity) { "输入引擎已改变" }
        owner.requireProfile(target.findByName("Profile")?.value ?: error("缺少工作目录"),
            target.findByName("Path")?.value ?: error("缺少工作目录路径"))
    }

    private fun requireRollbackBinding(target: RawConfig) {
        // Availability can change asynchronously while JNI is running. Roll back only the
        // unchanged live binding; native still enforces its own maintenance safety checks.
        require(owner.started) { "引擎已停止" }
        require(target.findByName("Engine")?.value == owner.engineIdentity) { "输入引擎已改变" }
        require(target.findByName("Profile")?.value == owner.profile &&
            target.findByName("Path")?.value == owner.user.canonicalPath) { "输入方案工作目录已改变" }
    }

    companion object {
        fun decode(config: RawConfig): RimeInputTypes? {
            val schema = config.findByName("Schema")?.value ?: return null
            val file = config.findByName("File")?.value ?: return null
            fun list(name: String) = config.findByName(name)?.value.orEmpty().lineSequence().filter(String::isNotBlank).toList()
            return RimeInputTypes(
                config.findByName("Types")?.subItems.orEmpty().map { RimeInputType(it.name, it.value) },
                list("Enabled"), config.findByName("Reset")?.value?.takeIf(String::isNotBlank), schema, File(file),
                list("Deployed"), config.findByName("Selected")?.value?.takeIf(String::isNotBlank),
            )
        }
    }
}
