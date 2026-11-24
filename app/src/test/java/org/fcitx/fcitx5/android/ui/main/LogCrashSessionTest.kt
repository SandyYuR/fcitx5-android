/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 崩溃页的来源判定。
 *
 * 清空崩溃堆栈以后，进程被系统回收、再从最近任务恢复时，system server 会把原启动 Intent
 * 原样再发一遍——里面仍带着那份已经清空的堆栈。删本地 extra 拦不住它，只有 saved state
 * 能证明「这次启动之前用户已经清空过」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogCrashSessionTest {

    private fun crashIntent(stackTrace: String): Intent = Intent().apply {
        putExtra(LogActivity.FROM_CRASH, true)
        putExtra(LogActivity.CRASH_STACK_TRACE, stackTrace)
    }

    private fun clearedState(): Bundle = Bundle().apply {
        putBoolean(STATE_CRASH_STACK_CLEARED, true)
    }

    @Test
    fun aFreshCrashKeepsItsStack() {
        val restored = restoreCrashSession(null, crashIntent("stack"))

        assertTrue(restored.fromCrash)
        assertFalse(restored.cleared)
        assertEquals("stack", restored.stackTrace)
    }

    @Test
    fun aPlainLogPageIsNotTreatedAsACrashPage() {
        val restored = restoreCrashSession(null, Intent())

        assertFalse(restored.fromCrash)
        assertFalse(restored.cleared)
        assertNull(restored.stackTrace)
    }

    /**
     * 关键回归：清空之后进程被回收，恢复时拿到的仍是那份带堆栈的原启动 Intent。
     * saved state 说清空过，就必须当作普通日志页，绝不再把堆栈贴回去。
     */
    @Test
    fun aClearedCrashIsNotRevivedByTheRelaunchedLaunchIntent() {
        val restored = restoreCrashSession(clearedState(), crashIntent("stack"))

        assertFalse(restored.fromCrash)
        assertTrue(restored.cleared)
        assertNull(restored.stackTrace)
    }

    /** 反复重建时 saved state 会被一层层重写，清空状态必须一直是 true。 */
    @Test
    fun theClearedFlagSurvivesRepeatedRecreation() {
        var state: Bundle? = clearedState()
        repeat(3) {
            val restored = restoreCrashSession(state, crashIntent("stack"))
            assertFalse(restored.fromCrash)
            assertNull(restored.stackTrace)
            state = clearedState().apply { putBoolean(STATE_CRASH_STACK_CLEARED, restored.cleared) }
        }
    }
}
