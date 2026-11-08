/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Application
import androidx.work.Data
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WanxiangUpdateResultTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun skipped(name: String) = WanxiangSkippedAsset(WanxiangPackage.Kind.EXTRA, name,
        WanxiangSelection.Source.GITHUB, "HTTP 404: $name at https://example.org/$name")

    @Test fun unicodePreviewsFitWorkManagerWhileTheFullReportSurvivesReload() {
        val target = Json.encodeToString(WanxiangTarget("rime", "/" + "中".repeat(900)))
        val result = WanxiangUpdateResult(target = target,
            skipped = listOf(skipped("😀".repeat(2000))),
            error = "错".repeat(998) + "😀" + "错".repeat(2000))
        val output = result.outputData(context)
        assertTrue(output.toByteArray().size <= Data.MAX_DATA_BYTES)
        assertEquals(target, output.getString(WanxiangUpdateWorker.TARGET))
        val preview = output.getString(WanxiangUpdateWorker.SKIPPED)!!
        assertTrue(preview.endsWith("…"))
        assertEquals(preview, preview.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertFalse(output.getString(WanxiangUpdateWorker.ERROR)!!.any { it.isSurrogate() })
        val directory = temporary.newFolder()
        result.save(directory, completed = false)
        assertEquals(result, WanxiangUpdateResult.read(directory, completed = false))
    }

    @Test fun aLongTargetIsKeptInTheFullResultWithoutWritingBrokenJsonToWorkManager() {
        val target = Json.encodeToString(WanxiangTarget("rime", "/" + "中".repeat(4000)))
        val result = WanxiangUpdateResult(target = target, skipped = listOf(skipped("missing.zip")))
        assertNull(result.outputData(context).getString(WanxiangUpdateWorker.TARGET))
        val directory = temporary.newFolder()
        result.save(directory, completed = true)
        assertEquals(result, WanxiangUpdateResult.read(directory, completed = true))
    }
}
