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

    /**
     * 表 1 核心：Down 只表示「按到了」，不点亮；抬手确认后才切换。
     */
    @Test
    fun downAloneDoesNotLatchUntilEligibleUp() {
        val controller = StickyModifierController()
        val gesture = controller.onDown(StickyModifier.Alt)

        assertNotNull(gesture)
        assertFalse("按下尚未抬手，不能点亮", controller.isLatched(StickyModifier.Alt))
        assertFalse(controller.hasActiveModifiers)
        assertNull(controller.consume())

        controller.onUp(StickyModifier.Alt, gesture, clickEligible = true, cancelled = false)
        assertTrue(controller.isLatched(StickyModifier.Alt))
    }

    /**
     * 表 1：按下期间不被消费——修饰键自身的长按/滑动动作因此看到的是「按下前」的状态。
     */
    @Test
    fun pressedModifierIsExcludedFromConsumption() {
        val controller = StickyModifierController()
        val gesture = controller.onDown(StickyModifier.Alt)

        assertNull(controller.consume())

        controller.onUp(StickyModifier.Alt, gesture, clickEligible = false, cancelled = false)
        // 非单击也不点亮。
        assertNull(controller.consume())
        assertFalse(controller.isLatched(StickyModifier.Alt))
    }

    @Test
    fun lateUpCannotFinishAReplacementGesture() {
        val controller = StickyModifierController()
        val oldGesture = controller.onDown(StickyModifier.Ctrl)
        controller.cancelAll()
        val newGesture = controller.onDown(StickyModifier.Ctrl)

        controller.onUp(StickyModifier.Ctrl, oldGesture, clickEligible = true, cancelled = false)
        assertFalse("迟到的 Up 不应点亮替换后的手势", controller.isLatched(StickyModifier.Ctrl))
        controller.onUp(StickyModifier.Ctrl, newGesture, clickEligible = true, cancelled = false)
        assertTrue(controller.isLatched(StickyModifier.Ctrl))
    }

    @Test
    fun duplicatePhysicalDownIsRejectedAndDoesNotLatch() {
        val controller = StickyModifierController()
        val gesture = controller.onDown(StickyModifier.Alt)
        assertNotNull(gesture)
        assertNull(controller.onDown(StickyModifier.Alt))
        assertFalse(controller.isLatched(StickyModifier.Alt))
        assertNull(controller.consume())
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

    /**
     * 表 1：连击 = 连续两次独立 toggle，表现为灭→亮→灭。
     */
    @Test
    fun consecutiveTapsToggleLikeASingleSwitch() {
        val controller = StickyModifierController()

        tap(controller, StickyModifier.Shift)
        assertTrue(controller.isLatched(StickyModifier.Shift))
        tap(controller, StickyModifier.Shift)
        assertFalse(controller.isLatched(StickyModifier.Shift))
        tap(controller, StickyModifier.Shift)
        assertTrue(controller.isLatched(StickyModifier.Shift))
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
