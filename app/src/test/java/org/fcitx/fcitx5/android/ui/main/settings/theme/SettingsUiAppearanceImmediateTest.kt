/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Bundle
import android.widget.FrameLayout
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import org.robolectric.shadows.ShadowDialog

/**
 * The page keeps no draft: touching a row writes the preference immediately, and the one
 * irreversible action (restore defaults) is the only row that asks first.
 *
 * The activity is rebuilt after a change so the new theme becomes visible. A themed page cannot be
 * patched in place, which is why these tests assert on the stored preference instead of on pixels:
 * the rebuild itself is exercised by the device regression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], qualifiers = "w360dp-h640dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class]
)
class SettingsUiAppearanceImmediateTest {
    private class HostActivity : SettingsUiActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }

        companion object {
            const val CONTAINER_ID = 0x7f0f0777
        }
    }

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var fragment: SettingsUiAppearanceFragment
    private lateinit var original: SettingsUiPreferences.Selection

    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        original = SettingsUiPreferences.read(context)
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        attachFreshFragment()
    }

    /** A page instance reads the store once, so a test that seeds a baseline needs a fresh page. */
    private fun attachFreshFragment() {
        fragment = SettingsUiAppearanceFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(HostActivity.CONTAINER_ID, fragment)
            .commitNow()
        idle()
    }

    @After fun tearDown() {
        SettingsUiPreferences.save(context, original)
        runCatching { controller.pause().stop().destroy() }
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun stored() = SettingsUiPreferences.read(context)

    private fun flippedMode() = if (original.mode == SettingsUiPreferences.MODE_DARK) {
        SettingsUiPreferences.MODE_LIGHT
    } else {
        SettingsUiPreferences.MODE_DARK
    }

    private inline fun <reified T : Preference> find(key: String): T {
        val preference = fragment.preferenceScreen.findPreference<T>(key)
        assertNotNull("missing preference $key", preference)
        return preference!!
    }

    /** The restore-default row carries no key on purpose; there is exactly one destructive row. */
    private fun restoreDefaultRow(): Preference {
        val screen = fragment.preferenceScreen
        val title = context.getString(R.string.settings_ui_restore_default)
        for (index in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(index)
            if (preference.title?.toString() == title) return preference
        }
        throw AssertionError("restore default row is missing")
    }

    @Test fun modeChangeIsStoredWithoutConfirmation() {
        val target = flippedMode()
        assertTrue(find<ListPreference>("settings_ui_mode").callChangeListener(target))
        idle()
        assertEquals(target, stored().mode)
    }

    @Test fun pureBlackToggleIsStoredWithoutConfirmation() {
        assertFalse("fixture needs the toggle to be usable", original.mode == SettingsUiPreferences.MODE_LIGHT)
        assertTrue(find<SwitchPreferenceCompat>(SettingsUiPreferences.KEY_PURE_BLACK)
            .callChangeListener(!original.pureBlack))
        idle()
        assertEquals(!original.pureBlack, stored().pureBlack)
    }

    @Test fun recipeChangeIsStoredWithoutConfirmation() {
        val target = if (original.recipe == SettingsUiPreferences.RECIPE_TONAL_2025) {
            SettingsUiPreferences.RECIPE_CMF_2026
        } else {
            SettingsUiPreferences.RECIPE_TONAL_2025
        }
        assertTrue(find<ListPreference>("settings_ui_recipe").callChangeListener(target))
        idle()
        assertEquals(target, stored().recipe)
    }

    @Test fun restoreDefaultOnlyReadsUntilConfirmed() {
        // A non-default baseline, written directly so no rebuild is scheduled while the dialog is up.
        val baseline = SettingsUiPreferences.defaultSelection().copy(mode = flippedMode(), pureBlack = false)
        SettingsUiPreferences.save(context, baseline)
        attachFreshFragment()
        assertEquals(baseline, stored())
        restoreDefaultRow().performClick()
        idle()
        // Opening the confirmation must leave everything exactly as the user left it.
        assertEquals(baseline, stored())
        val dialog = ShadowDialog.getLatestDialog() as? AppCompatAlertDialog
        assertNotNull("restore default must ask first", dialog)
        dialog!!.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(baseline, stored())
    }

    @Test fun confirmingRestoreDefaultWritesTheDefaults() {
        SettingsUiPreferences.save(context, SettingsUiPreferences.defaultSelection()
            .copy(mode = flippedMode(), pureBlack = false))
        attachFreshFragment()
        restoreDefaultRow().performClick()
        idle()
        (ShadowDialog.getLatestDialog() as AppCompatAlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(SettingsUiPreferences.defaultSelection(), stored())
    }
}
