/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Pure JVM: the helper must not depend on Android Os or API 26 class linkage. */
class FilePathGuardTest {
    @get:Rule val temp = TemporaryFolder()

    private fun link(file: File, target: File) {
        check(file.isAbsolute && target.isAbsolute)
        Files.createSymbolicLink(file.toPath(), target.toPath())
    }

    @Test fun ordinaryExistingAndMissingPathsAreAllowed() {
        val directory = temp.newFolder("ordinary")
        val file = File(directory, "keep").apply { writeText("original") }
        assertFalse(FilePathGuard.isRedirected(file))
        assertFalse(FilePathGuard.isRedirected(File(directory, "missing/leaf")))
        assertFalse(FilePathGuard.isLeafRedirected(file))
        assertEquals("original", file.readText())
    }

    @Test fun leafLinksAreRejectedWithoutChangingTheirTargets() {
        val outside = temp.newFolder("outside")
        val keep = File(outside, "keep").apply { writeText("original") }
        val fileLink = File(temp.root, "file-link")
        val directoryLink = File(temp.root, "directory-link")
        link(fileLink, keep)
        link(directoryLink, outside)
        assertTrue(FilePathGuard.isRedirected(fileLink))
        assertTrue(FilePathGuard.isLeafRedirected(fileLink))
        assertTrue(FilePathGuard.isRedirected(directoryLink))
        assertEquals("original", keep.readText())
        assertTrue(Files.isSymbolicLink(fileLink.toPath()))
        assertTrue(Files.isSymbolicLink(directoryLink.toPath()))
    }

    @Test fun linkedAncestorsAreRejectedEvenWhenTheLeafDoesNotExist() {
        val outside = temp.newFolder("ancestor-target")
        val keep = File(outside, "keep").apply { writeText("original") }
        val ancestor = File(temp.root, "ancestor-link")
        link(ancestor, outside)
        assertTrue(FilePathGuard.isRedirected(File(ancestor, "keep")))
        assertTrue(FilePathGuard.isRedirected(File(ancestor, "not-created/deep/leaf")))
        assertFalse(File(outside, "not-created").exists())
        assertEquals("original", keep.readText())
    }

    @Test fun danglingLeafLinksAreRejectedWithoutCreatingTheirTargets() {
        val missing = File(temp.root, "not-created")
        val dangling = File(temp.root, "dangling")
        link(dangling, missing)
        assertFalse(dangling.exists())
        assertTrue(FilePathGuard.isRedirected(dangling))
        assertTrue(FilePathGuard.isLeafRedirected(dangling))
        assertFalse(missing.exists())
        assertTrue(Files.isSymbolicLink(dangling.toPath()))
    }

    @Test fun aTrustedParentAliasDoesNotHideARedirectedLeaf() {
        val cache = temp.newFolder("cache")
        val alias = File(temp.root, "trusted-cache-alias")
        link(alias, cache)
        val owned = File(cache, "owned").apply { check(mkdir()) }
        val throughAlias = File(alias, owned.name)
        // preparedDirectory separately checks this parent against Context.cacheDir.
        assertFalse(FilePathGuard.isLeafRedirected(throughAlias))
        assertTrue(FilePathGuard.isRedirected(throughAlias))
        val redirected = File(alias, "redirected")
        link(redirected, owned)
        assertTrue(FilePathGuard.isLeafRedirected(redirected))
        assertTrue(owned.isDirectory)
    }

    @Test fun atomicWritesRejectLinkedParentsBeforeCreatingAnyDirectories() {
        val outside = temp.newFolder("atomic-target")
        val keep = File(outside, "keep").apply { writeText("original") }
        val parent = File(temp.root, "atomic-parent-link")
        link(parent, outside)
        val target = File(parent, "must-not-create/value")
        assertTrue(runCatching { FileUtil.writeAtomically(target, "new") }.isFailure)
        assertFalse(File(outside, "must-not-create").exists())
        assertEquals("original", keep.readText())
        assertTrue(Files.isSymbolicLink(parent.toPath()))
    }

    @Test fun atomicWritesThroughATrustedRootAliasReplaceTheFileAndPreserveItOnFailure() {
        val root = temp.newFolder("app-data")
        val alias = File(temp.root, "app-data-alias")
        link(alias, root)
        val target = File(root, "cache/value").apply { parentFile!!.mkdirs(); writeText("original") }
        val throughAlias = File(alias, "cache/value")
        FileUtil.writeAtomically(throughAlias, alias) { it.write("complete".toByteArray()) }
        assertEquals("complete", target.readText())
        assertTrue(runCatching {
            FileUtil.writeAtomically(throughAlias, alias) { it.write(1); throw java.io.IOException("full") }
        }.isFailure)
        assertEquals("complete", target.readText())
        assertFalse(File(target.parentFile, "value.tmp").exists())
        assertTrue(Files.isSymbolicLink(alias.toPath()))
    }

    @Test fun aTrustedRootStillRejectsRedirectedTargetsAndTemporaryLeaves() {
        val root = temp.newFolder("leaf-app-data")
        val alias = File(temp.root, "leaf-app-data-alias")
        link(alias, root)
        val keep = temp.newFile("leaf-sentinel").apply { writeText("original") }
        val target = File(root, "value")
        link(target, keep)
        assertTrue(runCatching { FileUtil.writeAtomically(File(alias, "value"), alias) { it.write(1) } }.isFailure)
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertEquals("original", keep.readText())
        assertFalse(File(root, "value.tmp").exists())
        val ordinary = File(root, "ordinary").apply { writeText("previous") }
        val temporary = File(root, "ordinary.tmp")
        link(temporary, keep)
        assertTrue(runCatching { FileUtil.writeAtomically(File(alias, "ordinary"), alias) { it.write(1) } }.isFailure)
        assertEquals("previous", ordinary.readText())
        assertEquals("original", keep.readText())
        assertTrue(Files.isSymbolicLink(temporary.toPath()))
    }

    @Test fun aTrustedRootStillRejectsLinkedDescendantsBeforeDirectoryCreation() {
        val root = temp.newFolder("ancestor-app-data")
        val alias = File(temp.root, "ancestor-app-data-alias")
        link(alias, root)
        val outside = temp.newFolder("outside-app-data")
        val keep = File(outside, "keep").apply { writeText("original") }
        val redirect = File(root, "redirect")
        link(redirect, outside)
        assertTrue(runCatching {
            FileUtil.writeAtomically(File(alias, "redirect/must-not-create/value"), alias) { it.write(1) }
        }.isFailure)
        assertFalse(File(outside, "must-not-create").exists())
        assertEquals("original", keep.readText())
        assertTrue(Files.isSymbolicLink(redirect.toPath()))
    }

    @Test fun anUnrelatedTrustedRootCannotAuthorizeAnotherAliasOrTraversal() {
        val root = temp.newFolder("unrelated-root")
        val outside = temp.newFolder("unrelated-target")
        val keep = File(outside, "keep").apply { writeText("original") }
        val alias = File(temp.root, "unrelated-alias")
        link(alias, outside)
        assertTrue(runCatching { FileUtil.writeAtomically(File(alias, "keep"), root) { it.write(1) } }.isFailure)
        assertTrue(runCatching { FileUtil.writeAtomically(File(root, "../unrelated-target/keep"), root) { it.write(1) } }.isFailure)
        assertEquals("original", keep.readText())
    }
}
