/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.content.BroadcastReceiver
import android.content.Intent
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestDelegate
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class])
class FcitxApplicationShutdownTest {
    @After fun resetDaemon() = FcitxDaemonTestShadow.reset()

    @Test fun deviceShutdownUsesFinalizationInsteadOfConnectionSave() {
        var shutdowns = 0
        FcitxDaemonTestShadow.install(object : FcitxDaemonTestDelegate {
            override fun shutdownFcitx() {
                shutdowns += 1
            }
        })
        val application = RuntimeEnvironment.getApplication() as FcitxApplication
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(application, "shutdownReceiver")

        receiver.onReceive(application, Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals(0, shutdowns)
        receiver.onReceive(application, Intent(Intent.ACTION_SHUTDOWN))
        assertEquals(1, shutdowns)
    }
}
