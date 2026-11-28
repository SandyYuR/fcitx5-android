/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.js.*
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginSettingsFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class JsServiceHostPermissionUiTest {
    @Test fun aFreshBundledServiceHasAnExplicitNetworkApprovalEntryWithoutAppGrants() {
        val id = "js:bilibili-translate"
        JsVoiceHostPermissions.revoke(id)
        JsAppPermissions.revoke(id)
        val controller = Robolectric.buildActivity(UiPolishHostActivity::class.java).setup()
        try {
            val activity = controller.get()
            val fragment = JsVoicePluginSettingsFragment.newInstance(id)
            activity.supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).commitNow()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (fragment.findPreference<Preference>("js_voice_network_permission") == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val permission = requireNotNull(fragment.findPreference<Preference>("js_voice_network_permission"))
            val spec = requireNotNull(JsVoiceProviders.specOf(id))
            val policy = JsVoiceNetworkPolicy(id, spec.hosts)
            val url = "https://index-translate.bilibili.com/v1/chat/completions"
            assertTrue(runCatching { policy.checkUrl(url) }.isFailure)
            permission.performClick()
            val prompt = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(JsVoiceHostPermissions.approvedHosts(id).isEmpty())
            prompt.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(spec.hosts, JsVoiceHostPermissions.approvedHosts(id))
            assertEquals("index-translate.bilibili.com", policy.checkUrl(url).host)
            assertTrue(JsAppPermissions.granted(id, "bilibili_translate").isEmpty())
            permission.performClick()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(runCatching { policy.checkUrl(url) }.isFailure)
        } finally {
            JsVoiceHostPermissions.revoke(id)
            controller.pause().stop().destroy()
        }
    }
}
