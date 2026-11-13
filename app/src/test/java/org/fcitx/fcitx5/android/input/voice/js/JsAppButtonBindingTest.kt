/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.action.JsAppButtonAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsAppButtonBindingTest {
    @Test fun exactPluginAndAppIdentityRoundTripsThroughTheButtonId() {
        val id = JsAppButtonBinding.encode("js:youdao-free-translate", "youdao_translate")!!
        assertEquals("js_app:js:youdao-free-translate/youdao_translate", id)
        assertEquals(
            JsAppButtonBinding.Target("js:youdao-free-translate", "youdao_translate"),
            JsAppButtonBinding.decode(id),
        )
        val action = ButtonAction.fromId(id)
        assertTrue(action is JsAppButtonAction)
        action as JsAppButtonAction
        assertEquals("js:youdao-free-translate", action.pluginId)
        assertEquals("youdao_translate", action.appId)
    }

    @Test fun malformedOrUnnamespacedIdsCannotBecomeJsAppActions() {
        listOf(
            "js_app:youdao/app",
            "js_app:js:youdao",
            "js_app:js:youdao/app/extra",
            "js_app:js:bad id/app",
            "js_app:js:youdao/bad id",
            "js_tools",
        ).forEach { assertNull(JsAppButtonBinding.decode(it)) }
        assertNull(JsAppButtonBinding.encode("youdao", "app"))
    }
}
