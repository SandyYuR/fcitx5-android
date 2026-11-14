/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginSettingsActivity
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppSelectionTest {
    private val firstId = "selection-first"
    private val secondId = "selection-second"
    private val preference get() = AppPrefs.getInstance().internal.preferredJsApp

    @Before fun setUp() {
        cleanUp()
    }

    @After fun cleanUp() {
        preference.setValue("")
        JsVoicePluginStore.delete(firstId)
        JsVoicePluginStore.delete(secondId)
        JsVoiceProviders.reload()
    }

    @Test fun defaultIdentifiesTheBundledProviderAsWellAsItsApp() {
        install(firstId, "llm_assistant")

        assertPreferred("js:llm-assistant", "llm_assistant")
        assertTrue(JsAppSelection.select("js:$firstId", "llm_assistant"))
        assertPreferred("js:$firstId", "llm_assistant")
        assertTrue(JsAppSelection.select("js:llm-assistant", "llm_assistant"))
        assertEquals("", preference.getValue())
        assertPreferred("js:llm-assistant", "llm_assistant")
    }

    @Test fun anyRegisteredAppCanBeSelectedAndThePairSurvivesCatalogReload() {
        install(firstId, "formatter", "translator")
        install(secondId, "formatter")
        assertTrue(JsAppSelection.available().any { (spec, app) ->
            spec.id == "js:$firstId" && app.id == "translator"
        })

        assertTrue(JsAppSelection.select("js:$secondId", "formatter"))
        assertEquals("js:$secondId/formatter", preference.sharedPreferences.getString(preference.key, null))
        JsVoiceProviders.reload()
        assertPreferred("js:$secondId", "formatter")

        assertTrue(JsAppSelection.select("js:$firstId", "translator"))
        assertPreferred("js:$firstId", "translator")
    }

    @Test fun unknownProviderOrAppDoesNotReplaceAValidSelection() {
        install(firstId, "formatter")
        assertTrue(JsAppSelection.select("js:$firstId", "formatter"))

        assertFalse(JsAppSelection.select("js:missing", "formatter"))
        assertFalse(JsAppSelection.select("js:$firstId", "missing"))
        assertFalse(JsAppSelection.select(firstId, "formatter"))
        assertPreferred("js:$firstId", "formatter")
    }

    @Test fun removalClearsTheSelectionBeforeAnotherClickAndReimportDoesNotRestoreIt() {
        install(firstId, "formatter")
        assertTrue(JsAppSelection.select("js:$firstId", "formatter"))

        assertTrue(JsVoicePluginStore.delete(firstId))
        JsVoiceProviders.reload()
        assertEquals("The reload hook must clear a missing target immediately", "", preference.getValue())

        install(firstId, "formatter")
        assertPreferred("js:llm-assistant", "llm_assistant")
    }

    @Test fun upgradeRemovingTheSelectedAppFallsBackWithoutSelectingAnotherAppInItsPlugin() {
        install(firstId, "formatter", "translator")
        assertTrue(JsAppSelection.select("js:$firstId", "formatter"))

        install(firstId, "translator")
        assertEquals("", preference.getValue())
        assertPreferred("js:llm-assistant", "llm_assistant")

        install(firstId, "formatter", "translator")
        assertPreferred("js:llm-assistant", "llm_assistant")
    }

    @Test fun importedUpgradeMayRemoveAnAppThroughTheRealImporterWithoutResurrectingSelection() = runBlocking {
        install(firstId, "formatter", "translator")
        assertTrue(JsAppSelection.select("js:$firstId", "formatter"))
        val updatedScript = """
            foxy.plugin.register({id:'$firstId',hosts:[],features:[]});
            foxy.app.register({id:'translator',permissions:[],onOpen:function(){return {close:true};}});
        """.trimIndent()
        val inspected = JsVoiceImporter.inspect(
            script = updatedScript,
            locale = "en",
            builtInIds = JsVoiceProviders.builtInIds(),
            importedIds = JsVoicePluginStore.importedIds(),
            existingImportedSpecs = JsVoiceProviders.all().associateBy { it.id },
        ) as JsVoiceImportResult.Success
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(inspected.meta, "updated.js", 2), updatedScript))
        JsVoiceProviders.reload()
        assertEquals("", preference.getValue())
        assertPreferred("js:llm-assistant", "llm_assistant")

        install(firstId, "formatter", "translator")
        assertPreferred("js:llm-assistant", "llm_assistant")
    }

    @Test fun ordinaryImportedVoiceUpgradeCannotDropItsVoiceCapability() = runBlocking {
        val voiceScript = """
            foxy.plugin.register({id:'$firstId',hosts:['example.com'],
              features:[{type:'voice.asr',id:'recognizer'}],
              start:function(){return 'session';},audio:function(){},stop:function(){}});
        """.trimIndent()
        val original = JsVoiceImporter.inspect(
            script = voiceScript,
            locale = "en",
            builtInIds = JsVoiceProviders.builtInIds(),
        ) as JsVoiceImportResult.Success
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(original.meta, "voice.js", 1), voiceScript))
        JsVoiceProviders.reload()

        val appOnly = """
            foxy.plugin.register({id:'$firstId',hosts:[],features:[]});
            foxy.app.register({id:'tool',permissions:[],onOpen:function(){return {close:true};}});
        """.trimIndent()
        val rejected = JsVoiceImporter.inspect(
            script = appOnly,
            locale = "en",
            builtInIds = JsVoiceProviders.builtInIds(),
            importedIds = JsVoicePluginStore.importedIds(),
            existingImportedSpecs = JsVoiceProviders.all().associateBy { it.id },
        )
        assertTrue(rejected is JsVoiceImportResult.Failure)
        assertEquals(
            JsVoiceImportResult.Failure.Reason.INCOMPATIBLE_TARGET,
            (rejected as JsVoiceImportResult.Failure).reason,
        )
    }

    @Test fun malformedRestoredSelectionIsClearedWithoutMatchingAnAppByName() {
        install(firstId, "formatter")
        preference.setValue("$firstId/formatter")

        assertPreferred("js:llm-assistant", "llm_assistant")
        assertEquals("", preference.getValue())
    }

    @Test fun aiSettingsRouteUsesTheSameSelectedProviderAndFallback() {
        install(firstId, "formatter")
        assertTrue(JsAppSelection.select("js:$firstId", "formatter"))
        JsApps.configure(appContext)
        val selected = shadowOf(appContext as FcitxApplication).nextStartedActivity
        assertEquals("js:$firstId", selected.getStringExtra(JsVoicePluginSettingsActivity.EXTRA_PLUGIN_ID))

        assertTrue(JsVoicePluginStore.delete(firstId))
        JsVoiceProviders.reload()
        JsApps.configure(appContext)
        val fallback = shadowOf(appContext as FcitxApplication).nextStartedActivity
        assertEquals("js:llm-assistant", fallback.getStringExtra(JsVoicePluginSettingsActivity.EXTRA_PLUGIN_ID))
    }

    @Test fun exactShortcutSettingsRouteUsesItsOwnPluginInsteadOfTheAiPreference() {
        JsApps.configure(appContext, "js:youdao-free-translate")
        val intent = shadowOf(appContext as FcitxApplication).nextStartedActivity
        assertEquals(
            "js:youdao-free-translate",
            intent.getStringExtra(JsVoicePluginSettingsActivity.EXTRA_PLUGIN_ID),
        )
    }

    private fun install(id: String, vararg appIds: String) {
        val apps = appIds.map { JsPluginApp(it, it, emptySet()) }
        val meta = JsVoicePluginMeta(id, "Text tools", "1", emptyList(), emptyList(), emptyList(), apps)
        val script = "foxy.plugin.register({id:'$id',hosts:[],features:[]});\n" +
            appIds.joinToString("\n") { "foxy.app.register({id:'$it',permissions:[],onOpen:function(){return {close:true};}});" }
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "$id.js", 1), script))
        JsVoiceProviders.reload()
    }

    private fun assertPreferred(pluginId: String, appId: String) {
        val selected = requireNotNull(JsAppSelection.preferred())
        assertEquals(pluginId, selected.first.id)
        assertEquals(appId, selected.second.id)
    }
}
