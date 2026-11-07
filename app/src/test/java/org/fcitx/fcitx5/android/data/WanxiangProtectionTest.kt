/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WanxiangProtectionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun rulesProtectExistingResourcesAndPermitFirstInstallation() {
        val p = WanxiangProtection(WanxiangProtection.DEFAULT)
        assertFalse(p.protects(temp.root, "wanxiang.custom.yaml"))
        File(temp.root, "wanxiang.custom.yaml").writeText("personal")
        assertTrue(p.protects(temp.root, "wanxiang.custom.yaml"))
        assertFalse(WanxiangProtection("").protects(temp.root, "wanxiang.custom.yaml"))
        assertTrue(WanxiangPayload.resource("wanxiang.custom.yaml", allowOverrides = true))
        assertFalse(WanxiangPayload.resource("wanxiang.custom.yaml"))
        assertTrue(WanxiangPayload.resource("custom_phrase.txt", allowOverrides = true))
        File(temp.root, "custom_phrase.txt").writeText("personal phrases")
        assertTrue(p.protects(temp.root, "custom_phrase.txt"))
        assertFalse(WanxiangProtection("").protects(temp.root, "custom_phrase.txt"))
    }
    @Test fun runtimeDataCannotBeEnabledByDeletingProtectionRules() {
        val p = WanxiangProtection("")
        for (path in listOf("wanxiang.userdb/CURRENT", "sync/device/data", "build/default.yaml", "installation.yaml", ".wanxiang-update/journal.json")) {
            assertTrue(path, p.protects(temp.root, path))
            assertFalse(path, WanxiangPayload.resource(path, allowOverrides = true))
        }
    }
    @Test fun protectionIsCheckedAgainAgainstFilesCreatedDuringDownload() {
        val payload = temp.newFolder("staged")
        File(payload, "default.yaml").writeText("new")
        File(payload, "wanxiang.schema.yaml").writeText("new schema")
        val p = WanxiangProtection("^default\\.yaml$")
        assertFalse(p.protects(temp.root, "default.yaml"))
        File(temp.root, "default.yaml").writeText("keep")
        p.filter(payload, temp.root)
        assertFalse(File(payload, "default.yaml").exists())
        assertTrue(File(payload, "wanxiang.schema.yaml").isFile)
        assertEquals("keep", File(temp.root, "default.yaml").readText())
    }
    @Test fun invalidRulesFailBeforeDownloadOrInstallation() {
        assertTrue(runCatching { WanxiangProtection("[invalid") }.isFailure)
        assertTrue(runCatching { WanxiangProtection("x".repeat(20_000)) }.isFailure)
    }
}
