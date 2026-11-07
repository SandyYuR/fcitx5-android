/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.WanxiangProtection
import org.fcitx.fcitx5.android.utils.FileUtil
import org.junit.Assert.*
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
@Config(sdk = [23, 35], application = Application::class)
class WanxiangExtraDictionariesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun put(root: File, path: String, value: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(value) }
    @Test fun catalogRejectsDuplicateOrEscapingIdsRatherThanTreatingThemAsUrls() {
        val valid = WanxiangExtraDictionaries.parse("[{\"id\":\"medical\",\"name\":\"医学\"}]")
        assertEquals("medical", valid.single().id)
        for (data in listOf("[{\"id\":\"../secret\",\"name\":\"bad\"}]", "[]", "[{\"id\":\"a\",\"name\":\"A\"},{\"id\":\"a\",\"name\":\"B\"}]"))
            assertTrue(runCatching { WanxiangExtraDictionaries.parse(data) }.isFailure)
    }
    @Test fun aProtectedCharacterTableControlsProConversionAndChangesAreDetectedBeforeInstallation() {
        val user = temp.newFolder("user"); val payload = temp.newFolder("payload"); val job = temp.newFolder("job")
        put(user, "dicts/zi.pro.dict.yaml", "中\tzhong;old\n")
        put(payload, "dicts/zi.pro.dict.yaml", "中\tzhong;new\n")
        WanxiangProtection("^dicts/zi\\.pro\\.dict\\.yaml$").filter(payload, user)
        val aux = WanxiangExtraDictionaries.prepareAuxiliary(job, payload, user)
        assertEquals("old", aux["中"])
        val download = put(job, "medical.dict.yaml", "---\nname: medical\nversion: '1'\n...\n中\tzhong\t100\n")
        WanxiangExtraDictionaries.stage(download, payload, WanxiangPackage(WanxiangPackage.Kind.EXTRA, "medical.dict.yaml", "https://example.invalid/file"), WanxiangSelection.Scheme.PRO, aux)
        assertTrue(File(payload, "dicts/medical.pro.dict.yaml").readText().contains("中\tzhong;old\t100"))
        WanxiangExtraDictionaries.verifyAuxiliary(job, payload, user)
        put(user, "dicts/zi.pro.dict.yaml", "中\tzhong;changed\n")
        assertTrue(runCatching { WanxiangExtraDictionaries.verifyAuxiliary(job, payload, user) }.isFailure)
    }
    @Test fun newlyProtectedStagedAuxiliaryCannotBeRemovedAfterItsCodesHaveBeenUsed() {
        val user = temp.newFolder("race-user"); val payload = temp.newFolder("race-payload"); val job = temp.newFolder("race-job")
        put(payload, "dicts/zi.pro.dict.yaml", "中\tzhong;new\n")
        WanxiangExtraDictionaries.prepareAuxiliary(job, payload, user)
        put(user, "dicts/zi.pro.dict.yaml", "中\tzhong;personal\n")
        WanxiangProtection("^dicts/zi\\.pro\\.dict\\.yaml$").filter(payload, user)
        assertTrue(runCatching { WanxiangExtraDictionaries.verifyAuxiliary(job, payload, user) }.isFailure)
    }
    @Test fun cancellingAuxiliaryPreparationDoesNotPublishMetadataOrStageAnExtra() {
        val user = temp.newFolder("cancel-user"); val payload = temp.newFolder("cancel-payload"); val job = temp.newFolder("cancel-job")
        put(user, "dicts/zi.pro.dict.yaml", "中\tzhong;ab\n")
        val result = runCatching {
            WanxiangExtraDictionaries.prepareAuxiliary(job, payload, user) { throw CancellationException("cancelled") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(File(job, "auxiliary.json").exists())
        assertTrue(payload.list().isNullOrEmpty())
    }
    @Test fun largeProtectionConfigurationsUsePrivateJobStorageAndLegacyJobsStillLoad() {
        val context = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID()
        val selection = WanxiangSelection(protection = "#" + "comment".repeat(1800), extraDictionaries = listOf("medical"))
        val text = Json.encodeToString(selection)
        assertTrue(text.toByteArray().size > 10 * 1024)
        FileUtil.writeAtomically(File(WanxiangUpdates.directory(context, id), "selection.json"), text)
        assertEquals(selection, WanxiangUpdates.jobSelection(context, id))
        assertEquals(WanxiangSelection(), WanxiangUpdates.jobSelection(context, id, "{}"))
    }
}
