/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.net.Uri
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.common.clearurls.ClearUrlsRuleFilter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClearUrlsAndroidTest {
    @Test fun androidCompilesTheBundledCatalogAndWorkerFilteringPreservesEncodedValues() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertTrue(HostClipboardFilter.isEnabled())
            assertEquals("ClearURLs(host)", HostClipboardFilter.description())
        }
        withContext(Dispatchers.IO) {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            val raw = instrumentation.targetContext.assets.open("clearurls/data.min.json")
                .bufferedReader().use { it.readText() }
            assertEquals(206, JSONObject(raw).getJSONObject("providers").length())
            val filter = ClearUrlsRuleFilter(raw)
            val clean = "https://example.test/?value=a%26b&nested=%2526&flag="
            val dirty = "$clean&utm_source=pc"
            assertEquals(clean, filter.transform(dirty))
            assertEquals(clean, HostClipboardFilter.transform(dirty))
            assertEquals(clean, HostClipboardFilter.transform("https://www.google.com/url?q=${Uri.encode(dirty)}&sa=t"))
            assertEquals("https://www.amazon.com/dp/B012345678?keep=1", HostClipboardFilter.transform(
                "https://www.amazon.com/dp/B012345678/ref=share?tag=affiliate-20&keep=1"))
        }
    }
}
