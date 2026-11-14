/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.RimeInstallPolicy
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.data.WanxiangAdvancedFiles
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeRequest
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdates
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangSelection
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DirectRimeMaintenanceFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val files = temporary.newFolder("files")
        val external = temporary.newFolder("external")
        val noBackup = temporary.newFolder("no-backup")
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getExternalFilesDir(type: String?) = external
            override fun getNoBackupFilesDir() = noBackup
        }
    }

    private fun request(id: UUID) = RawConfig(arrayOf(RawConfig("RequestId", id.toString())))

    private fun settingsJob(
        context: Context,
        config: DirectRimeConfig,
        id: UUID,
        payloadText: String? = "schema_list:\n  - schema: wanxiang\n",
    ) {
        val job = File(context.noBackupFilesDir, "wanxiang-settings/$id").apply { mkdirs() }
        val original = File(config.user, "default.yaml").apply { writeText("menu: {page_size: 5}\n") }
        val expected = mapOf("default.yaml" to WanxiangAdvancedFiles.hash(original))
        File(job, "plan.json").writeText(Json.encodeToString(
            WanxiangAdvancedFiles.Plan(config.current, config.user.canonicalPath, expected),
        ))
        payloadText?.let { text ->
            File(job, "payload/default.yaml").apply { parentFile!!.mkdirs(); writeText(text) }
        } ?: File(job, "payload").mkdirs()
    }

    private fun customJob(context: Context, config: DirectRimeConfig, id: UUID, withFile: Boolean) {
        val job = RimeSchemeUpdates.directory(context, id).apply { mkdirs() }
        val task = RimeSchemeTask(name = "fixture", source = "/scheme.zip", fileName = "scheme.zip")
        File(job, "request.json").writeText(Json.encodeToString(
            RimeSchemeRequest(WanxiangTarget(config.current, config.user.canonicalPath), listOf(task)),
        ))
        File(job, "prepared").writeText("1")
        File(job, "payload").mkdirs()
        if (withFile) File(job, "payload/ice.schema.yaml").writeText("schema: {schema_id: ice}\n")
    }

    private fun wanxiangJob(context: Context, id: UUID, withFile: Boolean) {
        val job = WanxiangUpdates.directory(context, id).apply { mkdirs() }
        File(job, "selection.json").writeText(Json.encodeToString(WanxiangSelection()))
        File(job, "payload").mkdirs()
        if (withFile) File(job, "payload/wanxiang.schema.yaml").writeText("schema: {schema_id: wanxiang}\n")
    }

    @Test fun threePreparationPathsReturnTheCapturedProfileAndExactInstallPolicies() {
        val context = context()
        val config = DirectRimeConfig(context).apply { load() }

        val settingsId = UUID.randomUUID()
        settingsJob(context, config, settingsId)
        val settings = DirectRimeMaintenanceFiles.prepareSettings(context, config, request(settingsId))

        val customId = UUID.randomUUID()
        customJob(context, config, customId, withFile = true)
        val custom = DirectRimeMaintenanceFiles.prepareCustomUpdate(context, config, request(customId))

        val wanxiangId = UUID.randomUUID()
        wanxiangJob(context, wanxiangId, withFile = true)
        val wanxiang = DirectRimeMaintenanceFiles.prepareWanxiangUpdate(context, config, RawConfig(arrayOf(
            RawConfig("Profile", config.current),
            RawConfig("Path", config.user.canonicalPath),
            RawConfig("RequestId", wanxiangId.toString()),
        )))

        assertEquals(RimeInstallPolicy.SETTINGS, settings.policy)
        assertEquals(RimeInstallPolicy.CUSTOM_UPDATE, custom?.policy)
        assertEquals(RimeInstallPolicy.WANXIANG_UPDATE, wanxiang?.policy)
        assertNotNull(custom)
        assertNotNull(wanxiang)
        listOf(settings, custom!!, wanxiang!!).forEach {
            assertEquals(config.user, it.user)
        }
    }

    @Test fun emptyPreparedUpdatesKeepTheExistingNoOpBoundaries() {
        val context = context()
        val config = DirectRimeConfig(context).apply { load() }

        val settingsId = UUID.randomUUID()
        settingsJob(context, config, settingsId, payloadText = null)
        // The settings branch historically lets the transaction report an empty payload after
        // stopping native; only worker-prepared update branches short-circuit before that point.
        assertNotNull(DirectRimeMaintenanceFiles.prepareSettings(context, config, request(settingsId)))

        val customId = UUID.randomUUID()
        customJob(context, config, customId, withFile = false)
        assertNull(DirectRimeMaintenanceFiles.prepareCustomUpdate(context, config, request(customId)))

        val wanxiangId = UUID.randomUUID()
        wanxiangJob(context, wanxiangId, withFile = false)
        assertNull(DirectRimeMaintenanceFiles.prepareWanxiangUpdate(context, config, RawConfig(arrayOf(
            RawConfig("Profile", config.current),
            RawConfig("Path", config.user.canonicalPath),
            RawConfig("RequestId", wanxiangId.toString()),
        ))))
    }
}
