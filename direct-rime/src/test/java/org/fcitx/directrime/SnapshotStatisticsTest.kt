package org.fcitx.directrime

import org.junit.Assert.*
import org.junit.Test

class SnapshotStatisticsTest {
    private fun snapshot(packed: Long = -1L, commit: String = "") = Snapshot(
        1, 10, 0, false, 0, commit, "ni", "test", false, 0, true, 2,
        arrayOf("你"), arrayOf(""), emptyArray(), "Test", 0, "你", arrayOf("1"), packed
    )

    @Test fun packedStatisticsDecodeWithoutKeepingTheRawText() {
        assertEquals(-1, snapshot().rawInputLength)
        assertFalse(snapshot().inputWasCommand)
        assertFalse(snapshot().inputWasComposing)
        assertEquals(0, snapshot(0).rawInputLength)
        assertFalse(snapshot(0).inputWasComposing)
        assertEquals(4, snapshot(8).rawInputLength)
        assertTrue(snapshot(8).inputWasComposing)
        assertFalse(snapshot(8).inputWasCommand)
        assertEquals(4, snapshot(9).rawInputLength)
        assertTrue(snapshot(9).inputWasCommand)
        assertEquals(Int.MAX_VALUE - 1, snapshot((Int.MAX_VALUE.toLong() - 1) shl 1).rawInputLength)
        assertEquals(-1, snapshot(Int.MAX_VALUE.toLong() shl 1).rawInputLength)
        assertEquals(-1, snapshot(Long.MAX_VALUE).rawInputLength)
    }

    @Test fun reusedPresentationAlwaysGetsThisOperationsStatisticsAndNoOldCommit() {
        val previous = snapshot(9, "already consumed")
        val current = Snapshot.reusePresentation(previous, 11, 4)
        assertEquals(9L, previous.inputStatistics)
        assertEquals("already consumed", previous.commit)
        assertEquals(4L, current.inputStatistics)
        assertEquals(2, current.rawInputLength)
        assertFalse(current.inputWasCommand)
        assertTrue(current.commit.isEmpty())
        assertSame(previous.candidates, current.candidates)
        assertTrue(current.isUnchangedRelease(previous))
        val disabled = Snapshot.reusePresentation(current, 12)
        assertEquals(-1L, disabled.inputStatistics)
        assertSame(current.candidates, disabled.candidates)
    }
}
