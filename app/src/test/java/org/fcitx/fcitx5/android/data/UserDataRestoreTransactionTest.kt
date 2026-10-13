package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UserDataRestoreTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root(name: String) = temp.newFolder(name).apply { File(this, "data").writeText("original-$name") }

    // Model platform aliases such as Android's /data/data and /data/user/0 without
    // requiring host symlink privileges. Staging and replacement still use real files.
    private fun alias(name: String, resolve: () -> File): File = object : File(temp.root, name) {
        override fun getCanonicalFile(): File = resolve().canonicalFile
    }

    @Test fun aStablePlatformAliasUsesTheResolvedDirectoryForCopyAndReplacement() {
        val one = root("one")
        val target = alias("platform-alias") { one }
        UserDataRestoreTransaction().use { restore ->
            restore.stage(target) { File(it, "new").writeText("restored") }
            restore.commit()
        }
        assertEquals("original-one", File(one, "data").readText())
        assertEquals("restored", File(one, "new").readText())
        assertFalse(target.exists())
    }

    @Test fun aNormalizedPathCanReplaceTheWholeDirectory() {
        val one = root("one")
        val sibling = temp.newFolder("sibling")
        val target = File(sibling, "../one")
        UserDataRestoreTransaction().use { restore ->
            restore.stage(target, copyExisting = false) { File(it, "new").writeText("restored") }
            restore.commit()
        }
        assertFalse(File(one, "data").exists())
        assertEquals("restored", File(one, "new").readText())
    }

    @Test fun anAliasRedirectedDuringStagingIsRejectedBeforeAnyDirectoryIsReplaced() {
        val early = root("early")
        val one = root("one")
        val other = root("other")
        var resolved = one
        var renames = 0
        val target = alias("changing-alias") { resolved }
        try {
            UserDataRestoreTransaction { from, to ->
                renames++
                from.renameTo(to)
            }.use { restore ->
                restore.stage(early) { File(it, "data").writeText("new-early") }
                restore.stage(target) {
                    File(it, "data").writeText("new-one")
                    resolved = other
                }
                restore.commit()
            }
            fail("A redirected destination must be rejected")
        } catch (_: IOException) { }
        assertEquals(0, renames)
        assertEquals("original-early", File(early, "data").readText())
        assertEquals("original-one", File(one, "data").readText())
        assertEquals("original-other", File(other, "data").readText())
        assertFalse(temp.root.listFiles()!!.any { it.name.contains(".original-") || it.name.contains(".import-") })
    }

    @Test fun originalsRemainUntouchedUntilAllStagingIsComplete() {
        val one = root("one")
        val two = root("two")
        UserDataRestoreTransaction().use { restore ->
            restore.stage(one) { File(it, "data").writeText("new-one") }
            restore.stage(two) { File(it, "data").writeText("new-two") }
            assertEquals("original-one", File(one, "data").readText())
            assertEquals("original-two", File(two, "data").readText())
            restore.commit()
        }
        assertEquals("new-one", File(one, "data").readText())
        assertEquals("new-two", File(two, "data").readText())
        assertFalse(temp.root.listFiles()!!.any { it.name.contains(".original-") || it.name.contains(".import-") })
    }

    @Test fun installationFailureRestoresEveryPreviouslyReplacedRoot() {
        val one = root("one")
        val two = root("two")
        try {
            UserDataRestoreTransaction { from, to ->
                if (from.name.startsWith(".two.import-")) false else from.renameTo(to)
            }.use { restore ->
                restore.stage(one) { File(it, "data").writeText("new-one") }
                restore.stage(two) { File(it, "data").writeText("new-two") }
                restore.commit()
            }
            fail("Restore should fail")
        } catch (_: IOException) { }
        assertEquals("original-one", File(one, "data").readText())
        assertEquals("original-two", File(two, "data").readText())
        assertFalse(temp.root.listFiles()!!.any { it.name.contains(".original-") || it.name.contains(".import-") })
    }

    @Test fun stagingFailureDoesNotChangeLiveFiles() {
        val one = root("one")
        try {
            UserDataRestoreTransaction().use { restore ->
                restore.stage(one) { File(it, "data").writeText("new"); throw IOException("injected read failure") }
            }
            fail("Staging should fail")
        } catch (_: IOException) { }
        assertEquals("original-one", File(one, "data").readText())
    }
}
