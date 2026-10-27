/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.app.Activity
import android.content.Intent
import android.view.ViewGroup
import androidx.core.view.descendants
import com.google.android.material.textfield.TextInputLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorForm
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class LegacySwipeEditorRoundTripTest {
    private val action = mapOf("macro" to listOf(mapOf("type" to "text", "text" to "swiped")))
    private fun form(activity: Activity, key: Map<String, Any?>) = KeyEditorForm(activity,
        Intent().putExtra(KeyEditorForm.EXTRA_KEY_DATA, KeyEditorForm.toSerializableMap(key)))

    @Test fun macroAppearanceRoundTrip() = appearanceRoundTrip("MacroKey")
    @Test fun capsAppearanceRoundTrip() = appearanceRoundTrip("CapsKey")
    @Test fun switchAppearanceRoundTrip() = appearanceRoundTrip("LayoutSwitchKey")
    @Test fun symbolAppearanceRoundTrip() = appearanceRoundTrip("SymbolKey")
    @Test fun returnAppearanceRoundTrip() = appearanceRoundTrip("ReturnKey")
    @Test fun backspaceAppearanceRoundTrip() = appearanceRoundTrip("BackspaceKey")

    private fun appearanceRoundTrip(type: String) {
        val page = Robolectric.buildActivity(Activity::class.java)
        page.get().setTheme(R.style.Theme_FcitxSettings_Base_Light)
        page.setup()
        val direction = AppPrefs.getInstance().keyboard.swipeSymbolDirection
        val punctuation = ThemeManager.prefs.punctuationPosition
        val oldDirection = direction.getValue(); val oldPosition = punctuation.getValue()
        try {
            for (mode in SwipeSymbolDirection.entries) for (position in PunctuationPosition.entries) {
                direction.setValue(mode); punctuation.setValue(position)
                val up = LayoutJsonUtils.legacySwipeTargetsUpFromSettings(mode, position)
                run {
                    val key = mapOf("type" to type, "label" to "a", "main" to "a", "alt" to "A",
                        "tap" to action, "swipe" to action,
                        (if (type == "MacroKey") "altLabel" else "swipeLabel") to "legacy")
                    val editor = form(page.get(), key)
                    val weight = (editor.view as ViewGroup).descendants.filterIsInstance<TextInputLayout>()
                        .single { it.hint == page.get().getString(R.string.layout_editor_width) }.editText!!
                    weight.setText("32")
                    val saved = requireNotNull(editor.validatedDraft(false))
                    val slot = if (up) "swipeUp" else "swipeDown"
                    val other = if (up) "swipeDown" else "swipeUp"
                    val fixture = "$mode/$position/$type"
                    assertEquals(fixture, action, saved[slot])
                    assertFalse(fixture, saved.containsKey(other))
                    assertEquals(fixture, "legacy", saved[slot + "Label"])
                    assertFalse(fixture, saved.containsKey("swipe"))
                    // A subsequent global preference/theme change must not reverse an explicit slot.
                    direction.setValue(if (up) SwipeSymbolDirection.Down else SwipeSymbolDirection.Up)
                    punctuation.setValue(if (up) PunctuationPosition.Bottom else PunctuationPosition.TopRight)
                    val reopened = requireNotNull(form(page.get(), saved).validatedDraft(false))
                    assertEquals(fixture, action, reopened[slot])
                    assertFalse(fixture, reopened.containsKey(other))
                    assertEquals(fixture, "legacy", reopened[slot + "Label"])
                    direction.setValue(mode); punctuation.setValue(position)
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                        .idleFor(java.time.Duration.ofSeconds(1))
                }
            }
        } finally {
            direction.setValue(oldDirection); punctuation.setValue(oldPosition)
            page.pause().stop().destroy()
        }
    }

    @Test fun twoExplicitDirectionsKeepTheirOwnActionsAndLabelsThroughAppearanceEditing() {
        val page = Robolectric.buildActivity(Activity::class.java)
        page.get().setTheme(R.style.Theme_FcitxSettings_Base_Light); page.setup()
        try {
            val down = mapOf("macro" to listOf(mapOf("type" to "text", "text" to "down")))
            val key = mapOf("type" to "MacroKey", "label" to "a", "tap" to action,
                "swipeUp" to action, "swipeDown" to down, "swipeUpLabel" to "up", "swipeDownLabel" to "down")
            val saved = requireNotNull(form(page.get(), key).validatedDraft(false))
            assertEquals(action, saved["swipeUp"]); assertEquals(down, saved["swipeDown"])
            assertEquals("up", saved["swipeUpLabel"]); assertEquals("down", saved["swipeDownLabel"])
        } finally { page.pause().stop().destroy() }
    }
}
