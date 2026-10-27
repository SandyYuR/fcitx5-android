/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DirectoryMoveJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(): Triple<File, File, DirectoryMoveJournal> {
        val data = temp.newFolder("data")
        File(data, "rime").mkdir()
        File(data, "rime/personal.dict.yaml").writeText("personal data")
        val config = File(temp.root, "config/profile.conf").apply { parentFile!!.mkdirs(); writeText("before") }
        return Triple(data, config, DirectoryMoveJournal(data, config))
    }
    @Test fun interruptedRenameRollsBackBeforeMetadataCommit() {
        val (data, config, journal) = fixture()
        journal.prepare("rime", "renamed", false, "after")
        DirectoryMoveJournal(data, config).recover()
        assertEquals("personal data", File(data, "rime/personal.dict.yaml").readText())
        assertFalse(File(data, "renamed").exists())
        journal.recover()
    }
    @Test fun committedRenameIsKeptAfterRestart() {
        val (data, config, journal) = fixture()
        journal.prepare("rime", "renamed", false, "after")
        config.writeText("after")
        DirectoryMoveJournal(data, config).recover()
        assertEquals("personal data", File(data, "renamed/personal.dict.yaml").readText())
        assertFalse(File(data, "rime").exists())
    }
    @Test fun interruptedDeleteRestoresTheDirectory() {
        val (data, config, journal) = fixture()
        journal.prepare("rime", ".deleted-00000000-0000-0000-0000-000000000000", true, "after")
        DirectoryMoveJournal(data, config).recover()
        assertEquals("personal data", File(data, "rime/personal.dict.yaml").readText())
    }
    @Test fun committedDeleteCompletesIdempotently() {
        val (data, config, journal) = fixture()
        journal.prepare("rime", ".deleted-00000000-0000-0000-0000-000000000000", true, "after")
        config.writeText("after")
        DirectoryMoveJournal(data, config).recover()
        assertTrue(data.list()!!.isEmpty())
        journal.recover()
    }
    @Test fun aCrashBeforeThePhysicalMoveKeepsTheOriginal() {
        val (data, config, journal) = fixture()
        journal.prepare("rime", "renamed", false, "after")
        assertTrue(File(data, "renamed").renameTo(File(data, "rime")))
        DirectoryMoveJournal(data, config).recover()
        assertEquals("personal data", File(data, "rime/personal.dict.yaml").readText())
    }
    @Test fun conflictingDirectoriesAreBothPreservedForRecovery() {
        val (data, config, journal) = fixture()
        journal.prepare("rime", "renamed", false, "after")
        File(data, "rime").mkdir()
        File(data, "rime/new.txt").writeText("new data")
        assertTrue(runCatching { DirectoryMoveJournal(data, config).recover() }.isFailure)
        assertEquals("new data", File(data, "rime/new.txt").readText())
        assertEquals("personal data", File(data, "renamed/personal.dict.yaml").readText())
    }
}
