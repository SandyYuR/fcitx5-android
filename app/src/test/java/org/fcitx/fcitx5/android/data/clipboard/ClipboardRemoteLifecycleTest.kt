/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.FcitxRemoteService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ClipboardRemoteLifecycleTest {
    @Test fun emptyAndDestroyedRemoteServicesDoNotKeepAGlobalPluginCallback() {
        val previous = ClipboardManager.transformer
        val controller = Robolectric.buildService(FcitxRemoteService::class.java)
        var destroyed = false
        try {
            controller.create()
            assertNull("Built-in filtering must not retain an empty service", ClipboardManager.transformer)
            ClipboardManager.transformer = { it }
            controller.destroy()
            destroyed = true
            assertNull("A destroyed service must release the global callback", ClipboardManager.transformer)
        } finally {
            if (!destroyed) controller.destroy()
            ClipboardManager.transformer = previous
        }
    }
}
