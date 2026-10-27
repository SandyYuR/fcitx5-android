/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = android.app.Application::class)
class ClipboardUrlFilterTest {
    private val rules = """{"providers":{"all":{"urlPattern":".*","rules":["utm_source"]}}}"""

    @Test fun metadataAndOrdinaryTextNeverOpenTheCatalog() {
        val filter = ClipboardUrlFilter { fail("Metadata must never perform asset I/O"); rules }
        assertTrue(filter.isEnabled())
        assertEquals("ClearURLs(host)", filter.description())
        assertEquals("ordinary text", filter.transform("ordinary text"))
    }

    @Test fun theVeryFirstUrlIsFilteredOnItsWorkerAndTheCatalogIsLoadedOnce() {
        val caller = Thread.currentThread()
        val loads = AtomicInteger()
        val filter = ClipboardUrlFilter { assertNotSame(caller, Thread.currentThread()); loads.incrementAndGet(); rules }
        val worker = Executors.newSingleThreadExecutor()
        try {
            repeat(2) {
                assertEquals("https://example.test/?keep=1", worker.submit<String> {
                    filter.transform("https://example.test/?utm_source=x&keep=1")
                }.get(10, TimeUnit.SECONDS))
            }
            assertEquals(1, loads.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun concurrentFirstCopiesInitializeOnlyOneCatalog() {
        val loads = AtomicInteger()
        val filter = ClipboardUrlFilter { loads.incrementAndGet(); rules }
        val worker = Executors.newFixedThreadPool(8)
        try {
            val copies = (1..32).map { worker.submit<String> { filter.transform("https://example.test/?utm_source=x") } }
            copies.forEach { assertEquals("https://example.test/", it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, loads.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun failedLoadingPreservesTextAndDoesNotRetryForEveryCopy() {
        val loads = AtomicInteger()
        val filter = ClipboardUrlFilter { loads.incrementAndGet(); throw java.io.IOException("missing asset") }
        val text = "https://example.test/?utm_source=x"
        assertEquals(text, filter.transform(text))
        assertEquals(text, filter.transform(text))
        assertEquals(1, loads.get())
    }
}
