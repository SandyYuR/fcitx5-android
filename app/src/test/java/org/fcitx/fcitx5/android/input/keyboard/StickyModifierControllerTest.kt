/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyModifierControllerTest {
    private fun tap(
        controller: StickyModifierController,
        modifier: StickyModifier,
        clickEligible: Boolean = true,
        cancelled: Boolean = false
    ) {
        val gesture = controller.onDown(modifier)
        assertNotNull(gesture)
        controller.onUp(modifier, gesture, clickEligible, cancelled)
    }

    @Test
    fun tapsLatchEveryModifierAndConsumptionKeepsActivationOrder() {
        val controller = StickyModifierController()
        tap(controller, StickyModifier.Alt)
        tap(controller, StickyModifier.Ctrl)
        tap(controller, StickyModifier.Shift)

        assertTrue(controller.isLatched(StickyModifier.Alt))
        assertTrue(controller.isLatched(StickyModifier.Ctrl))
        assertTrue(controller.isLatched(StickyModifier.Shift))
        val consumption = controller.consume()
        assertEquals(
            listOf(StickyModifier.Alt, StickyModifier.Ctrl, StickyModifier.Shift),
            consumption?.modifiers
        )
        assertFalse(controller.hasActiveModifiers)
        assertNull(controller.consume())
        controller.finish(consumption!!)
        assertNull(controller.consume())
    }

    @Test
    fun heldAndLatchedModifiersAreConsumedTogetherOnlyOnce() {
        val controller = StickyModifierController()
        val ctrlGesture = controller.onDown(StickyModifier.Ctrl)
        tap(controller, StickyModifier.Shift)

        val consumption = controller.consume()
        assertEquals(listOf(StickyModifier.Ctrl, StickyModifier.Shift), consumption?.modifiers)
        controller.onUp(StickyModifier.Ctrl, ctrlGesture, clickEligible = true, cancelled = false)
        assertFalse(controller.hasActiveModifiers)
        controller.finish(consumption!!)
        assertNull(controller.consume())
    }

    @Test
    fun lateUpCannotFinishAReplacementGesture() {
        val controller = StickyModifierController()
        val oldGesture = controller.onDown(StickyModifier.Ctrl)
        val consumption = controller.consume()!!
        controller.finish(consumption)
        val newGesture = controller.onDown(StickyModifier.Ctrl)

        controller.onUp(StickyModifier.Ctrl, oldGesture, clickEligible = true, cancelled = false)
        assertTrue(controller.hasActiveModifiers)
        assertFalse(controller.isLatched(StickyModifier.Ctrl))
        controller.onUp(StickyModifier.Ctrl, newGesture, clickEligible = true, cancelled = false)
        assertTrue(controller.isLatched(StickyModifier.Ctrl))
    }

    @Test
    fun duplicatePhysicalDownDoesNotAddAnotherSlot() {
        val controller = StickyModifierController()
        val gesture = controller.onDown(StickyModifier.Alt)
        assertNotNull(gesture)
        assertNull(controller.onDown(StickyModifier.Alt))
        assertEquals(listOf(StickyModifier.Alt), controller.consume()?.modifiers)
    }

    @Test
    fun secondEligibleTapCancelsOnlyTheSameModifierFamily() {
        val controller = StickyModifierController()
        tap(controller, StickyModifier.Ctrl)
        tap(controller, StickyModifier.Shift)
        tap(controller, StickyModifier.Ctrl)

        assertFalse(controller.hasActiveModifiers && controller.isLatched(StickyModifier.Ctrl))
        assertTrue(controller.isLatched(StickyModifier.Shift))
        assertEquals(listOf(StickyModifier.Shift), controller.consume()?.modifiers)
    }

    @Test
    fun cancelledOrIneligibleGesturesDoNotLatch() {
        val controller = StickyModifierController()
        tap(controller, StickyModifier.Ctrl, clickEligible = false)
        tap(controller, StickyModifier.Alt, cancelled = true)
        tap(controller, StickyModifier.Shift)

        assertFalse(controller.isLatched(StickyModifier.Ctrl))
        assertFalse(controller.isLatched(StickyModifier.Alt))
        assertTrue(controller.isLatched(StickyModifier.Shift))
    }

    @Test
    fun cancelledSecondTapPreservesExistingLatch() {
        val controller = StickyModifierController()
        tap(controller, StickyModifier.Ctrl)
        tap(controller, StickyModifier.Ctrl, cancelled = true)
        assertTrue(controller.isLatched(StickyModifier.Ctrl))

        tap(controller, StickyModifier.Ctrl, clickEligible = false)
        assertTrue(controller.isLatched(StickyModifier.Ctrl))
    }

    @Test
    fun cancelAllIsIdempotentAndClearsEveryState() {
        val controller = StickyModifierController()
        var notifications = 0
        controller.addListener { notifications++ }
        tap(controller, StickyModifier.Ctrl)
        controller.onDown(StickyModifier.Shift)
        val beforeCancel = notifications

        controller.cancelAll()
        controller.cancelAll()
        assertFalse(controller.hasActiveModifiers)
        assertNull(controller.consume())
        assertEquals(beforeCancel + 1, notifications)
    }
}
