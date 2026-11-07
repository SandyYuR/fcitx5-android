/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

import org.junit.Assert.*
import org.junit.Test

class StatsActorScheduleTest {
    @Test fun disabledOrCleanIdleActorsWaitOnlyForCommands() {
        assertEquals(StatsActorWakeup(null), StatsActorSchedule.next(20_000, false, null, null))
        assertEquals(StatsActorWakeup(null), StatsActorSchedule.next(20_000, false, 0, 0))
        assertEquals(StatsActorWakeup(null), StatsActorSchedule.next(20_000, true, null, null))
    }

    @Test fun firstDirtyTimeFixesTheDeadlineInsteadOfDebouncingEachEvent() {
        assertEquals(StatsActorWakeup(1000), StatsActorSchedule.next(100, true, 100, null))
        assertEquals(StatsActorWakeup(250), StatsActorSchedule.next(850, true, 100, null))
        assertEquals(StatsActorWakeup(1), StatsActorSchedule.next(1099, true, 100, null))
        assertEquals(StatsActorWakeup(0, flush = true), StatsActorSchedule.next(1100, true, 100, null))
        assertEquals(StatsActorWakeup(0, flush = true), StatsActorSchedule.next(2000, true, 100, null))
    }

    @Test fun unfinishedCompositionHasNoIdleDeadlineAfterItsDirtyDataWasFlushed() {
        // A composing session supplies no idleSessionSince; it retains its timing until input resumes.
        assertEquals(StatsActorWakeup(1000), StatsActorSchedule.next(100, true, 100, null))
        assertEquals(StatsActorWakeup(null), StatsActorSchedule.next(1100, true, null, null))
        assertEquals(StatsActorWakeup(null), StatsActorSchedule.next(60_000, true, null, null))
    }

    @Test fun aNonComposingSessionHasOneStrictFiveSecondCompletionDeadline() {
        assertEquals(StatsActorWakeup(5001), StatsActorSchedule.next(100, true, null, 100))
        assertEquals(StatsActorWakeup(1), StatsActorSchedule.next(5100, true, null, 100))
        assertEquals(StatsActorWakeup(0, finishSession = true), StatsActorSchedule.next(5101, true, null, 100))
        // Completion flushes its sample and removes the session, leaving no further timer.
        assertEquals(StatsActorWakeup(null), StatsActorSchedule.next(5101, true, null, null))
    }

    @Test fun pendingWritesAndSessionCompletionShareTheEarliestRequiredWakeup() {
        assertEquals(StatsActorWakeup(1000), StatsActorSchedule.next(100, true, 100, 100))
        assertEquals(StatsActorWakeup(4001), StatsActorSchedule.next(1100, true, null, 100))
        assertEquals(StatsActorWakeup(101), StatsActorSchedule.next(5000, true, 4500, 100))
        assertEquals(StatsActorWakeup(0, flush = true, finishSession = true), StatsActorSchedule.next(5500, true, 4500, 100))
    }

    @Test fun delayedProcessingAndVeryLargeClockValuesDoNotOverflowTheWait() {
        assertEquals(StatsActorWakeup(0, flush = true, finishSession = true), StatsActorSchedule.next(60_000, true, 100, 100))
        assertEquals(StatsActorWakeup(100), StatsActorSchedule.next(Long.MAX_VALUE - 100, true, Long.MAX_VALUE - 100, null))
        assertEquals(StatsActorWakeup(0, flush = true), StatsActorSchedule.next(Long.MAX_VALUE, true, Long.MAX_VALUE - 100, null))
    }

    @Test fun readyInputCommandsPrecedeIdleCompletionWithoutStarvingDurableWrites() {
        assertEquals(StatsActorWakeup(null), StatsActorSchedule.next(6000, true, null, 0, commandReady = true))
        assertEquals(StatsActorWakeup(0, flush = true), StatsActorSchedule.next(6000, true, 0, 0, commandReady = true))
        assertEquals(StatsActorWakeup(0, finishSession = true), StatsActorSchedule.next(6000, true, null, 0, commandReady = false))
    }
}
