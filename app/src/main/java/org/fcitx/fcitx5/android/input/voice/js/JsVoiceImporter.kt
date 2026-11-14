/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonPrimitive

/** 一次导入识别的结果。失败原因用枚举而不是字符串，界面据此给出可翻译的说明。 */
internal sealed interface JsVoiceImportResult {

    data class Success(
        val meta: JsVoicePluginMeta,
        val fieldCount: Int,
        /** 已有同名导入插件，保存会覆盖它（设置与密钥保留）。 */
        val replacesExisting: Boolean,
    ) : JsVoiceImportResult

    data class Failure(
        val reason: Reason,
        /** 具体细节（脚本抛错信息、非法 id、被拒域名等），拼进提示里。 */
        val detail: String = "",
    ) : JsVoiceImportResult {
        enum class Reason {
            TOO_LARGE,
            LOAD_FAILED,
            NO_PLUGIN,
            NO_VOICE_FEATURE,
            INVALID_ID,
            RESERVED_ID,
            NO_HOSTS,
            TARGET_MISMATCH,
            INCOMPATIBLE_TARGET,
        }
    }
}

/**
 * 导入策略：**不依赖 Android、不依赖 JS 引擎**的纯判定。
 *
 * 抽出来的理由和 [JsVoiceInvoker] 一样：单测必须能直接喂一份 meta 就验证「什么算合格插件」，
 * 而不是每次去起一个 QuickJS。
 */
internal object JsVoiceImportPolicy {

    /**
     * 判定顺序有意如此：先形状（id 能不能用），再能力（是不是语音插件），最后边界（域名）。
     * 反过来的话，一个 id 都写错的脚本会先被抱怨「没声明域名」，用户改错地方。
     */
    fun validate(
        meta: JsVoicePluginMeta,
        builtInIds: Set<String>,
        target: JsVoiceProviders.Spec? = null,
    ): JsVoiceImportResult.Failure.Reason? {
        if (!JsVoicePluginMeta.isValidId(meta.id)) {
            return JsVoiceImportResult.Failure.Reason.INVALID_ID
        }
        if (target != null && meta.providerId != target.id) {
            return JsVoiceImportResult.Failure.Reason.TARGET_MISMATCH
        }
        if (target == null && meta.providerId in builtInIds) {
            return JsVoiceImportResult.Failure.Reason.RESERVED_ID
        }
        // A voice provider must remain a voice provider so the saved voice selection cannot turn
        // into a dead entry. Imported app lists may evolve; selection reconciliation handles a
        // removed app. Bundled contracts additionally keep every built-in app entry callable.
        if (target?.hasVoice == true && meta.voiceAsr == null) {
            return JsVoiceImportResult.Failure.Reason.INCOMPATIBLE_TARGET
        }
        val protectedTarget = target?.takeIf { it.builtIn || it.overridesBuiltIn }
        if (protectedTarget != null &&
            protectedTarget.apps.any { oldApp -> meta.apps.none { it.id == oldApp.id } }) {
            return JsVoiceImportResult.Failure.Reason.INCOMPATIBLE_TARGET
        }
        if (meta.voiceAsr == null && meta.apps.isEmpty()) {
            return JsVoiceImportResult.Failure.Reason.NO_VOICE_FEATURE
        }
        if (meta.voiceAsr != null && meta.hosts.isEmpty()) {
            return JsVoiceImportResult.Failure.Reason.NO_HOSTS
        }
        return null
    }
}

/**
 * 识别一个待导入的脚本：真的在 QuickJS 里跑一遍，而不是按关键字猜。
 *
 * 只有跑过才知道三件界面必须显示的事：这个脚本注册的是不是 voice.asr 插件、它自报什么 id、
 * 它会连哪些域名。躲开求值就只能靠正则匹配源码，既漏报又会误报。
 */
internal object JsVoiceImporter {

    suspend fun inspect(
        script: String,
        locale: String,
        builtInIds: Set<String>,
        importedIds: Set<String> = emptySet(),
        targetPluginId: String? = null,
        targetSpec: JsVoiceProviders.Spec? = null,
        existingImportedSpecs: Map<String, JsVoiceProviders.Spec> = emptyMap(),
    ): JsVoiceImportResult {
        val size = script.toByteArray(Charsets.UTF_8).size
        if (size > JsVoicePluginMeta.MAX_SCRIPT_BYTES) {
            return JsVoiceImportResult.Failure(
                JsVoiceImportResult.Failure.Reason.TOO_LARGE,
                size.toString(),
            )
        }
        val js = JsVoiceRuntime.create()
        return try {
            JsVoiceStubNatives.install(js, locale)
            runCatching { JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script) }
                .getOrElse { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    return JsVoiceImportResult.Failure(
                        JsVoiceImportResult.Failure.Reason.LOAD_FAILED,
                        error.message.orEmpty(),
                    )
                }
            val meta = JsVoicePluginMeta.parse(js.evaluate<String>("__meta()"))
                ?: return JsVoiceImportResult.Failure(JsVoiceImportResult.Failure.Reason.NO_PLUGIN)
            if (meta.id.isBlank()) {
                // 没有 register()，或者 register 的入参里没有 id：两者对用户都是「这不是语音插件」。
                return JsVoiceImportResult.Failure(JsVoiceImportResult.Failure.Reason.NO_PLUGIN)
            }
            val target = if (targetPluginId != null) {
                if (meta.providerId != targetPluginId) {
                    return JsVoiceImportResult.Failure(JsVoiceImportResult.Failure.Reason.TARGET_MISMATCH, meta.providerId)
                }
                // A bundled ID is governed by its APK contract even while a custom replacement
                // is active. Otherwise apps added only by custom v1 would become mandatory and
                // custom v2 could never remove them again.
                (targetSpec ?: JsVoiceProviders.builtInSpecOf(targetPluginId)
                    ?: JsVoiceProviders.specOf(targetPluginId))?.takeIf { it.id == targetPluginId }
                    ?: return JsVoiceImportResult.Failure(JsVoiceImportResult.Failure.Reason.INCOMPATIBLE_TARGET)
            } else if (meta.id in importedIds && meta.providerId !in builtInIds) {
                // The ordinary import page is also the upgrade path for user plugins. Preserve an
                // existing voice capability there just as the targeted settings flow does, or a
                // voice provider could silently become app-only and strand the saved selection.
                existingImportedSpecs[meta.providerId]
            } else {
                null
            }
            JsVoiceImportPolicy.validate(meta, builtInIds, target)?.let { reason ->
                val detail = if (reason == JsVoiceImportResult.Failure.Reason.INVALID_ID) meta.id
                    else meta.rejectedHosts.joinToString(", ")
                return JsVoiceImportResult.Failure(reason, detail)
            }
            // 字段数只是给用户看的「这个插件要填几项」；解析失败不算导入失败——
            // 设置页打不开字段是插件自己的问题，不该拦住导入。
            val fieldCount = runCatching {
                JsVoiceFields.parse(js.evaluate<String>("__describe(" + JsonPrimitive(locale) + ")")).size
            }.getOrElse { if (it is CancellationException) throw it else 0 }
            JsVoiceImportResult.Success(
                meta = meta,
                fieldCount = fieldCount,
                replacesExisting = target != null || meta.id in importedIds,
            )
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            JsVoiceImportResult.Failure(JsVoiceImportResult.Failure.Reason.LOAD_FAILED, error.message.orEmpty())
        } finally {
            runCatching { js.close() }
        }
    }
}
