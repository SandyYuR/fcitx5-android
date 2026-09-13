/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索会话的状态通知必须按实例注册/注销。
 *
 * 背景：渲染搜索界面的 InputView 会因主题/偏好变更被整体替换
 * （FcitxInputMethodService.replaceInputView：新实例先构造并注册，旧实例随后才
 * onDetachedFromWindow）。单槽回调下旧实例的注销会清掉新实例的注册，此后搜索框
 * 文本与辅助栏结果再也不会刷新——即 2026-09-13 反馈的“打字进不了搜索框、返回
 * 退不出搜索”。本测试锁定这一时序。
 */
class ClipboardSearchControllerTest {

    private var oldViewCalls = 0
    private var newViewCalls = 0

    private val oldViewListener: () -> Unit = { oldViewCalls++ }
    private val newViewListener: () -> Unit = { newViewCalls++ }

    @After
    fun tearDown() {
        ClipboardSearchController.removeOnStateChangedListener(oldViewListener)
        ClipboardSearchController.removeOnStateChangedListener(newViewListener)
        ClipboardSearchController.stop()
        oldViewCalls = 0
        newViewCalls = 0
    }

    @Test
    fun oldViewUnregisterKeepsNewerViewRegistration() {
        // InputView 替换时序：旧实例已注册 → 新实例构造并注册 → 旧实例销毁注销
        ClipboardSearchController.addOnStateChangedListener(oldViewListener)
        ClipboardSearchController.addOnStateChangedListener(newViewListener)
        ClipboardSearchController.removeOnStateChangedListener(oldViewListener)

        ClipboardSearchController.start()

        assertTrue(ClipboardSearchController.isActive)
        assertEquals("旧视图不应再收到回调", 0, oldViewCalls)
        assertEquals("新视图必须仍然收到回调", 1, newViewCalls)
    }

    @Test
    fun allRegisteredListenersReceiveSessionStateChanges() {
        ClipboardSearchController.addOnStateChangedListener(oldViewListener)
        ClipboardSearchController.addOnStateChangedListener(newViewListener)

        ClipboardSearchController.start()
        assertEquals(1, oldViewCalls)
        assertEquals(1, newViewCalls)

        ClipboardSearchController.stop()
        assertEquals(2, oldViewCalls)
        assertEquals(2, newViewCalls)
        assertFalse(ClipboardSearchController.isActive)
    }

    @Test
    fun duplicateRegistrationNotifiesOnceAndSingleUnregisterRemovesIt() {
        ClipboardSearchController.addOnStateChangedListener(newViewListener)
        ClipboardSearchController.addOnStateChangedListener(newViewListener)

        ClipboardSearchController.start()
        assertEquals("同一实例重复注册只应通知一次", 1, newViewCalls)

        ClipboardSearchController.removeOnStateChangedListener(newViewListener)
        ClipboardSearchController.stop()
        assertEquals("注销后不应再收到回调", 1, newViewCalls)
    }

    @Test
    fun unregisteringUnknownListenerLeavesOthersIntact() {
        ClipboardSearchController.addOnStateChangedListener(newViewListener)
        ClipboardSearchController.removeOnStateChangedListener(oldViewListener)

        ClipboardSearchController.start()
        assertEquals(1, newViewCalls)
    }
}
