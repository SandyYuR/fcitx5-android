/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Process
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.IFcitxRemoteService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RemoteServicePermissionTest {
    @Test fun exportedBindingRequiresTheSignaturePermission() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getServiceInfo(ComponentName(context, FcitxRemoteService::class.java), 0)
        assertTrue(info.exported)
        assertEquals("${context.packageName}.permission.IPC", info.permission)
    }

    @Test fun aHandedOffBinderStillChecksEveryCaller() {
        val service = Robolectric.buildService(FcitxRemoteService::class.java).get()
        val remote = IFcitxRemoteService.Stub.asInterface(service.onBind(Intent()))
        val transformer = object : IClipboardEntryTransformer.Stub() {
            override fun getPriority() = 0
            override fun getDescription() = "test"
            override fun transform(clipboardText: String) = clipboardText
        }
        ShadowBinder.setCallingUid(Process.myUid() + 123)
        ShadowBinder.setCallingPid(Process.myPid() + 123)
        try {
            listOf<() -> Unit>(
                { remote.pid }, { remote.versionName }, { remote.restartFcitx() },
                { remote.registerClipboardEntryTransformer(transformer) },
                { remote.unregisterClipboardEntryTransformer(transformer) },
                { remote.importRemoteClipboardEntry("private", "", "", "text", 0, false) }
            ).forEach { call ->
                try { call(); fail("Untrusted Binder call succeeded") } catch (_: SecurityException) { }
            }
            ShadowBinder.setCallingUid(Process.myUid())
            assertEquals(Process.myPid(), remote.pid)
        } finally { ShadowBinder.reset() }
    }
}
