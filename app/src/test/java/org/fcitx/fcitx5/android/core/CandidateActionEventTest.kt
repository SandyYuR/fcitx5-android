/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CandidateActionEventTest {
    @Test fun presentationEqualityDoesNotDependOnMenuContext() {
        val first = FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data()).apply {
            candidateActionContext = CandidateActionContext(1, 2)
        }
        val second = first.copy().apply { candidateActionContext = CandidateActionContext(1, 3) }
        assertEquals(first, second)
        assertNotEquals(first.candidateActionContext, second.candidateActionContext)
    }

    @Test fun coalescingKeepsTheLatestCandidatesOwnContext() = runBlocking {
        val queue = InputEventQueue()
        val older = FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data()).apply {
            candidateActionContext = CandidateActionContext(1, 2)
        }
        val latest = older.copy().apply { candidateActionContext = CandidateActionContext(1, 3) }
        queue.offer(listOf(older))
        queue.offer(listOf(latest))
        assertSame(latest, queue.receive().single())
        assertEquals(CandidateActionContext(1, 3), latest.candidateActionContext)
    }
}
