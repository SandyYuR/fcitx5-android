/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowContextImpl

/** Exercise rollback after the preference implementation has already changed its visible value. */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
class PluginStoreCommitFailureShadow : ShadowContextImpl() {
    @Implementation
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val actual = super.getSharedPreferences(name, mode)
        if (name != "js_voice_imported_plugins") return actual
        return object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        val committed = editor.commit()
                        return if (failNextCommit) {
                            failNextCommit = false
                            false
                        } else committed
                    }
                }
            }
        }
    }

    companion object {
        var failNextCommit = false
        @JvmStatic @Resetter fun resetFailure() { failNextCommit = false }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, PluginStoreCommitFailureShadow::class])
class JsVoicePluginStoreTest {
    private val rawId = "llm-assistant"
    private val providerId = "js:$rawId"
    private val meta = JsVoicePluginMeta.parse("""
        {"id":"llm-assistant","name":"Custom AI","version":"0.1","hosts":[],"features":[],
         "apps":[{"id":"llm_assistant","permissions":[]}]}
    """.trimIndent())!!

    @After fun cleanup() {
        PluginStoreCommitFailureShadow.failNextCommit = false
        JsVoicePluginStore.delete(rawId)
        JsVoiceProviders.reload()
    }

    @Test fun failedExplicitReplacementDoesNotActivateOrOverwriteLegacySource() {
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "old.js", 1), "// old"))
        val previous = JsVoicePluginStore.recordOf(rawId)
        PluginStoreCommitFailureShadow.failNextCommit = true
        assertFalse(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "new.js", 2,
            overridesBuiltIn = true), "// new"))
        assertEquals(previous, JsVoicePluginStore.recordOf(rawId))
        assertEquals("// old", JsVoicePluginStore.readScript(rawId))
        JsVoiceProviders.reload()
        assertTrue(JsVoiceProviders.specOf(providerId)!!.builtIn)
    }

    @Test fun failedRestoreAndDeleteKeepExplicitReplacementAndReportFailure() {
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "custom.js", 1,
            overridesBuiltIn = true), "// custom"))
        val previous = JsVoicePluginStore.recordOf(rawId)
        PluginStoreCommitFailureShadow.failNextCommit = true
        assertFalse(JsVoicePluginStore.restoreBuiltIn(rawId))
        assertEquals(previous, JsVoicePluginStore.recordOf(rawId))
        assertEquals("// custom", JsVoicePluginStore.readScript(rawId))
        JsVoiceProviders.reload()
        assertTrue(JsVoiceProviders.specOf(providerId)!!.overridesBuiltIn)

        PluginStoreCommitFailureShadow.failNextCommit = true
        assertFalse(JsVoicePluginStore.delete(rawId))
        assertEquals(previous, JsVoicePluginStore.recordOf(rawId))
        assertEquals("// custom", JsVoicePluginStore.readScript(rawId))
        assertTrue(JsVoicePluginStore.restoreBuiltIn(rawId))
        assertFalse(JsVoicePluginStore.recordOf(rawId)!!.overridesBuiltIn)
        assertEquals("// custom", JsVoicePluginStore.readScript(rawId))
        assertTrue(JsVoicePluginStore.delete(rawId))
        assertNull(JsVoicePluginStore.recordOf(rawId))
        assertNull(JsVoicePluginStore.readScript(rawId))
    }

    @Test fun replacementAndRemovalRevokeOnlyThatPluginsAuthorizations() {
        val other = "js:another-plugin"
        for (id in listOf(providerId, other)) {
            JsVoiceHostPermissions.approve(id, setOf("example.com"))
            JsAppPermissions.grant(id, "llm_assistant", setOf("network.request"))
        }
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "custom.js", 1,
            overridesBuiltIn = true), "// custom"))
        assertTrue(JsVoiceHostPermissions.approvedHosts(providerId).isEmpty())
        assertTrue(JsAppPermissions.granted(providerId, "llm_assistant").isEmpty())
        assertEquals(setOf("example.com"), JsVoiceHostPermissions.approvedHosts(other))
        assertEquals(setOf("network.request"), JsAppPermissions.granted(other, "llm_assistant"))
        JsVoiceHostPermissions.approve(providerId, setOf("example.com"))
        JsAppPermissions.grant(providerId, "llm_assistant", setOf("network.request"))
        assertTrue(JsVoicePluginStore.delete(rawId))
        assertTrue(JsVoiceHostPermissions.approvedHosts(providerId).isEmpty())
        assertTrue(JsAppPermissions.granted(providerId, "llm_assistant").isEmpty())
        assertEquals(setOf("example.com"), JsVoiceHostPermissions.approvedHosts(other))
    }

    @Test fun interruptedDeleteRestoresAStillReferencedScriptFromItsTombstone() {
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "custom.js", 1,
            overridesBuiltIn = true), "// custom"))
        val original = JsVoicePluginStore.scriptFile(rawId)
        val tombstone = java.io.File(original.parentFile,
            ".${original.name}.delete-fixture.tmp")
        assertTrue(original.renameTo(tombstone))
        assertFalse(original.exists())

        // Any subsequent store path resolution performs crash recovery under the store lock.
        assertEquals(original, JsVoicePluginStore.scriptFile(rawId))
        assertTrue(original.isFile)
        assertEquals("// custom", original.readText())
        assertFalse(tombstone.exists())
    }

    @Test fun directStoreUpgradeCannotDropAnExistingVoiceCapability() {
        val id = "store-voice-capability"
        val voice = requireNotNull(JsVoicePluginMeta.parse("""
            {"id":"$id","name":"Voice","version":"1","hosts":["example.com"],
             "features":[{"type":"voice.asr","id":"recognizer"}]}
        """.trimIndent()))
        val appOnly = requireNotNull(JsVoicePluginMeta.parse("""
            {"id":"$id","name":"Tool","version":"2","hosts":[],"features":[],
             "apps":[{"id":"tool","permissions":[]}]}
        """.trimIndent()))
        try {
            assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(voice, "voice.js", 1), "// voice"))
            assertFalse(JsVoicePluginStore.save(JsVoicePluginRecord(appOnly, "tool.js", 2), "// tool"))
            assertNotNull(JsVoicePluginStore.recordOf(id)!!.meta.voiceAsr)
            assertEquals("// voice", JsVoicePluginStore.readScript(id))
        } finally {
            JsVoicePluginStore.delete(id)
        }
    }
}
