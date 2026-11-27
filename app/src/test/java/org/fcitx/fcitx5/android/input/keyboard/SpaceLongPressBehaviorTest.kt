package org.fcitx.fcitx5.android.input.keyboard

import android.view.inputmethod.InputMethodManager
import androidx.preference.ListPreference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowInputMethodManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, SpaceLongPressBehaviorTest.PickerShadow::class])
class SpaceLongPressBehaviorTest {
    @Implements(InputMethodManager::class)
    class PickerShadow : ShadowInputMethodManager() {
        var requests = 0
            private set

        @Implementation fun showInputMethodPicker() { requests++ }
    }

    @Test fun systemPickerIsASeparateOptionAndOpensOnlyOnTheLongPress() {
        val keyboard = AppPrefs.getInstance().keyboard
        val preference = keyboard.spaceKeyLongPressBehavior
        val list = keyboard.managedPreferencesUi.single { it.key == preference.key }
            .createUi(appContext) as ListPreference
        val options = list.entries.zip(list.entryValues).toMap()
        assertEquals("ShowPicker", options[appContext.getString(R.string.space_behavior_picker)])
        assertEquals(SpaceLongPressBehavior.None, preference.getValue())

        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val dispatcher = CommonKeyActionListener()
        val scope = DynamicScope()
        scope += service.wrapToUniqueComponent()
        scope += dispatcher
        val longPress = SpaceKey().behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single().action
        val picker = Shadow.extract<PickerShadow>(appContext.inputMethodManager)
        dispatcher.listener.onKeyAction(longPress, KeyActionListener.Source.Keyboard)
        assertEquals(0, picker.requests)

        val selected = options.getValue(appContext.getString(R.string.space_behavior_input_method_picker))
        preference.sharedPreferences.edit().putString(preference.key, selected.toString()).apply()
        dispatcher.listener.onKeyAction(longPress, KeyActionListener.Source.Keyboard)
        assertEquals(1, picker.requests)
        dispatcher.listener.onKeyAction(KeyAction.VoiceInputHoldEnd, KeyActionListener.Source.Keyboard)
        assertEquals(1, picker.requests)
    }
}
