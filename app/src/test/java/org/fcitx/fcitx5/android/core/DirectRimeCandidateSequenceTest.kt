/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class DirectRimeCandidateSequenceTest {
    private val wanxiang = arrayOf("Control+j", "Control+k", "Control+l", "Control+p")

    @Test fun unsupportedSchemasOnlyKeepForget() {
        assertTrue(DirectRimeCandidateSequence.actions(emptyArray()).isEmpty())
        assertTrue(DirectRimeCandidateSequence.actions(arrayOf("Control+j")).isEmpty())
        assertNull(DirectRimeCandidateSequence.keyArgument(emptyArray(), 1))
        assertEquals(0, DirectRimeCandidateSequence.forgetAction.id)
    }

    @Test fun onlyWanxiangSchemasAreEligible() {
        for (schema in listOf("wanxiang", "wanxiang_t9", "wanxiang_english", "wanxiangpro"))
            assertTrue(schema, DirectRimeCandidateSequence.isWanxiangSchema(schema))
        for (schema in listOf("", "rime_ice", "luna_pinyin", "my_wanxiang", "Wanxiang", " wanxiang"))
            assertFalse(schema, DirectRimeCandidateSequence.isWanxiangSchema(schema))
    }

    @Test fun menuOrderAndSchemaKeysFollowSuperSequence() {
        val actions = DirectRimeCandidateSequence.actions(wanxiang)
        assertEquals(listOf(1, 2, 3, 4), actions.map { it.id })
        assertEquals(listOf("置顶", "前移一位", "后移一位", "重置排序"), actions.map { it.text })
        assertEquals("${'p'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 1))
        assertEquals("${'j'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 2))
        assertEquals("${'k'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 3))
        assertEquals("${'l'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 4))
        assertNull(DirectRimeCandidateSequence.keyArgument(wanxiang, 0))
        assertNull(DirectRimeCandidateSequence.keyArgument(wanxiang, 5))
    }

    @Test fun customisedAndUndeliverableKeys() {
        val custom = arrayOf("Control+Alt+bracketleft", "not-a-key", "Control+l", "Control+p")
        assertEquals("${0x5b} 12", DirectRimeCandidateSequence.keyArgument(custom, 2))
        assertNull(DirectRimeCandidateSequence.keyArgument(custom, 3))
        assertEquals(listOf(1, 2, 4), DirectRimeCandidateSequence.actions(custom).map { it.id })
    }
}
