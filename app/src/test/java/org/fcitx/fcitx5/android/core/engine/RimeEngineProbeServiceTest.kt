/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RimeEngineProbeServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun probeServiceIsPrivateAndUsesAnIsolatedUid() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, RimeEngineProbeService::class.java),
            0
        )
        assertFalse(info.exported)
        assertTrue(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertEquals("${context.packageName}:rime_engine_probe", info.processName)
        assertFalse("普通应用进程不能被误判为探测进程", RimeEngineProbeService.isProbeProcess(context))
    }

    @Test fun cancellationBeforeConnectionStillUnbindsTheService() {
        val recording = RecordingContext(context)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val binding = RimeEngineProbeBinding(recording, connection)

        assertTrue(binding.bind())
        assertEquals(1, recording.bindCount)
        assertEquals(0, recording.unbindCount)
        assertSame(connection, recording.connection)

        // No onServiceConnected callback or process PID has been delivered.
        binding.close()
        binding.close()
        assertEquals(1, recording.unbindCount)
    }

    @Test fun isolatedServiceInstanceSuffixIsRecognized() {
        val packageName = "org.example.ime"
        assertTrue(RimeEngineProbeService.isProbeProcessName(packageName,
            "$packageName:rime_engine_probe"))
        assertTrue(RimeEngineProbeService.isProbeProcessName(packageName,
            "$packageName:rime_engine_probe:org.example.ProbeService"))
        assertFalse(RimeEngineProbeService.isProbeProcessName(packageName, packageName))
        assertFalse(RimeEngineProbeService.isProbeProcessName(packageName,
            "$packageName:rime_engine_probe_other"))
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var bindCount = 0
        var unbindCount = 0
        var connection: ServiceConnection? = null

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindCount++
            connection = conn
            return true
        }

        override fun unbindService(conn: ServiceConnection) {
            assertSame(connection, conn)
            unbindCount++
        }
    }
}
