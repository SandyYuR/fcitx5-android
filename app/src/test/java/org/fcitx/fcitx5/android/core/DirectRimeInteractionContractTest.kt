/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.directrime.RawInputState
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual backend owner/admission/publishing with the process's existing Native shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class DirectRimeInteractionContractTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher
    private lateinit var ownerThread: Thread
    private lateinit var native: InteractionNative
    private var generation = 0L
    private val publication: DirectRimePublicationState get() = ReflectionHelpers.getField(backend, "publicationState")

    @Before fun prepare() = runBlocking {
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ownerThread = withContext(owner) { Thread.currentThread() }
        ReflectionHelpers.setField(backend, "started", true)
        ReflectionHelpers.setField(backend, "schemas", listOf("fixture" to "Fixture"))
        generation = backend.invalidateInputSession()
        backend.prepareInputSession(generation, "editor", CapabilityFlags.DefaultFlags, true)
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").apply { running(); resolve(generation, true) }
        native = InteractionNative()
        NativeTestShadow.delegate = native
        backend.setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "No"))))
        backend.sendKey('n', 0u, 0, false, 0)
        backend.setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "ComposingText"))))
        native.clearCalls()
    }

    @After fun close() {
        ReflectionHelpers.setField(backend, "started", false)
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        backend.lifeCycleScope.cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    private inner class InteractionNative : NativeTestDelegate {
        var revision = 0L
        var schema = "fixture"
        var gate = 0
        var sequence = emptyArray<String>()
        val sequenceReads = mutableListOf<Boolean>()
        val actionArguments = mutableListOf<String>()
        var tabsRevision = 30L
        var preedit = "ni hao"
        var cursorBytes = 4
        var rawText = "nihao"
        var rawCaret = 3
        var current: Snapshot? = null
        var rawReads = 0
        var onRaw: () -> Unit = {}
        var onStop: () -> Unit = {}
        var onCandidates: (Int) -> Unit = {}
        val actions = mutableListOf<Pair<Int, Int>>()
        val candidateReads = mutableListOf<Pair<Int, Int>>()

        fun clearCalls() {
            actions.clear(); candidateReads.clear(); rawReads = 0
            sequenceReads.clear(); actionArguments.clear()
        }
        private fun checkOwner() = assertSame(ownerThread, Thread.currentThread())
        private fun nextSnapshot(handled: Boolean) = Snapshot(generation, ++revision, 0, handled, gate, "", preedit,
            schema, false, 0, true, cursorBytes, arrayOf("你"), emptyArray(), arrayOf("同标签"),
            "Fixture", 0, "你", arrayOf("1"), tabsRevision = tabsRevision).also { current = it }

        override fun sequenceKeys(evo: Boolean): Array<String> {
            checkOwner(); sequenceReads += evo; return sequence
        }
        override fun stop() { checkOwner(); onStop() }
        override fun options(): Array<String> { checkOwner(); return emptyArray() }
        override fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot {
            checkOwner()
            assertEquals(generation, value)
            return if (release) Snapshot.reusePresentation(checkNotNull(current), ++revision).also { current = it }
                else nextSnapshot(true)
        }
        override fun action(value: Long, previous: Long, action: Int, index: Int, name: String): Snapshot {
            checkOwner()
            assertEquals(generation, value)
            assertEquals(revision, previous)
            actions += action to index
            actionArguments += name
            if (action == 9) {
                rawCaret = index
                cursorBytes = if (rawText == preedit || index <= 2) index else index + 1
            }
            return nextSnapshot(action != 13)
        }
        override fun rawInput(value: Long, previous: Long): RawInputState {
            checkOwner()
            assertEquals(generation, value)
            assertEquals(revision, previous)
            rawReads++
            onRaw()
            return RawInputState(rawText, rawCaret)
        }
        override fun candidates(value: Long, offset: Int, limit: Int): Array<String> {
            checkOwner()
            assertEquals(generation, value)
            candidateReads += offset to limit
            onCandidates(offset)
            val count = minOf(limit, (20 - offset).coerceAtLeast(0))
            return Array(count * 2) { index ->
                if (index % 2 == 0) "word-${offset + index / 2}" else "comment-${offset + index / 2}"
            }
        }
    }

    private fun evoBindings(vararg bindings: String) = arrayOf("evo-editor-bindings-v1", *bindings)

    private suspend fun sequenceFixture(
        evo: Boolean = true,
        schema: String = "luna_pinyin",
        keys: Array<String> = evoBindings("Control+Up", "manual_up", "Control+Down", "manual_down",
            "Shift+Delete", "manual_delete", "Control+Delete", "auto_delete"),
    ) {
        withContext(owner) {
            ReflectionHelpers.setField(backend, "configuredEvoFamily", evo)
            native.schema = schema
            native.sequence = keys
        }
        backend.sendKey('x', 0u, 0, false, 0)
        native.clearCalls()
    }

    @Test fun evoMenuUsesDeclaredKeysAndReplacesTheEquivalentLegacyForgetEntry() = runBlocking {
        sequenceFixture()
        val menu = backend.getCandidateActions(0)
        assertEquals(listOf(2, 3, 4, 5), menu.map { it.id })
        assertEquals("重置自动调频", menu.last().text)
        assertEquals(listOf(true), native.sequenceReads)
        assertTrue(backend.triggerCandidateActionIfCurrent(0, 5, true, backend.candidateActionContext))
        assertEquals(listOf(15 to 0), native.actions)
        assertEquals(listOf("65535 4"), native.actionArguments)
        // Existing callers keep their original forget action even when the visible menu replaces it.
        assertTrue(backend.triggerCandidateActionIfCurrent(0, 0, true, backend.candidateActionContext))
        assertEquals(listOf(15 to 0, 12 to 0), native.actions)
    }

    @Test fun evoManualResetIsIndependentOfMovementAndRetainsForgetWithoutAutoDelete() = runBlocking {
        sequenceFixture(keys = evoBindings("Shift+Delete", "manual_delete"))
        assertEquals(listOf(0, 4), backend.getCandidateActions(0).map { it.id })
        assertTrue(backend.triggerCandidateActionIfCurrent(0, 4, false, backend.candidateActionContext))
        assertEquals(listOf(14 to 0), native.actions)
        assertEquals(listOf("65535 1"), native.actionArguments)
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 1, false, backend.candidateActionContext))
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 5, false, backend.candidateActionContext))
        assertEquals(listOf(14 to 0), native.actions)
    }

    @Test fun nonEvoSchemaNamesCannotEnableEvoBindingsOrNativeReads() = runBlocking {
        sequenceFixture(evo = false, schema = "evo_librime-evo")
        assertEquals(listOf(0), backend.getCandidateActions(0).map { it.id })
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 5, true, backend.candidateActionContext))
        assertTrue(native.sequenceReads.isEmpty())
        assertTrue(native.actions.isEmpty())
    }

    @Test fun wanxiangLuaKeysAndLabelsStayUnchangedForEitherConfiguredFamily() = runBlocking {
        for (evo in listOf(false, true)) {
            sequenceFixture(evo = evo, schema = "wanxiang_t9",
                keys = arrayOf("Control+j", "Control+k", "Control+l", "Control+p"))
            val menu = backend.getCandidateActions(0)
            assertEquals(listOf(0, 1, 2, 3, 4), menu.map { it.id })
            assertEquals(listOf("忘记此词", "置顶", "前移一位", "后移一位", "重置排序"), menu.map { it.text })
            assertEquals(listOf(evo), native.sequenceReads)
            assertTrue(backend.triggerCandidateActionIfCurrent(0, 2, false, backend.candidateActionContext))
            assertEquals(listOf("106 4"), native.actionArguments)
        }
    }

    @Test fun evoWithoutAnActiveEditorBindingOnlyKeepsForget() = runBlocking {
        sequenceFixture(keys = emptyArray())
        assertEquals(listOf(0), backend.getCandidateActions(0).map { it.id })
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 5, true, backend.candidateActionContext))
        assertTrue(native.actions.isEmpty())
    }

    @Test fun candidateActionReReadsTheCurrentSchemaKeyAfterShowingTheMenu() = runBlocking {
        sequenceFixture()
        backend.getCandidateActions(0)
        withContext(owner) {
            native.sequence = evoBindings("Control+Left", "manual_up", "Control+Down", "manual_down",
                "Shift+Delete", "manual_delete", "Control+Delete", "auto_delete")
        }
        assertTrue(backend.triggerCandidateActionIfCurrent(0, 2, false, backend.candidateActionContext))
        assertEquals(listOf("65361 4"), native.actionArguments)
        assertEquals(listOf(true, true), native.sequenceReads)
    }

    @Test fun evoUndeliverableBindingsNeverReachNativeAction() = runBlocking {
        for (key in listOf("Control+Mod3+Up", "Control+Super+Up", "Control+Lock+Up", "Ctrl+Up",
            "prefixControl+Up", " Control+Up", "Control+0xff52", "Control+ENTER")) {
            sequenceFixture(keys = evoBindings(key, "manual_up"))
            assertEquals(key, listOf(0), backend.getCandidateActions(0).map { it.id })
            assertFalse(key, backend.triggerCandidateActionIfCurrent(0, 2, false, backend.candidateActionContext))
            assertEquals(listOf(true, true), native.sequenceReads)
            assertTrue(key, native.actions.isEmpty())
            assertTrue(key, native.actionArguments.isEmpty())
        }
    }

    @Test fun evoAliasCollisionsWithAnyActionNeverReachNativeAction() = runBlocking {
        for (other in listOf("manual_up", "manual_down", "manual_delete", "auto_delete", "noop", "unknown", "")) {
            sequenceFixture(keys = evoBindings("Control+Shift+Prior", "manual_up", "Shift+Control+Page_Up", other))
            assertEquals(other, listOf(0), backend.getCandidateActions(0).map { it.id })
            assertFalse(other, backend.triggerCandidateActionIfCurrent(0, 2, false, backend.candidateActionContext))
            assertEquals(listOf(true, true), native.sequenceReads)
            assertTrue(other, native.actions.isEmpty())
            assertTrue(other, native.actionArguments.isEmpty())
        }
    }

    @Test fun candidateActionRejectsUnsupportedModifierIntroducedAfterShowingTheMenu() = runBlocking {
        sequenceFixture(keys = evoBindings("Control+Up", "manual_up"))
        assertEquals(listOf(0, 2), backend.getCandidateActions(0).map { it.id })
        val context = backend.candidateActionContext
        withContext(owner) { native.sequence = evoBindings("Control+Mod3+Up", "manual_up") }
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 2, false, context))
        assertEquals(listOf(true, true), native.sequenceReads)
        assertTrue(native.actions.isEmpty())
        assertTrue(native.actionArguments.isEmpty())
    }

    @Test fun candidateActionRejectsAliasCollisionIntroducedAfterShowingTheMenu() = runBlocking {
        sequenceFixture(keys = evoBindings("Control+Prior", "manual_up"))
        assertEquals(listOf(0, 2), backend.getCandidateActions(0).map { it.id })
        val context = backend.candidateActionContext
        withContext(owner) {
            native.sequence = evoBindings("Control+Prior", "manual_up", "Control+Page_Up", "noop")
        }
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 2, false, context))
        assertEquals(listOf(true, true), native.sequenceReads)
        assertTrue(native.actions.isEmpty())
        assertTrue(native.actionArguments.isEmpty())
    }

    @Test fun evoAutoDeleteRejectsAnInvalidatedMenuContextBeforeReadingKeys() = runBlocking {
        sequenceFixture()
        val context = backend.candidateActionContext
        withContext(owner) { ReflectionHelpers.setField(publication, "candidateVersion", context.version + 1) }
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 5, true, context))
        assertTrue(native.sequenceReads.isEmpty())
        assertTrue(native.actions.isEmpty())
    }

    @Test fun queuedEvoAutoDeleteChecksRevisionBeforeReadingKeys() {
        runBlocking { sequenceFixture() }
        val context = backend.candidateActionContext
        queuedChange(
            {
                val replacement = Snapshot.reusePresentation(checkNotNull(native.current), ++native.revision)
                native.current = replacement
                ReflectionHelpers.setField(backend, "snapshot", replacement)
            },
            { backend.triggerCandidateActionIfCurrent(0, 5, true, context) },
        )
        assertTrue(native.sequenceReads.isEmpty())
    }

    @Test fun stoppedEvoAndStaleInputNeverReadSchemaBindings() = runBlocking {
        sequenceFixture()
        ReflectionHelpers.setField(backend, "started", false)
        assertEquals(listOf(0), backend.getCandidateActions(0).map { it.id })
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 5, true, backend.candidateActionContext))
        ReflectionHelpers.setField(backend, "started", true)
        backend.invalidateInputSession()
        assertEquals(listOf(0), backend.getCandidateActions(0).map { it.id })
        assertTrue(native.sequenceReads.isEmpty())
        assertTrue(native.actions.isEmpty())
    }

    @Test fun maintenanceAndUnreadyEvoNeverReadSchemaBindings() = runBlocking {
        sequenceFixture()
        ReflectionHelpers.setField(backend, "maintenance", true)
        assertEquals(listOf(0), backend.getCandidateActions(0).map { it.id })
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 5, true, backend.candidateActionContext))
        ReflectionHelpers.setField(backend, "maintenance", false)
        native.gate = 1
        backend.sendKey('x', 0u, 0, false, 0)
        assertEquals(listOf(0), backend.getCandidateActions(0).map { it.id })
        assertFalse(backend.triggerCandidateActionIfCurrent(0, 5, true, backend.candidateActionContext))
        assertTrue(native.sequenceReads.isEmpty())
        assertTrue(native.actions.isEmpty())
    }

    @Test fun runtimeStopClearsConfiguredFamilyEvenWhenNativeStopThrows() = runBlocking {
        sequenceFixture()
        val runtime = ReflectionHelpers.getField<EngineLifecycleController>(backend, "runtime")
        val finalize = ReflectionHelpers.getField<() -> Unit>(runtime, "finalize")
        val failure = IllegalStateException("native stop failed")
        native.onStop = { throw failure }
        withContext(owner) {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { finalize() })
            assertFalse(ReflectionHelpers.getField<Boolean>(backend, "configuredEvoFamily"))
            assertFalse(ReflectionHelpers.getField<Boolean>(backend, "started"))
            assertNull(ReflectionHelpers.getField<Snapshot?>(backend, "snapshot"))
        }
    }

    @Test fun keyUpOnlyRevisionChangesKeepPublishedCandidatePageTargetsCurrent() = runBlocking {
        val expected = backend.candidateActionContext
        val previousRevision = native.revision
        backend.sendKey('n', 0u, 0, true, 0)
        assertTrue(native.revision > previousRevision)
        assertEquals(expected, backend.candidateActionContext)
        val page = backend.getCandidates(0, 12)
        assertEquals(listOf(0 to 8, 8 to 4), native.candidateReads)
        assertEquals((0 until 12).map { "word-$it" }, page.map { it.text })
        page.forEachIndexed { index, word ->
            assertEquals(CandidateSelectionTarget(expected, index, true), word.selectionTarget)
        }
        assertTrue(native.actions.isEmpty())
    }

    @Test fun candidateInvalidationBetweenNativeBatchesNeverReturnsAPartialOldPage() = runBlocking {
        native.onCandidates = { if (it == 0) ReflectionHelpers.setField(publication, "candidateVersion", backend.candidateActionContext.version + 1) }
        try {
            backend.getCandidates(0, 16)
            fail("A page from an invalidated candidate list was returned")
        } catch (_: CancellationException) {
            assertEquals(listOf(0 to 8), native.candidateReads)
        }
    }

    private fun queuedChange(change: () -> Unit, action: suspend () -> Boolean) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(owner) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var pending: Deferred<Boolean>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val request = async(start = CoroutineStart.UNDISPATCHED) { action() }
            pending = request
            change()
            release.countDown()
            assertFalse(withTimeout(5_000) { request.await() })
            assertTrue(native.actions.isEmpty())
        } finally { release.countDown(); pending?.cancelAndJoin(); blocker.join() }
    }

    @Test fun queuedSelectionChecksNativeRevisionEvenWhenTheCandidateVersionDidNotChange() {
        val context = backend.candidateActionContext
        queuedChange(
            {
                val replacement = Snapshot.reusePresentation(checkNotNull(native.current), ++native.revision)
                native.current = replacement
                ReflectionHelpers.setField(backend, "snapshot", replacement)
                assertEquals(context, backend.candidateActionContext)
            },
            { backend.selectCandidateIfCurrent(0, true, context) },
        )
    }

    @Test fun aQueuedSelectionStillUsesTheBackendFinalRunningGate() {
        val context = backend.candidateActionContext
        queuedChange(
            { ReflectionHelpers.setField(backend, "started", false) },
            { backend.selectCandidateIfCurrent(0, true, context) },
        )
    }

    @Test fun unchangedLabelsWithANewTabsRevisionRejectTheOldTarget() = runBlocking {
        val previous = backend.candidateTabActionContext
        native.tabsRevision++
        backend.sendKey('x', 0u, 0, false, 0)
        val current = backend.candidateTabActionContext
        assertNotEquals(previous, current)
        native.clearCalls()
        assertFalse(backend.triggerCandidateListTabActionIfCurrent(CandidateTabActionTarget(previous, 0)))
        assertTrue(native.actions.isEmpty())
        assertTrue(backend.triggerCandidateListTabActionIfCurrent(CandidateTabActionTarget(current, 0)))
        assertEquals(listOf(4 to 0), native.actions)
    }

    @Test fun anEditorInvalidatedDuringRawReadCannotReceiveTheFollowingCaretAction() = runBlocking {
        native.onRaw = { backend.invalidateInputSession() }
        assertFalse(backend.moveCursorIfCurrent(3, generation, "ni hao"))
        assertEquals(1, native.rawReads)
        assertTrue(native.actions.isEmpty())
        assertTrue(backend.inputSession != generation)
    }

    @Test fun supplementaryCursorInteractionPassesRawByteCoordinatesToNative() = runBlocking {
        native.preedit = "汉🙂e"
        native.rawText = "汉🙂e"
        native.cursorBytes = 7
        native.rawCaret = 7
        backend.sendKey('x', 0u, 0, false, 0)
        assertEquals(0, native.rawReads)
        native.clearCalls()
        assertTrue(backend.moveCursorIfCurrent(1, generation, "汉🙂e"))
        assertEquals(1, native.rawReads)
        assertEquals(listOf(9 to 3), native.actions)
        assertEquals(3, native.rawCaret)
    }

    @Test fun displayedGlobalSelectionRemainsIndependentOfTheLegacyPagingMode() = runBlocking {
        backend.setCandidatePagingMode(0)
        native.clearCalls()
        assertTrue(backend.selectCandidateIfCurrent(3, false, backend.candidateActionContext))
        assertTrue(backend.selectCandidateIfCurrent(3, true, backend.candidateActionContext))
        assertTrue(backend.select(3))
        assertEquals(listOf(1 to 3, 11 to 3, 11 to 3), native.actions)
    }

    @Test fun legacyForgetAndTabEntrypointsKeepTheirDistinctIndexValidation() = runBlocking {
        backend.triggerCandidateAction(-1, 0)
        backend.triggerGlobalCandidateAction(-1, 0)
        backend.triggerCandidateListTabAction(-2)
        assertEquals(listOf(5 to -1, 12 to -1, 4 to -2), native.actions)
        native.clearCalls()
        assertFalse(backend.triggerCandidateActionIfCurrent(-1, 0, false, backend.candidateActionContext))
        assertFalse(backend.triggerCandidateListTabActionIfCurrent(CandidateTabActionTarget(backend.candidateTabActionContext, -2)))
        assertTrue(native.actions.isEmpty())
        assertTrue(backend.triggerCandidateListTabActionIfCurrent(CandidateTabActionTarget(backend.candidateTabActionContext, -1)))
        assertEquals(listOf(7 to -1), native.actions)
    }
}
