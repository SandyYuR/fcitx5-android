/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.Snapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRimePresentationTest {
    private fun snapshot(
        generation: Long = 7,
        gate: Int = 0,
        preedit: String = "a😀b",
        cursorBytes: Int = 5,
        candidates: Array<String> = arrayOf("你", "好"),
        comments: Array<String> = arrayOf("nǐ"),
        tabs: Array<String> = arrayOf("同标签", "次标签"),
        labels: Array<String> = arrayOf("甲 "),
        page: Int = 2,
        lastPage: Boolean = false,
        highlighted: Int = 1,
        preview: String = "预览",
        tabsRevision: Long = 30,
    ) = Snapshot(
        generation,
        11,
        5,
        false,
        gate,
        "",
        preedit,
        "fixture",
        false,
        page,
        lastPage,
        cursorBytes,
        candidates,
        comments,
        tabs,
        "Fixture",
        highlighted,
        preview,
        labels,
        tabsRevision = tabsRevision,
    )

    @Test fun mapsPreeditModesWithoutHoldingPresentationState() {
        val value = snapshot()
        val composing = DirectRimePresentation.clientPreedit(value, "ComposingText")
        assertEquals("a😀b", composing.toString())
        assertEquals(3, composing.cursor)
        assertArrayEquals(intArrayOf(TextFormatFlag.Underline.flag), composing.flags)

        val preview = DirectRimePresentation.clientPreedit(value, "CommitPreview")
        assertEquals("预览", preview.toString())
        assertEquals(2, preview.cursor)
        assertArrayEquals(intArrayOf(TextFormatFlag.Underline.flag), preview.flags)

        assertSame(FormattedText.Empty, DirectRimePresentation.clientPreedit(value, "No"))
        assertSame(FormattedText.Empty, DirectRimePresentation.clientPreedit(value, "unknown"))
    }

    @Test fun mapsPanelTextAndVersionedTabTargets() {
        val value = snapshot()
        val panel = DirectRimePresentation.inputPanel(
            value,
            "No",
            directInput = false,
            bindingUsable = true,
            statusHint = "状态提示",
            startupError = "",
            statusHintId = 44,
        )

        assertEquals("a😀b", panel.preedit.toString())
        assertEquals(3, panel.preedit.cursor)
        assertEquals("状态提示", panel.auxUp.toString())
        assertEquals(44, panel.statusHintId)
        assertEquals(listOf("同标签", "次标签"), panel.tabs.map { it.text })
        panel.tabs.forEachIndexed { index, tab ->
            assertEquals(CandidateTabActionTarget(CandidateTabActionContext(7, 30), index), tab.tabTarget)
        }

        val newer = DirectRimePresentation.inputPanel(
            snapshot(tabsRevision = 31),
            "ComposingText",
            directInput = false,
            bindingUsable = true,
            statusHint = "状态提示",
            startupError = "",
            statusHintId = 45,
        )
        assertSame(FormattedText.Empty, newer.preedit)
        assertEquals(31, newer.tabs[0].tabTarget!!.context.version)
        assertFalse(panel.tabs.contentEquals(newer.tabs))
    }

    @Test fun appliesDirectInputAndUnavailableAuxiliaryPolicies() {
        val direct = DirectRimePresentation.inputPanel(
            snapshot(),
            "No",
            directInput = true,
            bindingUsable = true,
            statusHint = "不应显示",
            startupError = "",
            statusHintId = 4,
        )
        assertSame(FormattedText.Empty, direct.auxUp)

        val blocked = DirectRimePresentation.inputPanel(
            snapshot(gate = 2),
            "No",
            directInput = true,
            bindingUsable = false,
            statusHint = "不应显示",
            startupError = "准备失败",
            statusHintId = 9,
        )
        assertEquals("准备失败", blocked.auxUp.toString())
        assertEquals(0, blocked.statusHintId)

        val gateMessage = DirectRimePresentation.inputPanel(
            snapshot(gate = 2),
            "No",
            directInput = false,
            bindingUsable = false,
            statusHint = "",
            startupError = "",
            statusHintId = 9,
        )
        assertEquals("无可用方案，请导入方案并部署", gateMessage.auxUp.toString())
    }

    @Test fun reusesTheEmptyTabArrayWithoutCachingNonEmptyTabs() {
        val first = DirectRimePresentation.inputPanel(
            snapshot(tabs = emptyArray()), "No", false, true, "", "", 0
        )
        val second = DirectRimePresentation.inputPanel(
            snapshot(tabs = emptyArray()), "No", false, true, "", "", 0
        )
        assertSame(first.tabs, second.tabs)
    }

    @Test fun mapsCandidateMetadataWithoutBindingSelectionVersions() {
        val value = snapshot()
        val candidates = DirectRimePresentation.candidates(value)
        assertEquals(2, candidates.size)
        assertEquals("甲 ", candidates[0].label)
        assertEquals("你", candidates[0].text)
        assertEquals("nǐ", candidates[0].comment)
        assertEquals("2 ", candidates[1].label)
        assertEquals("好", candidates[1].text)
        assertEquals("", candidates[1].comment)
        assertTrue(candidates.all { it.selectionTarget == null })

        val paged = DirectRimePresentation.candidateEvent(value, 1, candidates) as FcitxEvent.PagedCandidateEvent
        assertSame(candidates, paged.data.candidates)
        assertEquals(1, paged.data.cursorIndex)
        assertTrue(paged.data.hasPrev)
        assertTrue(paged.data.hasNext)
        assertEquals(FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, paged.data.layoutHint)
        assertNull(paged.candidateActionContext)

        val listed = DirectRimePresentation.candidateEvent(value, 0, candidates) as FcitxEvent.CandidateListEvent
        assertSame(candidates, listed.data.candidates)
        assertEquals(-1, listed.data.total)
        assertNull(listed.candidateActionContext)
    }
}
