/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class DirectRimeCandidateSequenceTest {
    private val wanxiang = arrayOf("Control+j", "Control+k", "Control+l", "Control+p")
    private fun evo(vararg bindings: String) = arrayOf("evo-editor-bindings-v1", *bindings)

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
        assertEquals("EVO keeps the Wanxiang Lua menu labels",
            actions.map { it.text }, DirectRimeCandidateSequence.actions(wanxiang, evo = true).map { it.text })
        assertEquals("${'p'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 1))
        assertEquals("${'j'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 2))
        assertEquals("${'k'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 3))
        assertEquals("${'l'.code} 4", DirectRimeCandidateSequence.keyArgument(wanxiang, 4))
        assertNull(DirectRimeCandidateSequence.keyArgument(wanxiang, 0))
        assertNull(DirectRimeCandidateSequence.keyArgument(wanxiang, 5))
    }

    @Test fun evoBindingsKeepOldSlotsAndEncodeNativeNavigationKeys() {
        val keys = DirectRimeCandidateSequence.resolveKeys(evo(
            "Control+Up", "manual_up", "Control+Down", "manual_down",
            "Shift+Delete", "manual_delete", "Control+Delete", "auto_delete",
        ), evo = true)
        assertArrayEquals(arrayOf("Control+Up", "Control+Down", "Shift+Delete", "", "Control+Delete"), keys)
        val actions = DirectRimeCandidateSequence.actions(keys, evo = true)
        assertEquals(listOf(2, 3, 4, 5), actions.map { it.id })
        assertEquals(listOf("前移一位", "后移一位", "重置手动排序", "重置自动调频"), actions.map { it.text })
        assertNull(DirectRimeCandidateSequence.keyArgument(keys, 1, evo = true))
        assertEquals("65362 4", DirectRimeCandidateSequence.keyArgument(keys, 2, evo = true))
        assertEquals("65364 4", DirectRimeCandidateSequence.keyArgument(keys, 3, evo = true))
        assertEquals("65535 1", DirectRimeCandidateSequence.keyArgument(keys, 4, evo = true))
        assertEquals("65535 4", DirectRimeCandidateSequence.keyArgument(keys, 5, evo = true))
    }

    @Test fun evoResetAndAutoDeleteDoNotRequireManualMovementBindings() {
        val reset = arrayOf("", "", "Shift+Delete", "", "")
        assertEquals(listOf(4), DirectRimeCandidateSequence.actions(reset, evo = true).map { it.id })
        val autoDelete = arrayOf("", "", "", "", "Control+Delete")
        assertEquals(listOf(5), DirectRimeCandidateSequence.actions(autoDelete, evo = true).map { it.id })
        assertTrue(DirectRimeCandidateSequence.actions(autoDelete).isEmpty())
        assertNull(DirectRimeCandidateSequence.keyArgument(autoDelete, 5))
        assertNull(DirectRimeCandidateSequence.keyArgument(wanxiang, 5, evo = true))
    }

    @Test fun evoRawProtocolRequiresTagAndCompletePairsWhileLuaIsUnchanged() {
        for (family in listOf(false, true))
            assertSame(wanxiang, DirectRimeCandidateSequence.resolveKeys(wanxiang, family))
        for (raw in listOf(emptyArray(), evo(), evo("Control+Up"), evo("Control+Up", "manual_up", "orphan"),
            arrayOf("Control+Up", "Control+Down", "Shift+Delete", "", "Control+Delete")))
            assertTrue(DirectRimeCandidateSequence.resolveKeys(raw, evo = true).isEmpty())
        assertTrue(DirectRimeCandidateSequence.resolveKeys(evo("Control+Up", "manual_up"), evo = false).isEmpty())
    }

    @Test fun evoStrictEncodingNeverDropsUnsupportedModifiersOrPrefixes() {
        val unsupported = listOf("Lock", "Mod2", "Mod3", "Mod4", "Mod5", "Super", "Hyper", "Meta",
            "Release", "Button1", "Button2", "Button3", "Button4", "Button5", "Ctrl")
            .map { "Control+$it+Up" } + listOf(
                " Control+Up", "Control+Up ", "prefixControl+Up", "CTRL_Up", "Control+Unknown+Up",
                "Control++", "Control+!", "Control+0xff52", "0xff52", "中", "Control+中",
                "Control+ENTER", "Control+DELETE", "Control+PGUP", "Control+SunPageUp", "Control+XF86AudioMute",
            )
        for (raw in unsupported) {
            assertTrue(raw, DirectRimeCandidateSequence.resolveKeys(evo(raw, "manual_up"), evo = true).isEmpty())
            val keys = arrayOf(raw, "", "", "", "")
            assertTrue(raw, DirectRimeCandidateSequence.actions(keys, evo = true).isEmpty())
            assertNull(raw, DirectRimeCandidateSequence.keyArgument(keys, 2, evo = true))
        }
        // Existing application/Lua syntax remains permissive and independent of EVO validation.
        assertEquals("65362 4", DirectRimeCandidateSequence.encode("prefixControl+Up"))
        assertEquals("65362 4", DirectRimeCandidateSequence.encode(" CTRL_Up "))
    }

    @Test fun evoExactLookupKeepsIsoLeftTabAndEverySupportedModifierCombination() {
        assertEquals(0xfe20, KeyCodec.exactKeysym("ISO_Left_Tab"))
        assertNull(KeyCodec.exactKeysym("Control+Up"))
        assertNull(KeyCodec.exactKeysym("0xff52"))
        for (mask in listOf(0, 1, 4, 5, 8, 9, 12, 13)) {
            val prefix = listOf("Control" to 4, "Alt" to 8, "Shift" to 1)
                .filter { mask and it.second != 0 }.joinToString("") { "${it.first}+" }
            val keys = DirectRimeCandidateSequence.resolveKeys(evo(prefix + "ISO_Left_Tab", "manual_up"), evo = true)
            assertEquals("65056 $mask", DirectRimeCandidateSequence.keyArgument(keys, 2, evo = true))
        }
        for ((raw, sym) in listOf("+" to 43, " " to 32, "!" to 33, "a" to 97, "Control+exclam" to 33)) {
            val keys = DirectRimeCandidateSequence.resolveKeys(evo(raw, "manual_up"), evo = true)
            assertEquals("$sym ${if (raw.startsWith("Control+")) 4 else 0}",
                DirectRimeCandidateSequence.keyArgument(keys, 2, evo = true))
        }
    }

    @Test fun evoCollisionsIncludeAliasesModifierOrderAndEveryDeclaredAction() {
        val aliases = listOf(
            "Control+Shift+Prior" to "Shift+Control+Page_Up",
            "Control+Next" to "Control+Page_Down",
            "Control+Control+Up" to "Control+Up",
            "+" to "plus",
            "Return" to "ENTER",
        )
        for ((first, second) in aliases) for (action in listOf("manual_up", "manual_down", "manual_delete", "auto_delete", "noop", "unknown", "")) {
            val keys = DirectRimeCandidateSequence.resolveKeys(evo(first, "manual_up", second, action), evo = true)
            assertTrue("$first / $second / $action", keys.isEmpty())
        }
    }

    @Test fun evoInvalidAndCollidingBindingsLeaveTheFirstUniqueFallbackAvailable() {
        val keys = DirectRimeCandidateSequence.resolveKeys(evo(
            "Control+Mod3+Up", "manual_up",
            "Control+Prior", "manual_up", "Control+Page_Up", "noop",
            "Control+u", "manual_up", "Control+v", "manual_up",
            "Control+Down", "manual_down", "Shift+Delete", "manual_delete",
            "Control+Delete", "auto_delete", "Control+p", "pin",
        ), evo = true)
        assertArrayEquals(arrayOf("Control+u", "Control+Down", "Shift+Delete", "", "Control+Delete"), keys)
        assertEquals("117 4", DirectRimeCandidateSequence.keyArgument(keys, 2, evo = true))
        assertNull(DirectRimeCandidateSequence.keyArgument(arrayOf("", "", "", "Control+p", ""), 1, evo = true))
        assertTrue(DirectRimeCandidateSequence.actions(arrayOf("", "", "", "Control+p", ""), evo = true).isEmpty())
    }

    @Test fun customisedAndUndeliverableKeys() {
        val custom = arrayOf("Control+Alt+bracketleft", "not-a-key", "Control+l", "Control+p")
        assertEquals("${0x5b} 12", DirectRimeCandidateSequence.keyArgument(custom, 2))
        assertNull(DirectRimeCandidateSequence.keyArgument(custom, 3))
        assertEquals(listOf(1, 2, 4), DirectRimeCandidateSequence.actions(custom).map { it.id })
    }
}
