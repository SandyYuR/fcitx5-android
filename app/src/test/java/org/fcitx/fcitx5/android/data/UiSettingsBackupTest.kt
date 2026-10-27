/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class UiSettingsBackupTest {
    private fun prefs(name: String) = RuntimeEnvironment.getApplication()
        .getSharedPreferences(name, Context.MODE_PRIVATE).apply { edit().clear().commit() }

    @Test fun snapshotOnlyContainsTypedBoundedAppearanceKeysAndClearsOldSecrets() {
        val source = prefs("backup-source")
        val target = prefs("backup-target")
        source.edit().putString("settings_ui_mode", "dark").putBoolean("settings_ui_pure_black", true)
            .putString("password", "fixture").putString("server_address", "private.example")
            .putString("settings_ui_recipe", "x".repeat(65)).putBoolean("settings_ui_palette", false).commit()
        target.edit().putString("old-secret", "fixture").commit()
        UiSettingsBackup.copy(source, target, clear = true)
        assertEquals(setOf("settings_ui_mode", "settings_ui_pure_black"), target.all.keys)
        assertEquals("dark", target.getString("settings_ui_mode", null))
    }
    @Test fun restoreIgnoresInjectedSecretsAndDoesNotOverwriteExistingAccountConfiguration() {
        val source = prefs("backup-restore")
        val target = prefs("backup-main")
        source.edit().putString("password", "injected").putString("settings_ui_mode", "light").commit()
        target.edit().putString("password", "existing").putString("server_address", "existing-host").commit()
        UiSettingsBackup.copy(source, target, clear = false)
        assertEquals("existing", target.getString("password", null))
        assertEquals("existing-host", target.getString("server_address", null))
        assertEquals("light", target.getString("settings_ui_mode", null))
    }
    @Test fun bothPlatformPoliciesAllowOnlyTheDedicatedSnapshot() {
        for ((filename, count) in listOf("full_backup_content.xml" to 1, "data_extraction_rules.xml" to 2)) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/xml/$filename"))
            val includes = document.getElementsByTagName("include")
            assertEquals(count, includes.length)
            for (i in 0 until includes.length) {
                val attrs = includes.item(i).attributes
                assertEquals("sharedpref", attrs.getNamedItem("domain").nodeValue)
                assertEquals("${UiSettingsBackup.FILE}.xml", attrs.getNamedItem("path").nodeValue)
            }
        }
    }
}
