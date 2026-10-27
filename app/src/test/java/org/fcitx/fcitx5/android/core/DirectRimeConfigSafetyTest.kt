/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DirectRimeConfigSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(): Context {
        val files = temporary.newFolder("files")
        val external = temporary.newFolder("external")
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getExternalFilesDir(type: String?) = external
        }
    }
    private fun blockSave(config: DirectRimeConfig) {
        assertTrue(File(config.root, "config/conf/rime-data-dir.conf.tmp").mkdir())
    }

    @Test fun creatingAnOverlappingProfileRejectsBothParentsAndChildren() {
        val config = DirectRimeConfig(context()).apply { load() }
        assertTrue(runCatching { config.create("nested", File(config.user, "nested").path) }.isFailure)
        assertTrue(runCatching { config.create("parent", config.user.parent!!) }.isFailure)
        assertEquals(listOf("rime"), config.directoryState().findByName("Profiles")!!.value.lines())
    }

    private fun legacyNested(context: Context): DirectRimeConfig {
        val root = context.getExternalFilesDir(null)!!
        val nested = File(root, "data/rime/nested").apply { mkdirs() }
        File(nested, "personal.dict.yaml").writeText("personal data")
        File(root, RimeDataDirectoryState.CONFIG_PATH).apply { parentFile!!.mkdirs() }.writeText(
            RimeDataDirectoryState("nested", listOf("rime", "nested"), emptyMap()).portableConfig()
                .substringBefore("ExternalPaths=") +
                RimeDataDirectoryState.externalPathConfig(mapOf("nested" to nested.absolutePath)))
        return DirectRimeConfig(context).apply { load() }
    }

    @Test fun legacyNestedDataCannotBeDeletedOrMovedWithItsInactiveParent() {
        val config = legacyNested(context())
        assertTrue(config.error.isNotBlank())
        val dictionary = File(config.user, "personal.dict.yaml")
        assertTrue(runCatching { config.delete("rime") }.isFailure)
        assertTrue(runCatching { config.rename("rime", "renamed") }.isFailure)
        assertEquals("personal data", dictionary.readText())
        assertEquals("nested", config.current)
    }

    @Test fun failedCreateDoesNotPublishAProfileOrLeaveAnEmptyDirectory() {
        val context = context()
        val config = DirectRimeConfig(context).apply { load(); saveDirectories() }
        blockSave(config)
        assertTrue(runCatching { config.create("failed") }.isFailure)
        assertFalse(config.directoryState().findByName("Profiles")!!.value.lines().contains("failed"))
        assertFalse(File(config.dataRoot, "failed").exists())
        assertEquals(config.directoryState(), DirectRimeConfig(context).apply { load() }.directoryState())
    }

    @Test fun failedSwitchRetainsThePreviousSelectionInMemoryAndOnDisk() {
        val context = context()
        val config = DirectRimeConfig(context).apply { load(); create("second") }
        blockSave(config)
        assertTrue(runCatching { config.switch("second") }.isFailure)
        assertEquals("rime", config.current)
        assertEquals(config.current, DirectRimeConfig(context).apply { load() }.current)
    }

    @Test fun failedRenameRestoresFilesAndRegistration() {
        val context = context()
        val config = DirectRimeConfig(context).apply { load(); create("second") }
        File(config.user, "personal.dict.yaml").writeText("personal data")
        blockSave(config)
        assertTrue(runCatching { config.rename("rime", "renamed") }.isFailure)
        assertEquals("personal data", File(config.user, "personal.dict.yaml").readText())
        assertFalse(File(config.dataRoot, "renamed").exists())
        assertEquals(config.current, DirectRimeConfig(context).apply { load() }.current)
    }

    @Test fun failedDeleteRestoresTheOriginalDirectory() {
        val context = context()
        val config = DirectRimeConfig(context).apply { load(); create("second") }
        File(config.dataRoot, "second/personal.dict.yaml").writeText("keep")
        blockSave(config)
        assertTrue(runCatching { config.delete("second") }.isFailure)
        assertEquals("keep", File(config.dataRoot, "second/personal.dict.yaml").readText())
        assertTrue(config.directoryState().findByName("Profiles")!!.value.lines().contains("second"))
        assertFalse(config.dataRoot.listFiles()!!.any { it.name.startsWith(".deleted-") })
    }

    @Test fun successfulDeletionNeverRemovesExternalProfileFiles() {
        val config = DirectRimeConfig(context()).apply { load() }
        val outside = temporary.newFolder("outside")
        File(outside, "personal.dict.yaml").writeText("keep")
        config.create("external", outside.path)
        config.delete("external")
        assertEquals("keep", File(outside, "personal.dict.yaml").readText())
    }
}
