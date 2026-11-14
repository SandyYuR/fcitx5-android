/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.broadcast

import org.fcitx.fcitx5.android.core.CandidateActionContext
import org.fcitx.fcitx5.android.core.CandidateSelectionTarget
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fcitx.fcitx5.android.input.dependency.InputUniqueComponent
import org.junit.Assert.*
import org.junit.Test
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.plusAssign

class CandidateBroadcastTest {
    private class Paged : InputUniqueComponent<Paged>(Paged::class), PagedCandidateReceiver {
        val events = mutableListOf<Any>()
        override fun onCandidateUpdate(data: CandidateListEvent.Data) { events += data }
        override fun onPagedCandidateUpdate(data: PagedCandidateEvent.Data) { events += data }
    }
    private class ListOnly : InputUniqueComponent<ListOnly>(ListOnly::class), InputBroadcastReceiver {
        val events = mutableListOf<CandidateListEvent.Data>()
        override fun onCandidateUpdate(data: CandidateListEvent.Data) { events += data }
    }
    private class AnotherList : InputUniqueComponent<AnotherList>(AnotherList::class), InputBroadcastReceiver {
        var last: CandidateListEvent.Data? = null
        override fun onCandidateUpdate(data: CandidateListEvent.Data) { last = data }
    }
    private class Fixture {
        val broadcaster = InputBroadcaster()
        val paged = Paged()
        val list = ListOnly()
        val another = AnotherList()
        val scope = DynamicScope().apply {
            this += broadcaster; this += paged; this += list; this += another
        }
    }
    private fun page(version: Long = 1) = PagedCandidateEvent.Data(
        arrayOf(CandidateWord("k", "词", "注释").apply {
            selectionTarget = CandidateSelectionTarget(CandidateActionContext(7, version), 9, false)
        }), 0, PagedCandidateEvent.LayoutHint.NotSet, true, true
    )

    @Test fun pageHasOneDeliveryPerReceiverWithCompleteSelectionMetadata() {
        val f = Fixture()
        val page = page()
        f.broadcaster.onPagedCandidateUpdate(page)
        assertEquals(1, f.paged.events.size)
        assertSame(page, f.paged.events.single())
        assertEquals(1, f.list.events.size)
        assertSame(page.candidates, f.list.events.single().candidates)
        assertEquals(-1, f.list.events.single().total)
        assertSame(f.list.events.single(), f.another.last)
        assertEquals(page.candidates[0].selectionTarget, f.another.last!!.candidates[0].selectionTarget)
    }

    @Test fun switchingToAListAndBackHasNoTimeBasedSuppressionOrSyntheticHighlight() {
        val f = Fixture()
        val first = page()
        val list = CandidateListEvent.Data(4, arrayOf(CandidateWord("1", "新词", "")))
        val second = page(2)
        f.broadcaster.onPagedCandidateUpdate(first)
        f.broadcaster.onCandidateUpdate(list)
        f.broadcaster.onPagedCandidateUpdate(second)
        assertEquals(listOf(first, list, second), f.paged.events)
        assertEquals(3, f.list.events.size)
        assertSame(list, f.list.events[1])
        assertSame(second.candidates, f.list.events[2].candidates)
    }

    @Test fun emptyPageClearsBothPagingAndListOnlyComponents() {
        val f = Fixture()
        f.broadcaster.onPagedCandidateUpdate(page())
        f.broadcaster.onPagedCandidateUpdate(PagedCandidateEvent.Data.Empty)
        assertEquals(2, f.paged.events.size)
        assertSame(PagedCandidateEvent.Data.Empty, f.paged.events.last())
        assertTrue(f.list.events.last().candidates.isEmpty())
    }

    @Test fun identicalPresentationStillDeliversTheNewSelectionVersion() {
        val f = Fixture()
        val old = page(1)
        val fresh = page(2)
        assertEquals(old, fresh)
        f.broadcaster.onPagedCandidateUpdate(old)
        f.broadcaster.onPagedCandidateUpdate(fresh)
        assertSame(fresh, f.paged.events.last())
        assertSame(fresh.candidates, f.list.events.last().candidates)
        assertEquals(2, f.list.events.size)
    }
}
