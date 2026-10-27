/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import android.app.Application
import android.content.Context
import org.fcitx.fcitx5.android.clipboardsync.network.SyncServerAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class SyncServerPreferencesTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("transport-fixture", Context.MODE_PRIVATE).apply { edit().clear().commit() }

    @Test fun aFreshConfigurationHasNoImplicitLanServer() {
        val prefs = prefs(); SyncServerPreferences.migrate(prefs)
        assertFalse(SyncServerPreferences.hasConfiguredCurrent(prefs))
        assertFalse(prefs.contains("server_address"))
        assertFalse(prefs.contains("server_address_oneclip"))
    }
    @Test fun existingSchemeLessAddressesBecomeExplicitHttpWithoutChangingCredentials() {
        val prefs = prefs()
        prefs.edit().putString("server_address", "old.example:5033").putString("server_profile_type", "syncclipboard")
            .putString("username", "fixture-user").putString("password", "fixture-password").commit()
        SyncServerPreferences.migrate(prefs)
        assertEquals("http://old.example:5033", prefs.getString("server_address", ""))
        assertEquals("fixture-user", prefs.getString("username", ""))
        assertEquals("fixture-password", prefs.getString("password", ""))
    }
    @Test fun existingSecureAddressesStaySecureAndMigrationRunsOnlyOnce() {
        val prefs = prefs()
        prefs.edit().putString("server_address", "https://secure.example").commit()
        SyncServerPreferences.migrate(prefs)
        assertEquals("https://secure.example", prefs.getString("server_address", ""))
        prefs.edit().putString("server_address", "new.example").commit()
        SyncServerPreferences.migrate(prefs)
        assertEquals("https://new.example", SyncServerAddress.normalize(prefs.getString("server_address", "")!!))
    }
    @Test fun oldSelectedImplicitDefaultsArePreservedWithoutCreatingOtherEndpoints() {
        val prefs = prefs()
        prefs.edit().putString("server_profile_type", "oneclip").putBoolean("quick_sync", true).commit()
        SyncServerPreferences.migrate(prefs)
        assertEquals("http://192.168.10.45:8899", prefs.getString("server_address", ""))
        assertFalse(prefs.contains("server_address_clipcascade"))
        assertFalse(prefs.contains("server_address_syncclipboard"))
        assertTrue(SyncServerPreferences.hasConfiguredCurrent(prefs))
    }
    @Test fun allStoredProfileAddressesAreMigratedWithoutRewritingExplicitSchemes() {
        val prefs = prefs()
        prefs.edit().putString("server_address_oneclip", "one.example:8899")
            .putString("server_address_clipcascade", "https://secure.example")
            .putString("server_address_custom", "http://local.example").commit()
        SyncServerPreferences.migrate(prefs)
        assertEquals("http://one.example:8899", prefs.getString("server_address_oneclip", ""))
        assertEquals("https://secure.example", prefs.getString("server_address_clipcascade", ""))
        assertEquals("http://local.example", prefs.getString("server_address_custom", ""))
    }
    @Test fun anUnknownLegacyProfileDoesNotCreateANewImplicitEndpoint() {
        val prefs = prefs(); prefs.edit().putString("server_profile_type", "unsupported").commit()
        SyncServerPreferences.migrate(prefs)
        assertFalse(SyncServerPreferences.hasConfiguredCurrent(prefs))
        assertFalse(prefs.contains("server_address"))
    }
}
