/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the manual numeric override lifecycle around layer switches.
 *
 * Regression scenario (QQ send button): the user opens the custom number pad with the
 * "?123" layout switch key (activateManual), leaves it with a latching "layer to" macro
 * key, types, then the app's send button clears the editor and restarts input in place.
 * The restart calls force(null); while the manual slot stayed latched that fallback
 * resurrected the number pad on top of the text layer the user had switched to.
 */
class NumericLayoutOverrideControllerTest {

    @Test
    fun manualOverrideSurvivesForceWithoutLayer() {
        val controller = NumericLayoutOverrideController()

        assertTrue(controller.activateManual("数字"))
        assertEquals("数字", controller.manualKey)
        assertTrue(controller.isManualNumericShowing())

        // A forced-layout clear without a layer of its own falls back to the manual
        // numeric layout: this is how an in-place input restart preserves a hand-picked
        // number pad (the Alipay restart case documented on shouldKeepCurrentLayoutOnStartInput).
        controller.force(null)
        assertEquals("数字", controller.forcedKey)
        assertTrue(controller.isManualNumericShowing())
    }

    @Test
    fun layerSwitchAwayFromManualDropsIt() {
        val controller = NumericLayoutOverrideController()

        assertTrue(controller.activateManual("数字"))
        // User leaves the number pad via a latching "layer to" macro key.
        assertTrue(controller.releaseManualOnLayerSwitch("rime"))

        assertFalse(controller.manual)
        assertNull(controller.manualKey)
        // The later forced-layout clear (input restart / IME update) must not
        // resurrect the number pad the user already left.
        controller.force(null)
        assertNull(controller.forcedKey)
        assertFalse(controller.isManualNumericShowing())
    }

    @Test
    fun layerSwitchToTheSameNumericLayerKeepsIt() {
        val controller = NumericLayoutOverrideController()

        assertTrue(controller.activateManual("数字"))
        // Re-affirming the numeric layer via a layer key is not a departure.
        assertFalse(controller.releaseManualOnLayerSwitch("数字"))
        assertEquals("数字", controller.manualKey)
        assertTrue(controller.isManualNumericShowing())
    }

    @Test
    fun layerSwitchWithoutManualOverrideIsANoOp() {
        val controller = NumericLayoutOverrideController()

        assertFalse(controller.releaseManualOnLayerSwitch("rime"))
        assertFalse(controller.manual)
        assertNull(controller.manualKey)
    }

    @Test
    fun sessionOverrideSurvivesLayerSwitch() {
        val controller = NumericLayoutOverrideController()

        // Numeric editor: the session override owns the number pad for this field.
        controller.beginSession("数字")
        assertEquals("数字", controller.sessionKey)

        // The user peeks at another layer with a latching layer key. There is no
        // manual override to release, and the session slot must stay intact.
        assertFalse(controller.releaseManualOnLayerSwitch("符号"))
        assertEquals("数字", controller.sessionKey)

        // Clearing the layer latch falls back to the session override, keeping a
        // numeric editor on its number pad across layer peeks and input restarts.
        controller.force(null)
        assertEquals("数字", controller.forcedKey)
    }

    @Test
    fun backLayerSwitchDropsManualOverrideToo() {
        val controller = NumericLayoutOverrideController()

        assertTrue(controller.activateManual("数字"))
        // BACK pops the layer history; latching back to a remembered layer is an
        // explicit departure just like "layer to".
        assertTrue(controller.releaseManualOnLayerSwitch("符号"))
        controller.force(null)
        assertNull(controller.forcedKey)
    }

    @Test
    fun dismissBlocksImplicitButNotExplicitReactivation() {
        val controller = NumericLayoutOverrideController()

        // Numeric editor opens its custom number pad via the session slot.
        controller.beginSession("数字")
        assertEquals("数字", controller.sessionKey)

        // User switches to the text keyboard ("ABC" key) -> dismiss.
        controller.dismiss()
        assertTrue(controller.dismissed)
        assertNull(controller.sessionKey)
        assertNull(controller.forcedKey)

        // An implicit re-activation (force fallback / IME update path) stays blocked:
        // the number pad must not resurrect itself after the user left it.
        assertFalse(controller.activateManual("数字"))
        assertTrue(controller.dismissed)
        assertNull(controller.forcedKey)

        // But an explicit user "?123" press re-arms the override and shows the
        // custom number pad again (regression: it used to fall back to the
        // built-in number keyboard after a text<->number round trip).
        assertTrue(controller.activateManual("数字", userRequested = true))
        assertFalse(controller.dismissed)
        assertEquals("数字", controller.manualKey)
        assertEquals("数字", controller.forcedKey)
        assertTrue(controller.isManualNumericShowing())
    }
}
