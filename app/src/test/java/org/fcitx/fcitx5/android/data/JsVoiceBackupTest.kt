package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.voice.js.*
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsVoiceBackupTest {
    @Test fun wholeBackupMustIncludeImportedPluginScript() {
        val meta = JsVoicePluginMeta.parse("""{"id":"review","name":"review","version":"1","hosts":["example.com"],"features":[{"type":"voice.asr"}]}""")!!
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "review.js", 0), "// review fixture script"))
        File(appContext.applicationInfo.dataDir, "databases").mkdirs()
        val external = appContext.getExternalFilesDir(null)!!
        val profile = File(external, "data/rime").apply { mkdirs() }
        val output = ByteArrayOutputStream()
        UserDataManager.export(output, RimeDataDirectoryState("rime", listOf("rime"), mapOf("rime" to profile.path))).getOrThrow()
        val archivedScript = JsVoicePluginStore.scriptFile("review").name
        val names = mutableListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry() }
        }
        assertTrue("Imported plugin metadata is backed up", names.any { it.endsWith("js_voice_imported_plugins.xml") })
        assertTrue("Imported JS script is missing from whole backup", names.contains("js-voice/" + archivedScript))
        UserDataManager.prepareImport(output.toByteArray().inputStream()).use { prepared ->
            assertEquals("// review fixture script", File(prepared.directory, "js-voice/$archivedScript").readText())
            JsVoicePluginStore.scriptFile("review").delete()
            UserDataManager.restore(prepared, RimeProfileRestorePlan(emptyMap()), closeClipboard = false).getOrThrow()
            assertEquals("// review fixture script", JsVoicePluginStore.readScript("review"))
            File(prepared.directory, "js-voice/$archivedScript").writeText("// damaged")
            assertTrue(runCatching { JsVoicePluginStore.validateBackup(prepared.directory) }.isFailure)
            File(prepared.directory, "js-voice").deleteRecursively()
            assertTrue("A new backup must not silently lose its referenced scripts",
                runCatching { JsVoicePluginStore.validateBackup(prepared.directory) }.isFailure)
        }
    }
    @Test fun failedUpgradeLeavesOldScriptAndMetadataUsable() {
        val meta = JsVoicePluginMeta.parse("""{"id":"atomic","version":"1","hosts":["example.com"],"features":[{"type":"voice.asr"}]}""")!!
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "old.js", 1), "// old"))
        val old = JsVoicePluginStore.recordOf("atomic")!!
        assertFalse(JsVoicePluginStore.save(old.copy(sourceName = "too-large.js"), "x".repeat(JsVoicePluginMeta.MAX_SCRIPT_BYTES + 1)))
        assertEquals(old, JsVoicePluginStore.recordOf("atomic"))
        assertEquals("// old", JsVoicePluginStore.readScript("atomic"))
        // Force rename failure after the temporary file has been fully written.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("// new".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val collision = File(appContext.filesDir, "js-voice/atomic.$digest.js").apply { mkdirs() }
        File(collision, "blocker").writeText("keep")
        assertFalse(JsVoicePluginStore.save(old.copy(sourceName = "new.js"), "// new"))
        assertEquals(old, JsVoicePluginStore.recordOf("atomic"))
        assertEquals("// old", JsVoicePluginStore.readScript("atomic"))
        collision.deleteRecursively()
        assertTrue(JsVoicePluginStore.save(old.copy(sourceName = "new.js"), "// new"))
        assertEquals("// new", JsVoicePluginStore.readScript("atomic"))
        assertEquals("new.js", JsVoicePluginStore.recordOf("atomic")!!.sourceName)
    }

}
