/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestDelegate
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.AdvancedSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginsActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsSearchIndex
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class])
class SettingsEntryNavigationTest {
    @Before fun prepareDaemon() { FcitxDaemonTestShadow.install(SettingsNavigationDaemonDelegate()) }
    @After fun resetDaemon() { FcitxDaemonTestShadow.reset() }

    private fun page(block: (MainActivity, () -> PreferenceFragmentCompat) -> Unit) {
        // STARTED is enough to navigate; About's foreground release check is outside this test.
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent()).create().start().visible()
        try {
            val activity = controller.get()
            val nav = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            fun current(): PreferenceFragmentCompat {
                nav.childFragmentManager.executePendingTransactions()
                return nav.childFragmentManager.primaryNavigationFragment as PreferenceFragmentCompat
            }
            block(activity, ::current)
        } finally { controller.stop().destroy() }
    }

    private fun PreferenceGroup.entries(): List<Preference> = (0 until preferenceCount).map(::getPreference)
    private fun PreferenceGroup.find(title: CharSequence): Preference = entries().firstOrNull { it.title == title }
        ?: entries().filterIsInstance<PreferenceGroup>().firstNotNullOfOrNull { group ->
            runCatching { group.find(title) }.getOrNull()
        } ?: error("Missing preference: $title")

    @Test fun homeHasOnlyTheKeyboardTestActionAndAboutIsTheLastMoreEntry() = page { activity, current ->
        val home = current() as MainFragment
        val menu = home.requireView().findViewById<MaterialToolbar>(R.id.toolbar).menu
        assertEquals(1, menu.size())
        val testInput = menu.getItem(0)
        assertEquals(R.id.keyboard_test_entry, testInput.itemId)
        assertTrue(testInput.isVisible && testInput.isEnabled)
        assertTrue((testInput as MenuItemImpl).requiresActionButton())
        val more = home.preferenceScreen.find(activity.getString(R.string.settings_home_category_more)) as PreferenceGroup
        assertEquals(activity.getString(R.string.about), more.entries().last().title)
        assertFalse(more.entries().any { it.title == activity.getString(R.string.faq) || it.title == activity.getString(R.string.developer) })
    }

    @Test fun aboutOpensFromHomeAndFaqFollowsTheGuideAndOpensItsUrl() = page { activity, current ->
        val home = current()
        home.preferenceScreen.find(activity.getString(R.string.about)).performClick()
        val about = current() as AboutFragment
        val entries = about.preferenceScreen.entries()
        val guide = entries.indexOfFirst { it.title == activity.getString(R.string.direct_rime_about_guide) }
        val faq = entries[guide + 1]
        assertEquals(activity.getString(R.string.faq), faq.title)
        faq.performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Const.faqUrl, intent.data.toString())
        val indexed = SettingsSearchIndex.entries(activity).single { it.titleRes == R.string.faq }
        assertEquals(SettingsRoute.About, indexed.route)
        assertEquals(listOf(R.string.about), indexed.pathRes)
    }

    @Test fun jsPluginSettingsOpensTheSameManagerFromConvenienceAndVoice() = page { activity, current ->
        current().preferenceScreen.find(activity.getString(R.string.settings_group_convenience)).performClick()
        val convenience = current() as SettingsGroupFragment
        convenience.preferenceScreen.find(activity.getString(R.string.js_voice_plugins_title)).performClick()
        val shortcut = shadowOf(activity).nextStartedActivity
        assertEquals(JsVoicePluginsActivity::class.java.name, shortcut.component?.className)
        assertTrue(convenience.findNavController().popBackStack())
        current().preferenceScreen.find(activity.getString(R.string.settings_group_input)).performClick()
        current().preferenceScreen.find(activity.getString(R.string.keyboard_category_voice)).performClick()
        current().preferenceScreen.find(activity.getString(R.string.js_voice_plugins_title)).performClick()
        val original = shadowOf(activity).nextStartedActivity
        assertEquals(shortcut.component, original.component)
        val indexed = SettingsSearchIndex.entries(activity).single { it.titleRes == R.string.js_voice_plugins_title }
        assertEquals(JsVoicePluginsActivity::class.java, indexed.activityClass)
        assertEquals(listOf(R.string.settings_search_group_android, R.string.settings_group_convenience), indexed.pathRes)
    }

    @Test fun developerOpensFromAdvanced() = page { activity, current ->
        current().preferenceScreen.find(activity.getString(R.string.settings_group_advanced)).performClick()
        val advanced = current() as AdvancedSettingsFragment
        advanced.preferenceScreen.find(activity.getString(R.string.developer)).performClick()
        assertTrue(current() is DeveloperFragment)
    }

    @Test fun customSchemeUpdateImmediatelyFollowsWanxiangInTheSameGroup() = page { activity, current ->
        current().preferenceScreen.find(activity.getString(R.string.settings_group_data)).performClick()
        val group = current().preferenceScreen.find(activity.getString(R.string.wanxiang_group)) as PreferenceGroup
        assertEquals(listOf(activity.getString(R.string.wanxiang_update), activity.getString(R.string.scheme_update_title)), group.entries().map { it.title })
        group.entries()[1].performClick()
        assertEquals(org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdateActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        val indexed = SettingsSearchIndex.entries(activity).single { it.titleRes == R.string.scheme_update_title }
        assertEquals(org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdateActivity::class.java, indexed.activityClass)
    }

    @Test fun inputStatisticsIsLastInConvenienceAndSearchUsesThatBreadcrumb() = page { activity, current ->
        current().preferenceScreen.find(activity.getString(R.string.settings_group_convenience)).performClick()
        val convenience = current()
        assertEquals(activity.getString(R.string.stats_title), convenience.preferenceScreen.entries().last().title)
        convenience.preferenceScreen.find(activity.getString(R.string.stats_title)).performClick()
        assertEquals(org.fcitx.fcitx5.android.ui.main.stats.InputStatsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(convenience.findNavController().popBackStack())
        current().preferenceScreen.find(activity.getString(R.string.settings_group_input)).performClick()
        assertTrue(runCatching { current().preferenceScreen.find(activity.getString(R.string.stats_title)) }.isFailure)
        val indexed = SettingsSearchIndex.entries(activity).single { it.titleRes == R.string.stats_title }
        assertEquals(listOf(R.string.settings_search_group_android, R.string.settings_group_convenience), indexed.pathRes)
        assertEquals(org.fcitx.fcitx5.android.ui.main.stats.InputStatsActivity::class.java, indexed.activityClass)
    }
}

/** Navigation and entry placement do not require starting a native input engine. */
private class SettingsNavigationDaemonDelegate : FcitxDaemonTestDelegate {
    override fun connect(name: String): FcitxConnection = object : FcitxConnection {
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = error("Unexpected engine read")
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = awaitCancellation()
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {}
        override val lifecycleScope = CoroutineScope(Dispatchers.Unconfined)
    }
    override fun disconnect(name: String) {}
}
