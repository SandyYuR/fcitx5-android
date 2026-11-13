/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.RawInputState
import org.fcitx.directrime.Snapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class DirectRimeInteractionTargetsTest {
    private fun newSnapshot(
        generation: Long = 7,
        revision: Long = 11,
        tabsRevision: Long = 30,
        preedit: String = "ni hao",
        cursorBytes: Int = 4,
        gate: Int = 0,
    ) = Snapshot(generation, revision, 0, false, gate, "", preedit, "fixture", false,
        0, true, cursorBytes, arrayOf("你"), emptyArray(), arrayOf("同标签"), "Fixture",
        0, "你", arrayOf("1"), tabsRevision = tabsRevision)

    private inner class Fixture : DirectRimeInteractionTargets.Owner {
        val epoch = AtomicLong(7)
        override var acceptsInput = true
        override var snapshot: Snapshot? = newSnapshot()
        override var started = true
        override var maintenance = false
        override var candidateVersion = 20L
        override var preeditMode = "ComposingText"
        var displayedText = "ni hao"
        var epochReads = 0
        var clientReads = 0
        var afterEpochRead: (Int) -> Unit = {}
        var onCandidateContext: () -> Unit = {}
        var onTabContext: () -> Unit = {}
        var onRaw: (Snapshot) -> RawInputState? = { RawInputState("nihao", 3) }
        var onMove: (Int) -> Boolean = { true }
        val rawQueries = mutableListOf<Pair<Long, Long>>()
        val restores = mutableListOf<Pair<Long, String>>()
        val moves = mutableListOf<Int>()
        val targets = DirectRimeInteractionTargets(this)

        override val inputSession: Long get() {
            val value = epoch.get()
            afterEpochRead(++epochReads)
            return value
        }
        override val candidateActionContext: CandidateActionContext get() {
            onCandidateContext()
            return CandidateActionContext(inputSession, candidateVersion)
        }
        override val candidateTabActionContext: CandidateTabActionContext get() {
            onTabContext()
            return snapshot?.let { CandidateTabActionContext(it.generation, it.tabsRevision) }
                ?: CandidateTabActionContext(inputSession, 0)
        }
        override val clientPreeditText: String get() { clientReads++; return displayedText }
        override fun rawInput(value: Snapshot): RawInputState? {
            rawQueries += value.generation to value.revision
            return onRaw(value)
        }
        override fun restoreClientCursor(generation: Long) { restores += generation to displayedText }
        override fun moveRawCursor(target: Int): Boolean { moves += target; return onMove(target) }
    }

    @Test fun actionAdmissionUsesThePublishedCandidateVersionRatherThanNativeRevision() {
        val fixture = Fixture()
        val expected = fixture.snapshot!!
        assertTrue(fixture.targets.admitsAction(expected, CandidateActionContext(7, 20), null))
        assertFalse(fixture.targets.admitsAction(expected, CandidateActionContext(7, 11), null))
        fixture.candidateVersion = 21
        assertFalse(fixture.targets.actionStillCurrent(expected, CandidateActionContext(7, 20), null))
    }

    @Test fun nativeRevisionOnlyInvalidatesQueuedActionsAndNotTheCandidatePage() {
        val fixture = Fixture()
        val expected = fixture.snapshot!!
        fixture.snapshot = Snapshot.reusePresentation(expected, 12)
        assertFalse(fixture.targets.actionStillCurrent(expected, null, null))
        assertTrue(fixture.targets.candidatePageStillCurrent(expected, 20))
        fixture.candidateVersion = 21
        assertFalse(fixture.targets.candidatePageStillCurrent(expected, 20))
    }

    @Test fun identicalTabLabelsDoNotMakeAnOldTabRevisionCurrent() {
        val fixture = Fixture()
        val expected = fixture.snapshot!!
        val tab = CandidateTabActionContext(7, 30)
        assertTrue(fixture.targets.admitsAction(expected, null, tab))
        fixture.snapshot = newSnapshot(tabsRevision = 31)
        assertFalse(fixture.targets.admitsAction(expected, null, tab))
        assertFalse(fixture.targets.actionStillCurrent(expected, null, tab))
    }

    @Test fun anEditorChangeDuringTheContextReadCannotUseTheEarlierEpoch() {
        val fixture = Fixture()
        fixture.afterEpochRead = { if (it == 1) fixture.epoch.incrementAndGet() }
        assertFalse(fixture.targets.admitsAction(fixture.snapshot!!, CandidateActionContext(7, 20), null))
        assertEquals(2, fixture.epochReads)
    }

    @Test fun absentContextsAndAnAdmissionRejectionDoNotReadUnrequestedTargets() {
        val fixture = Fixture()
        fixture.onCandidateContext = { fail("No candidate target was requested") }
        fixture.onTabContext = { fail("No tab target was requested") }
        val expected = fixture.snapshot!!
        assertTrue(fixture.targets.admitsAction(expected, null, null))
        assertTrue(fixture.targets.actionStillCurrent(expected, null, null))
        fixture.acceptsInput = false
        val reads = fixture.epochReads
        assertFalse(fixture.targets.admitsAction(expected, CandidateActionContext(7, 20), CandidateTabActionContext(7, 30)))
        assertEquals(reads, fixture.epochReads)
    }

    @Test fun ownerCurrentnessRetainsTheFinalBackendActionGateAsASeparateCheck() {
        val fixture = Fixture()
        val expected = fixture.snapshot!!
        fixture.acceptsInput = false
        fixture.started = false
        fixture.maintenance = true
        assertTrue(fixture.targets.actionStillCurrent(expected, null, null))
        assertFalse(fixture.targets.candidatePageStillCurrent(expected, 20))
    }

    @Test fun aDifferentEditorStopsCandidatePagesRegardlessOfRevisionOrText() {
        val fixture = Fixture()
        fixture.epoch.incrementAndGet()
        assertFalse(fixture.targets.candidatePageStillCurrent(fixture.snapshot!!, 20))
        assertFalse(fixture.targets.actionStillCurrent(fixture.snapshot!!, null, null))
    }

    @Test fun cursorChecksBothLiveEpochReadsBeforeQueryingRawInput() {
        val fixture = Fixture()
        fixture.afterEpochRead = { if (it == 1) fixture.epoch.incrementAndGet() }
        assertFalse(fixture.targets.moveCursor(3, 7, "ni hao"))
        assertEquals(2, fixture.epochReads)
        assertTrue(fixture.rawQueries.isEmpty())
        assertTrue(fixture.restores.isEmpty())
    }

    @Test fun inactiveOrUnavailableCursorRequestsHaveNoRawOrDisplaySideEffects() {
        val fixtures = listOf(
            Fixture().apply { snapshot = null },
            Fixture().apply { started = false },
            Fixture().apply { maintenance = true },
            Fixture().apply { snapshot = newSnapshot(gate = 2) },
            Fixture().apply { epoch.incrementAndGet() },
        )
        for (fixture in fixtures) {
            assertFalse(fixture.targets.moveCursor(3, 7, "ni hao"))
            assertTrue(fixture.rawQueries.isEmpty())
            assertTrue(fixture.restores.isEmpty())
        }
    }

    @Test fun predictionModesRejectBeforeReadingClientTextOrRawInput() {
        for (mode in listOf("No", "CommitPreview")) {
            val fixture = Fixture().apply { preeditMode = mode }
            assertFalse(fixture.targets.moveCursor(3, 7, "ni hao"))
            assertEquals(0, fixture.clientReads)
            assertTrue(fixture.rawQueries.isEmpty())
            assertEquals(listOf(7L to "ni hao"), fixture.restores)
        }
    }

    @Test fun aStaleDisplayedPreeditRestoresWithoutReadingRawInput() {
        val fixture = Fixture()
        assertFalse(fixture.targets.moveCursor(3, 7, "old text"))
        assertTrue(fixture.rawQueries.isEmpty())
        assertEquals(listOf(7L to "ni hao"), fixture.restores)
    }

    @Test fun legacyCursorRequestsDoNotRequireAClientTextComparison() {
        val fixture = Fixture()
        assertTrue(fixture.targets.moveCursor(3, 7, null))
        assertEquals(0, fixture.clientReads)
        assertEquals(listOf(2), fixture.moves)
    }

    @Test fun supplementaryDisplayedCodePointsAreMappedToTheExistingRawUtf8Boundary() {
        val fixture = Fixture().apply {
            snapshot = newSnapshot(preedit = "汉🙂e", cursorBytes = 7)
            displayedText = "汉🙂e"
            onRaw = { RawInputState("汉🙂e", 7) }
        }
        assertTrue(fixture.targets.moveCursor(1, 7, "汉🙂e"))
        assertEquals(listOf(7L to 11L), fixture.rawQueries)
        assertEquals(listOf(3), fixture.moves)
        assertTrue(fixture.restores.isEmpty())
    }

    @Test fun invalidPositionsKeepTheOriginalRawReadThenRestoreOrder() {
        for (position in listOf(-1, 7)) {
            val fixture = Fixture()
            assertFalse(fixture.targets.moveCursor(position, 7, "ni hao"))
            assertEquals(listOf(7L to 11L), fixture.rawQueries)
            assertEquals(listOf(7L to "ni hao"), fixture.restores)
            assertTrue(fixture.moves.isEmpty())
        }
    }

    @Test fun anUnavailableRawSnapshotRestoresTheCurrentCacheWithTheCapturedGeneration() {
        val fixture = Fixture().apply {
            onRaw = { displayedText = "updated cache"; null }
        }
        assertFalse(fixture.targets.moveCursor(3, 7, "ni hao"))
        assertEquals(listOf(7L to "updated cache"), fixture.restores)
        assertTrue(fixture.moves.isEmpty())
    }

    @Test fun aTransformedInteriorIsNotGuessedAndASameCaretDoesNotRecompose() {
        val ambiguous = Fixture().apply {
            snapshot = newSnapshot(preedit = "你好", cursorBytes = 6)
            displayedText = "你好"
            onRaw = { RawInputState("nihao", 5) }
        }
        assertFalse(ambiguous.targets.moveCursor(1, 7, "你好"))
        assertEquals(listOf(7L to "你好"), ambiguous.restores)
        assertTrue(ambiguous.moves.isEmpty())
        val unchanged = Fixture()
        assertTrue(unchanged.targets.moveCursor(4, 7, "ni hao"))
        assertEquals(listOf(7L to "ni hao"), unchanged.restores)
        assertTrue(unchanged.moves.isEmpty())
    }

    @Test fun aRejectedCursorActionDoesNotAddADisplayRestore() {
        val fixture = Fixture().apply { onMove = { false } }
        assertFalse(fixture.targets.moveCursor(3, 7, "ni hao"))
        assertEquals(listOf(2), fixture.moves)
        assertTrue(fixture.restores.isEmpty())
    }

    @Test fun nativeReadAndMoveFailuresKeepTheirOriginalPropagationPolicy() {
        val readError = IOException("raw read")
        val read = Fixture().apply { onRaw = { throw readError } }
        assertSame(readError, assertThrows(IOException::class.java) { read.targets.moveCursor(3, 7, "ni hao") })
        assertTrue(read.restores.isEmpty())
        val moveError = IOException("caret action")
        val move = Fixture().apply { onMove = { throw moveError } }
        assertSame(moveError, assertThrows(IOException::class.java) { move.targets.moveCursor(3, 7, "ni hao") })
        assertTrue(move.restores.isEmpty())
    }
}
