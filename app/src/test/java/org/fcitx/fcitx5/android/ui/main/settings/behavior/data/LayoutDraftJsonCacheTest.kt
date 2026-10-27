/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LayoutDraftJsonCacheTest {
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "draft-test-ui") }.asCoroutineDispatcher()
    @After fun close() = ui.close()

    private fun manager(text: String = "one") = LayoutDataManager(ContextWrapper(null)).apply {
        entries["rime"] = mutableListOf(mutableListOf(mutableMapOf("type" to "MacroKey", "label" to text,
            "tap" to mutableMapOf("macro" to mutableListOf(mutableMapOf("type" to "text", "text" to text))))))
    }

    @Test fun readingAnUnpreparedCacheNeverStartsSerialization() = runBlocking(ui) {
        val calls = AtomicInteger()
        val cache = LayoutDraftJsonCache(this, encode = { calls.incrementAndGet(); "prepared" })
        assertNull(cache.valueFor(manager()))
        assertEquals(0, calls.get())
    }

    @Test fun serializationUsesTheWorkerAndIdenticalRequestsReuseTheResult() = runBlocking(ui) {
        val caller = Thread.currentThread()
        val manager = manager()
        val document = LayoutDocument.capture(manager)
        val calls = AtomicInteger()
        val cache = LayoutDraftJsonCache(this, encode = {
            assertNotSame("JSON formatting must leave the caller thread", caller, Thread.currentThread())
            calls.incrementAndGet()
            "prepared"
        })
        val first = cache.prepare(document)
        first.join()
        assertEquals("prepared", cache.valueFor(manager))
        assertSame(first, cache.prepare(document))
        assertEquals(1, calls.get())
    }

    @Test fun anIncompleteRequestReturnsNullWithoutBlockingTheCaller() = runBlocking(ui) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = manager()
        val cache = LayoutDraftJsonCache(this, encode = {
            started.complete(Unit)
            withTimeout(5_000) { release.await() }
            "prepared"
        })
        val job = cache.prepare(LayoutDocument.capture(manager))
        try {
            withTimeout(5_000) { started.await() }
            assertNull(cache.valueFor(manager))
        } finally { release.complete(Unit) }
        job.join()
        assertEquals("prepared", cache.valueFor(manager))
    }

    @Test fun liveEditsAfterCaptureCannotChangeTheBackgroundSnapshotOrServeStaleJson() = runBlocking(ui) {
        val manager = manager()
        val document = LayoutDocument.capture(manager)
        val original = manager.exportCurrentJsonCompact()
        val cache = LayoutDraftJsonCache(this)
        val job = cache.prepare(document)
        val tap = manager.entries.getValue("rime")[0][0]["tap"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val steps = tap["macro"] as MutableList<MutableMap<String, Any?>>
        steps[0]["text"] = "changed"
        job.join()
        assertNull("An inline edit that is not in history still invalidates the cached text", cache.valueFor(manager))
        document.restore(manager)
        assertEquals(original, cache.valueFor(manager))
    }

    @Test fun everySavedMetadataSectionIsCheckedBeforeReusingJson() = runBlocking(ui) {
        val manager = manager()
        manager.setLayoutAuxBarKeys("rime", listOf(mapOf("type" to "MacroKey", "label" to "aux")))
        manager.setLayoutAuxBarConfig("rime", AuxBarConfig(AuxBarPosition.Top, 10f))
        val document = LayoutDocument.capture(manager)
        val cache = LayoutDraftJsonCache(this)
        cache.prepare(document).join()
        val mutations: List<() -> Unit> = listOf(
            { manager.entries.getValue("rime")[0][0]["label"] = "changed" },
            { manager.layoutHeightPercentOverrides["rime"] = 45 },
            { manager.layoutHeightPercentOverridesLandscape["rime"] = 25 },
            { manager.setLayoutAuxBarConfig("rime", AuxBarConfig(AuxBarPosition.Left, 20f)) },
            { manager.getLayoutAuxBarKeysRef("rime")[0]["label"] = "changed" },
            { manager.entries.clear() }
        )
        for (mutate in mutations) {
            document.restore(manager)
            assertNotNull(cache.valueFor(manager))
            mutate()
            assertNull(cache.valueFor(manager))
        }
    }

    @Test fun rapidRequestsKeepOneEncoderAndOnlyTheNewestPendingDocument() = runBlocking(ui) {
        val a = manager("a"); val b = manager("b"); val c = manager("c")
        val docA = LayoutDocument.capture(a); val docB = LayoutDocument.capture(b); val docC = LayoutDocument.capture(c)
        val startedA = CompletableDeferred<Unit>(); val releaseA = CompletableDeferred<Unit>()
        val startedC = CompletableDeferred<Unit>(); val releaseC = CompletableDeferred<Unit>()
        val active = AtomicInteger(); val maxActive = AtomicInteger(); val calls = AtomicInteger()
        val cache = LayoutDraftJsonCache(this, encode = { document ->
            val running = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, running) }
            calls.incrementAndGet()
            try {
                if (document === docA) { startedA.complete(Unit); withTimeout(5_000) { releaseA.await() } }
                if (document === docC) { startedC.complete(Unit); withTimeout(5_000) { releaseC.await() } }
                document.entries.getValue("rime")[0][0]["label"].toString()
            } finally { active.decrementAndGet() }
        })
        val first = cache.prepare(docA)
        try {
            withTimeout(5_000) { startedA.await() }
            val skipped = cache.prepare(docB)
            val latest = cache.prepare(docC)
            assertTrue(skipped.isCompleted)
            releaseA.complete(Unit)
            withTimeout(5_000) { startedC.await() }
            assertNull("Old results must not be published while the newest one is pending", cache.valueFor(a))
            releaseC.complete(Unit)
            first.join(); latest.join()
            assertEquals("c", cache.valueFor(c))
            assertNull(cache.valueFor(b))
            assertEquals(2, calls.get())
            assertEquals(1, maxActive.get())
        } finally { releaseA.complete(Unit); releaseC.complete(Unit) }
    }

    @Test fun clearInvalidatesEvenAnAlreadyRunningEncoder() = runBlocking(ui) {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = manager()
        val cache = LayoutDraftJsonCache(this, encode = {
            started.complete(Unit); withTimeout(5_000) { release.await() }; "old"
        })
        val job = cache.prepare(LayoutDocument.capture(manager))
        try {
            withTimeout(5_000) { started.await() }
            cache.clear()
        } finally { release.complete(Unit) }
        job.join()
        assertNull(cache.valueFor(manager))
    }

    @Test fun aFailedPreparationCanBeRetriedAndDoesNotPublishIncompleteJson() = runBlocking(ui) {
        val manager = manager()
        val document = LayoutDocument.capture(manager)
        val calls = AtomicInteger()
        val cache = LayoutDraftJsonCache(this, encode = {
            if (calls.incrementAndGet() == 1) throw IOException("injected encoding failure")
            "valid"
        })
        cache.prepare(document).join()
        assertNull(cache.valueFor(manager))
        cache.prepare(document).join()
        assertEquals("valid", cache.valueFor(manager))
    }

    @Test fun clearingTheOwnerBeforeTheWorkerStartsCancelsItsCompletionHandle() = runBlocking(ui) {
        val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val manager = manager()
        val calls = AtomicInteger()
        val cache = LayoutDraftJsonCache(owner, encode = { calls.incrementAndGet(); "unused" })
        val job = cache.prepare(LayoutDocument.capture(manager))
        owner.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(0, calls.get())
        assertNull(cache.valueFor(manager))
        assertTrue(cache.prepare(LayoutDocument.capture(manager)).isCompleted)
    }

    @Test fun clearingTheOwnerDuringEncodingNeverPublishesItsResult() = runBlocking(ui) {
        val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = manager()
        val cache = LayoutDraftJsonCache(owner, encode = {
            started.complete(Unit); withTimeout(5_000) { release.await() }; "old"
        })
        val job = cache.prepare(LayoutDocument.capture(manager))
        try {
            withTimeout(5_000) { started.await() }
            owner.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertNull(cache.valueFor(manager))
        } finally { release.complete(Unit); owner.cancel() }
    }

    @Test fun backgroundEncodingMatchesTheExistingExporterIncludingNestedAndSplitData() = runBlocking(ui) {
        val manager = manager("正文".repeat(100))
        manager.entries["rime:方案:__variant__:split"] = mutableListOf(mutableListOf(mutableMapOf(
            "type" to "AlphabetKey", "main" to "n", "alt" to "N", "displayText" to mapOf("方案" to "字"))))
        manager.layoutHeightPercentOverrides["rime"] = 45
        manager.layoutHeightPercentOverridesLandscape["rime"] = 25
        manager.setLayoutAuxBarConfig("rime", AuxBarConfig(AuxBarPosition.Right, 15f))
        manager.setLayoutAuxBarKeys("rime", listOf(mapOf("type" to "MacroKey", "label" to "，")))
        val expected = manager.exportCurrentJsonCompact()
        val cache = LayoutDraftJsonCache(this, Dispatchers.Default)
        cache.prepare(LayoutDocument.capture(manager)).join()
        assertEquals(expected, cache.valueFor(manager))
    }
}
