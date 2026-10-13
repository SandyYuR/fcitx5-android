package org.fcitx.fcitx5.android.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipStreamSecurityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, String>): ZipInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return ZipInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test(expected = SecurityException::class)
    fun directoryEntriesCannotEscapeTheDestination() {
        archive("../outside/" to "").use { it.extract(temp.newFolder("dest")) }
    }

    @Test(expected = SecurityException::class)
    fun siblingWithTheSamePrefixIsOutsideTheDestination() {
        archive("../dest-other/words" to "private").use { it.extract(temp.newFolder("dest")) }
    }

    @Test(expected = SecurityException::class)
    fun windowsSeparatorsCannotBypassTraversalChecks() {
        archive("..\\outside\\words" to "private").use { it.extract(temp.newFolder("dest")) }
    }

    @Test(expected = IOException::class)
    fun duplicateNormalizedPathsAreRejected() {
        archive("same" to "one", "./same" to "two").use { it.extract(temp.newFolder("dest")) }
    }

    @Test(expected = IOException::class)
    fun totalUncompressedBytesAreLimited() {
        archive("one" to "12", "two" to "34").use { it.extract(temp.newFolder("dest"), maxBytes = 3) }
    }

    @Test(expected = IOException::class)
    fun entryCountIsLimitedIncludingDirectories() {
        archive("one/" to "", "two/" to "", "three/" to "").use { it.extract(temp.newFolder("dest"), maxEntries = 2) }
    }

    @Test fun normalArchivePreservesNestedFiles() {
        val root = temp.newFolder("dest")
        archive("external/data/rime/" to "", "external/data/rime/user.dict.yaml" to "words").use { it.extract(root) }
        assertEquals("words", root.resolve("external/data/rime/user.dict.yaml").readText())
    }
}
