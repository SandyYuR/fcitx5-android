package org.fcitx.fcitx5.android.ui.main.update

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class DownloadStartupPolicyTest {
    @Test fun legacyPlatformsLoadTheWorkerButUseTheirExistingDownloadManagerRoute() = runBlocking {
        val worker = TestListenableWorkerBuilder.from(RuntimeEnvironment.getApplication(), HostsDownloadWorker::class.java).build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }
    @Test fun theMergedManifestDoesNotInitializeWorkManagerOnImeStartup() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("build/intermediates/merged_manifest/fxDebug/processFxDebugMainManifest/AndroidManifest.xml"))
        val metadata = document.getElementsByTagName("meta-data")
        for (i in 0 until metadata.length) {
            assertNotEquals("androidx.work.WorkManagerInitializer", metadata.item(i).attributes
                .getNamedItemNS("http://schemas.android.com/apk/res/android", "name")?.nodeValue)
        }
    }
}
