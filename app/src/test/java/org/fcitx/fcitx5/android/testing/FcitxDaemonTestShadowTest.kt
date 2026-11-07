/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.testing

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class])
class FcitxDaemonTestShadowTest {
    @After fun resetDaemon() { FcitxDaemonTestShadow.reset() }

    @Test fun reusedSingletonUsesOnlyTheCurrentDelegateAndRejectsCallsAfterReset() {
        val calls = mutableListOf<String>()
        FcitxDaemonTestShadow.install(object : FcitxDaemonTestDelegate {
            override fun getCurrentInputSession() = 11L
            override fun disconnect(name: String) { calls += "first:$name" }
        })
        val singleton = FcitxDaemon
        assertEquals(11L, singleton.currentInputSession)
        singleton.disconnect("navigation")

        FcitxDaemonTestShadow.install(object : FcitxDaemonTestDelegate {
            override fun getCurrentInputSession() = 22L
            override fun disconnect(name: String) { calls += "second:$name" }
        })
        assertSame(singleton, FcitxDaemon)
        assertEquals(22L, singleton.currentInputSession)
        singleton.disconnect("updater")
        assertEquals(listOf("first:navigation", "second:updater"), calls)

        FcitxDaemonTestShadow.reset()
        assertThrows(IllegalStateException::class.java) { singleton.disconnect("late") }
        assertEquals(listOf("first:navigation", "second:updater"), calls)
    }
}
