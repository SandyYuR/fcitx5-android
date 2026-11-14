/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class DirectRimeManagementCommandsTest {
    @Test fun profileActionsUseExactProtocolValuesAndLeaveUnknownActionsForExecution() {
        val actions = mapOf(
            "create" to DirectRimeManagementCommands.ProfileAction.Create,
            "switch" to DirectRimeManagementCommands.ProfileAction.Switch,
            "rename" to DirectRimeManagementCommands.ProfileAction.Rename,
            "delete" to DirectRimeManagementCommands.ProfileAction.Delete,
        )
        for ((value, action) in actions) {
            val config = RawConfig(arrayOf(RawConfig("Name", " profile "), RawConfig("Action", value)))
            assertEquals(DirectRimeManagementCommands.ProfileManager(" profile ", action),
                DirectRimeManagementCommands.profileManager(config))
        }
        for (value in listOf("", "CREATE", " switch", "rename ", "unknown")) {
            val config = RawConfig(arrayOf(RawConfig("Action", value)))
            assertEquals(DirectRimeManagementCommands.ProfileManager("",
                DirectRimeManagementCommands.ProfileAction.Unknown),
                DirectRimeManagementCommands.profileManager(config))
        }
        assertEquals(DirectRimeManagementCommands.ProfileManager("",
            DirectRimeManagementCommands.ProfileAction.Unknown),
            DirectRimeManagementCommands.profileManager(RawConfig()))
    }

    @Test fun profileHeaderIsCapturedButBranchFieldsAreReadAtTheirLaterExecutionPoint() {
        val config = RawConfig(arrayOf(
            RawConfig("Name", "original"), RawConfig("Action", "rename"),
            RawConfig("ExternalPath", "earlier path"), RawConfig("NewName", "earlier name"),
        ))
        val command = DirectRimeManagementCommands.profileManager(config)

        config["Name"].value = "later profile"
        config["Action"].value = "delete"
        config["ExternalPath"].value = " ../external directory "
        config["NewName"].value = " later name "

        assertEquals(DirectRimeManagementCommands.ProfileManager("original",
            DirectRimeManagementCommands.ProfileAction.Rename), command)
        assertEquals(" ../external directory ", DirectRimeManagementCommands.externalPath(config))
        assertEquals(" later name ", DirectRimeManagementCommands.newName(config))
        assertEquals("", DirectRimeManagementCommands.externalPath(RawConfig()))
        assertEquals("", DirectRimeManagementCommands.newName(RawConfig()))
    }

    @Test fun importDistinguishesAMissingArchiveFromAnEmptyValueAndDoesNotNormalizePaths() {
        val missing = assertThrows(IllegalStateException::class.java) {
            DirectRimeManagementCommands.importProfile(RawConfig(arrayOf(RawConfig("Destination", "target"))))
        }
        assertEquals("请选择方案 ZIP", missing.message)
        assertEquals(DirectRimeManagementCommands.ImportProfile("", ""),
            DirectRimeManagementCommands.importProfile(RawConfig(arrayOf(RawConfig("Archive", "")))))

        val config = RawConfig(arrayOf(
            RawConfig("Archive", " ../cache/profile.zip "), RawConfig("Destination", "  "),
            RawConfig("Archive", "ignored duplicate"),
        ))
        val command = DirectRimeManagementCommands.importProfile(config)
        config["Archive"].value = "changed.zip"
        config["Destination"].value = "changed destination"
        assertEquals(DirectRimeManagementCommands.ImportProfile(" ../cache/profile.zip ", "  "), command)
    }

    @Test fun schemaRowsPreserveSelectionOrderDuplicatesEmptyIdsAndTheThirdColumnRemainder() {
        val config = RawConfig(arrayOf(RawConfig("Items", listOf(
            "1\twanxiang\t万象",
            "0\tignored\t未勾选",
            "1\tonly-two-columns",
            "1\t\t空标识",
            "1\twanxiang\t重复",
            " 1\tleading-space\t不修剪标记",
            "1 \ttrailing-space\t不修剪标记",
            "1\tluna\t说明\t继续说明",
            "",
        ).joinToString("\r\n"))))
        val command = DirectRimeManagementCommands.schemaSelection(config)
        assertEquals(listOf("wanxiang", "", "wanxiang", "luna"), command.ids)
        config["Items"].value = "1\tchanged\t后来更改"
        assertEquals(listOf("wanxiang", "", "wanxiang", "luna"), command.ids)
        assertTrue(DirectRimeManagementCommands.schemaSelection(RawConfig()).ids.isEmpty())
    }
}
