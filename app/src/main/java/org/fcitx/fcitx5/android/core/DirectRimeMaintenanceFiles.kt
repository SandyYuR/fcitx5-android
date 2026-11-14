/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.PreparedRimeInstall
import org.fcitx.fcitx5.android.data.RimeInstallPolicy
import org.fcitx.fcitx5.android.data.WanxiangAdvancedFiles
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.data.WanxiangProtection
import org.fcitx.fcitx5.android.data.WanxiangYaml
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdates
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangExtraDictionaries
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdates
import java.util.UUID

/** Synchronous update preparation performed on the native owner before it is stopped. */
internal object DirectRimeMaintenanceFiles {
    fun prepareSettings(
        context: Context,
        directConfig: DirectRimeConfig,
        raw: RawConfig,
    ): PreparedRimeInstall {
        val id = UUID.fromString(raw.findByName("RequestId")!!.value)
        val job = WanxiangPayload.child(context.noBackupFilesDir.canonicalFile, "wanxiang-settings/$id")
        val planFile = WanxiangPayload.child(job, "plan.json")
        require(planFile.isFile && planFile.length() <= 256 * 1024)
        val plan = Json.decodeFromString<WanxiangAdvancedFiles.Plan>(planFile.readText())
        directConfig.requireUpdateTarget(plan.profile, plan.path)
        WanxiangAdvancedFiles.verify(directConfig.user, plan.expected)
        val payload = WanxiangPayload.child(job, "payload")
        payload.listFiles().orEmpty().forEach { file ->
            require(WanxiangAdvancedFiles.writable(file.name) && plan.expected.containsKey(file.name))
            require(
                WanxiangPayload.child(payload.canonicalFile, file.name) == file.absoluteFile &&
                    file.isFile && file.length() <= WanxiangYaml.MAX_TEXT,
            )
            if (!file.name.endsWith(".dict.yaml")) WanxiangYaml().document(file.readText())
        }
        return PreparedRimeInstall(directConfig.user, payload, RimeInstallPolicy.SETTINGS)
    }

    fun prepareCustomUpdate(
        context: Context,
        directConfig: DirectRimeConfig,
        raw: RawConfig,
    ): PreparedRimeInstall? {
        val id = UUID.fromString(raw.findByName("RequestId")!!.value)
        val request = RimeSchemeUpdates.request(context, id)
        directConfig.requireUpdateTarget(request.target.profile, request.target.path)
        val job = RimeSchemeUpdates.directory(context, id)
        require(WanxiangPayload.child(job, "prepared").isFile) { "更新资源尚未准备完成" }
        val payload = WanxiangPayload.child(job, "payload")
        if (payload.walkTopDown().none { it.isFile }) return null
        return PreparedRimeInstall(directConfig.user, payload, RimeInstallPolicy.CUSTOM_UPDATE)
    }

    fun prepareWanxiangUpdate(
        context: Context,
        directConfig: DirectRimeConfig,
        raw: RawConfig,
    ): PreparedRimeInstall? {
        directConfig.requireUpdateTarget(
            raw.findByName("Profile")!!.value,
            raw.findByName("Path")!!.value,
        )
        val id = UUID.fromString(raw.findByName("RequestId")!!.value)
        val payload = WanxiangPayload.child(
            context.noBackupFilesDir.canonicalFile,
            "wanxiang-updates/$id/payload",
        )
        val selection = WanxiangUpdates.jobSelection(context, id)
        WanxiangProtection(selection.protection).filter(payload, directConfig.user)
        WanxiangExtraDictionaries.verifyAuxiliary(payload.parentFile!!, payload, directConfig.user)
        if (payload.walkTopDown().none { it.isFile }) return null
        return PreparedRimeInstall(directConfig.user, payload, RimeInstallPolicy.WANXIANG_UPDATE)
    }
}
