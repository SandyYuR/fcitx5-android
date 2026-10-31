package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class UserDataRestoreJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private class ProcessStopped : Error()
    private fun journal(dir: File, hook: (String) -> Unit = {}) = UserDataRestoreJournal(dir, afterPersist = hook)
    private fun root(parent: File, name: String) = File(parent, name).apply {
        mkdir(); File(this, "data").writeText("old-$name")
    }
    private fun staged(restore: UserDataRestoreTransaction, target: File) = restore.stage(target, false) {
        File(it, "data").writeText("new-${target.name}")
    }
    private fun assertClean(parent: File) = assertFalse(parent.listFiles()!!.any {
        it.name.contains(".original-") || it.name.contains(".import-")
    })

    @Test fun eachRenameWindowRollsBackAllRootsAfterProcessDeath() {
        for (window in 1..4) {
            val parent = temp.newFolder("window-$window")
            val state = temp.newFolder("journal-$window")
            val one = root(parent, "one"); val two = root(parent, "two")
            var renames = 0
            val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
                check(from.renameTo(to))
                if (++renames == window) throw ProcessStopped()
                true
            }
            staged(transaction, one); staged(transaction, two)
            try { transaction.commit(); fail("Expected process death") } catch (_: ProcessStopped) { }
            // Intentionally no close: a killed process cannot execute finally/use.
            val recovered = journal(state).recover()
            assertFalse(recovered.committed)
            assertTrue(recovered.pending.isEmpty()); assertTrue(recovered.warnings.isEmpty())
            assertEquals("old-one", File(one, "data").readText())
            assertEquals("old-two", File(two, "data").readText())
            assertClean(parent)
            assertTrue(journal(state).recover().pending.isEmpty())
        }
    }

    @Test fun persistedCommitKeepsEveryImportedRootEvenBeforeClose() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val one = root(parent, "one"); val two = root(parent, "two")
        val transaction = UserDataRestoreTransaction(journal(state) { if (it == "COMMITTED") throw ProcessStopped() })
        staged(transaction, one); staged(transaction, two)
        try { transaction.commit(); fail("Expected process death") } catch (_: ProcessStopped) { }
        val result = journal(state).recover()
        assertTrue(result.committed); assertTrue(result.pending.isEmpty()); assertTrue(result.warnings.isEmpty())
        assertEquals("new-one", File(one, "data").readText()); assertEquals("new-two", File(two, "data").readText())
        assertClean(parent)
    }

    @Test fun killedStagingNeverChangesLiveData() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val one = root(parent, "one")
        val transaction = UserDataRestoreTransaction(journal(state))
        try { transaction.stage(one) { File(it, "data").writeText("partial"); throw ProcessStopped() } }
        catch (_: ProcessStopped) { }
        assertTrue(journal(state).recover().warnings.isEmpty())
        assertEquals("old-one", File(one, "data").readText()); assertClean(parent)
    }

    @Test fun rootThatDidNotExistIsRemovedOnRollback() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val target = File(parent, "new")
        val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
            check(from.renameTo(to)); throw ProcessStopped()
        }
        staged(transaction, target)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        assertTrue(journal(state).recover().warnings.isEmpty())
        assertFalse(target.exists()); assertClean(parent)
    }

    @Test fun conflictRetainsBothOriginalAndForeignDataUntilItIsResolved() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val target = root(parent, "one")
        val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
            check(from.renameTo(to)); throw ProcessStopped()
        }
        staged(transaction, target)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        target.mkdir(); File(target, "foreign").writeText("keep")
        repeat(2) {
            assertEquals(listOf(target), journal(state).recover().pending)
            assertEquals("keep", File(target, "foreign").readText())
            assertEquals("old-one", parent.listFiles()!!.single { it.name.contains(".original-") }.resolve("data").readText())
        }
        target.deleteRecursively()
        assertTrue(journal(state).recover().pending.isEmpty())
        assertEquals("old-one", File(target, "data").readText()); assertClean(parent)
    }

    @Test fun absentVolumeRemainsPendingWithoutRecreatingDirectories() {
        val volume = temp.newFolder("volume"); val offline = File(temp.root, "offline")
        val state = temp.newFolder("journal"); val target = root(volume, "one")
        val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
            check(from.renameTo(to)); throw ProcessStopped()
        }
        staged(transaction, target)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        assertTrue(volume.renameTo(offline))
        assertEquals(listOf(target), journal(state).recover().pending); assertFalse(volume.exists())
        assertTrue(offline.renameTo(volume))
        assertTrue(journal(state).recover().pending.isEmpty())
        assertEquals("old-one", File(target, "data").readText())
    }

    @Test fun backgroundCleanupKeepsPendingRootsUntouchedAfterAConflictIsRemoved() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val one = root(parent, "one"); val two = root(parent, "two")
        val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
            check(from.renameTo(to)); throw ProcessStopped()
        }
        staged(transaction, one); staged(transaction, two)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        one.mkdir(); File(one, "foreign").writeText("keep")
        assertEquals(listOf(one), journal(state).recover(cleanup = false).pending)
        one.deleteRecursively()
        repeat(2) {
            val result = journal(state).cleanupResolved()
            assertEquals(listOf(one), result.pending)
            assertTrue(result.cleanupPending)
            assertFalse("Background deletion must not restore the live root", one.exists())
            assertEquals("old-two", File(two, "data").readText())
            assertFalse(parent.listFiles()!!.any { it.name.startsWith(".two.import-") })
        }
        assertTrue(journal(state).recover().pending.isEmpty())
        assertEquals("old-one", File(one, "data").readText()); assertClean(parent)
    }

    @Test fun backgroundCleanupDoesNotResolveAnOptionalVolumeWhenItReturns() {
        val volume = temp.newFolder("volume"); val offline = File(temp.root, "offline")
        val state = temp.newFolder("journal"); val target = root(volume, "one")
        val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
            check(from.renameTo(to)); throw ProcessStopped()
        }
        staged(transaction, target)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        assertTrue(volume.renameTo(offline))
        assertEquals(listOf(target), journal(state).recover(cleanup = false).pending)
        assertTrue(offline.renameTo(volume))
        assertEquals(listOf(target), journal(state).cleanupResolved().pending)
        assertFalse(target.exists())
        assertTrue(journal(state).recover().pending.isEmpty())
        assertEquals("old-one", File(target, "data").readText()); assertClean(volume)
    }

    @Test fun resolvedRollbackCanResumeCleanupAfterAnotherProcessDeath() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val target = root(parent, "one")
        var count = 0
        val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
            check(from.renameTo(to)); if (++count == 2) throw ProcessStopped(); true
        }
        staged(transaction, target)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        try { journal(state) { throw ProcessStopped() }.recover() } catch (_: ProcessStopped) { }
        assertEquals("old-one", File(target, "data").readText())
        assertTrue(journal(state).recover().warnings.isEmpty()); assertClean(parent)
    }

    @Test fun stagingCleanupUnlinksChildSymlinkWithoutFollowingIt() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val outside = temp.newFolder("outside"); File(outside, "keep").writeText("keep")
        val target = root(parent, "one")
        val transaction = UserDataRestoreTransaction(journal(state))
        transaction.stage(target) { org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(it, "link").toPath(), outside.toPath()) }
        assertTrue(journal(state).recover().warnings.isEmpty())
        assertEquals("keep", File(outside, "keep").readText()); assertClean(parent)
    }

    @Test fun redirectedRootFailsClosedAndDoesNotTouchTheDestination() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val outside = temp.newFolder("outside"); File(outside, "keep").writeText("keep")
        val target = root(parent, "one")
        val transaction = UserDataRestoreTransaction(journal(state)); staged(transaction, target)
        target.deleteRecursively(); org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(target.toPath(), outside.toPath())
        assertTrue(runCatching { journal(state).recover() }.exceptionOrNull() is IOException)
        assertEquals("keep", File(outside, "keep").readText())
        assertTrue(File(state, "user-data-restore.json").exists())
    }

    @Test fun malformedJournalIsRetainedAndBlocksANewRestore() {
        val state = temp.newFolder("journal")
        val file = File(state, "user-data-restore.json").apply { writeText("{broken") }
        assertTrue(runCatching { journal(state).recover() }.isFailure)
        assertTrue(runCatching { journal(state).begin() }.isFailure)
        assertEquals("{broken", file.readText())
    }

    @Test fun oversizedJournalIsRejectedBeforeParsing() {
        val state = temp.newFolder("journal")
        File(state, "user-data-restore.json").outputStream().use { it.write(ByteArray(4 * 1024 * 1024 + 1)) }
        assertTrue(runCatching { journal(state).recover() }.exceptionOrNull() is IOException)
    }

    @Test fun overlappingRootsAndJournalRootsAreRejectedBeforeReplacement() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val target = root(parent, "one")
        UserDataRestoreTransaction(journal(state)).use { transaction ->
            staged(transaction, target)
            assertTrue(runCatching { staged(transaction, File(target, "nested")) }.isFailure)
            assertTrue(runCatching { staged(transaction, state) }.isFailure)
        }
        assertEquals("old-one", File(target, "data").readText())
    }

    @Test fun startupRollbackLeavesRecursiveStagingCleanupForTheBackground() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val target = root(parent, "one")
        var count = 0
        val transaction = UserDataRestoreTransaction(journal(state)) { from, to ->
            check(from.renameTo(to)); if (++count == 2) throw ProcessStopped(); true
        }
        staged(transaction, target)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        val recovered = journal(state).recover(cleanup = false)
        assertTrue(recovered.cleanupPending); assertTrue(recovered.pending.isEmpty())
        assertEquals("old-one", File(target, "data").readText())
        val staged = parent.listFiles()!!.single { it.name.contains(".import-") }
        assertEquals("new-one", File(staged, "data").readText())
        assertTrue(journal(state).recover(cleanup = false).cleanupPending)
        assertFalse(journal(state).recover().cleanupPending); assertClean(parent)
    }

    @Test fun startupAfterCommitKeepsImportedDataWhileOriginalCleanupIsDeferred() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val target = root(parent, "one")
        val transaction = UserDataRestoreTransaction(journal(state) { if (it == "COMMITTED") throw ProcessStopped() })
        staged(transaction, target)
        try { transaction.commit() } catch (_: ProcessStopped) { }
        val recovered = journal(state).recover(cleanup = false)
        assertTrue(recovered.committed); assertTrue(recovered.cleanupPending)
        assertEquals("new-one", File(target, "data").readText())
        val original = parent.listFiles()!!.single { it.name.contains(".original-") }
        assertEquals("old-one", File(original, "data").readText())
        assertFalse(journal(state).recover().cleanupPending); assertClean(parent)
        assertEquals("new-one", File(target, "data").readText())
    }

    @Test fun startupWithUnfinishedStagingKeepsLiveDataAndDefersDeletion() {
        val parent = temp.newFolder("roots"); val state = temp.newFolder("journal")
        val target = root(parent, "one")
        val transaction = UserDataRestoreTransaction(journal(state)); staged(transaction, target)
        assertTrue(journal(state).recover(cleanup = false).cleanupPending)
        assertEquals("old-one", File(target, "data").readText())
        assertTrue(parent.listFiles()!!.any { it.name.contains(".import-") })
        assertFalse(journal(state).recover().cleanupPending); assertClean(parent)
    }
}
