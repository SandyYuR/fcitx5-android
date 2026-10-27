package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.*
import org.junit.Test

class SessionBoundVoiceFinishTest {
    @Test fun aDrainedOldRequestCannotEndOrArmANewerSession() {
        var generation = 1
        val events = mutableListOf<String>()
        val request = SessionBoundVoiceFinish({ generation == 1 }, { events += "end" },
            { events += "watchdog" }, { events += "error" })
        generation = 2
        request.complete()
        assertTrue(events.isEmpty())
    }
    @Test fun normalFinishEndsBeforeArmingTheWatchdog() {
        val events = mutableListOf<String>()
        SessionBoundVoiceFinish({ true }, { events += "end" }, { events += "watchdog" },
            { fail("unexpected failure") }).complete()
        assertEquals(listOf("end", "watchdog"), events)
    }
    @Test fun synchronousSessionEndDoesNotRearmTheWatchdog() {
        var current = true
        SessionBoundVoiceFinish({ current }, { current = false }, { fail("armed after end") },
            { fail("unexpected failure") }).complete()
    }
    @Test fun endFailureTearsDownOnlyTheCurrentSession() {
        var failures = 0
        SessionBoundVoiceFinish({ true }, { throw IllegalStateException("provider died") },
            { fail("armed after failure") }, { failures++ }).complete()
        assertEquals(1, failures)
    }
    @Test fun failureFromAnOldProviderCannotStopItsReplacement() {
        var current = true
        SessionBoundVoiceFinish({ current }, { current = false; throw IllegalStateException("late failure") },
            { fail("armed for replacement") }, { fail("stopped replacement") }).complete()
    }
}
