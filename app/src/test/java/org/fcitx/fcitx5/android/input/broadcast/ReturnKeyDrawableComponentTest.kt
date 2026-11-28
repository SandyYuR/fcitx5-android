/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.broadcast

import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.plusAssign
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ReturnKeyDrawableComponentTest {
    @Test fun composingRestoresBothEditorResourceAndThemeSlot() {
        val component = ReturnKeyDrawableComponent()
        val scope = DynamicScope().apply { this += InputBroadcaster(); this += component }
        val previous = IconThemeManager.activeTheme
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/></svg>"""
        try {
            IconThemeManager.activeTheme = IconTheme(name = "return regression", icons = mapOf(
                "keys.return.default" to svg, "keys.return.search" to svg, "keys.return.send" to svg))
            listOf(EditorInfo.IME_ACTION_SEARCH to R.drawable.ic_baseline_search_24,
                EditorInfo.IME_ACTION_SEND to R.drawable.ic_baseline_send_24).forEach { (action, resource) ->
                val slot = if (action == EditorInfo.IME_ACTION_SEARCH) "keys.return.search" else "keys.return.send"
                component.updateDrawableOnEditorInfo(EditorInfo().apply { imeOptions = action })
                assertEquals(slot, component.currentSlot)
                assertNotNull(component.iconThemeDrawable)
                component.updateDrawableOnPreedit(false)
                assertEquals("keys.return.default", component.currentSlot)
                assertEquals(ReturnKeyDrawableComponent.DEFAULT_DRAWABLE, component.resourceId)
                component.updateDrawableOnPreedit(true)
                assertEquals(resource, component.resourceId)
                assertEquals(slot, component.currentSlot)
                assertNotNull(component.iconThemeDrawable)
            }
            component.updateDrawableOnEditorInfo(EditorInfo().apply {
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            })
            component.updateDrawableOnPreedit(false)
            component.updateDrawableOnPreedit(true)
            assertEquals("keys.return.default", component.currentSlot)
            assertEquals(ReturnKeyDrawableComponent.DEFAULT_DRAWABLE, component.resourceId)
            assertNotNull(scope)
        } finally { IconThemeManager.activeTheme = previous }
    }
}
