/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.os.Looper
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class LoadingDialogLifecycleTest {
    class Host : SettingsUiActivity()

    private fun verify(progress: Boolean, destroyBeforeShow: Boolean = false, complete: Boolean = false) {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val host = controller.get()
        val action = CompletableDeferred<Unit>()
        var destroyed = false
        try {
            if (progress) host.lifecycleScope.withProgressLoadingDialog(host, threshold = 100) { update ->
                update("正在导入")
                action.await()
            } else host.lifecycleScope.withLoadingDialog(host, threshold = 100) { action.await() }
            val main = shadowOf(Looper.getMainLooper())
            main.idle()
            if (destroyBeforeShow) {
                controller.pause().stop().destroy()
                destroyed = true
                main.idleFor(Duration.ofSeconds(1))
                assertTrue("Destroyed hosts cannot show a delayed dialog", ShadowDialog.getShownDialogs().isEmpty())
            } else {
                main.idleFor(Duration.ofMillis(150))
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertTrue(dialog.isShowing)
                if (complete) action.complete(Unit) else {
                    controller.pause().stop().destroy()
                    destroyed = true
                }
                main.idle()
                assertFalse("Completion and lifecycle cancellation must both dismiss the window", dialog.isShowing)
            }
        } finally {
            if (!destroyed) controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            ShadowDialog.getLatestDialog()?.dismiss()
        }
    }

    @Test fun loadingDialogClosesWhenItsLifecycleIsDestroyed() = verify(false)
    @Test fun progressDialogClosesWhenItsLifecycleIsDestroyed() = verify(true)
    @Test fun loadingDialogDoesNotAppearAfterEarlyDestruction() = verify(false, destroyBeforeShow = true)
    @Test fun progressDialogDoesNotAppearAfterEarlyDestruction() = verify(true, destroyBeforeShow = true)
    @Test fun loadingDialogClosesOnSuccessfulCompletion() = verify(false, complete = true)
    @Test fun progressDialogClosesOnSuccessfulCompletion() = verify(true, complete = true)
}
