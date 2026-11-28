/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import android.content.Context
import androidx.preference.PreferenceManager
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = org.fcitx.fcitx5.android.FcitxApplication::class,
    shadows = [org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow::class])
class HostsMappingTransactionTest {
    private lateinit var context: Context
    private val mapping = mapOf("github.com" to listOf("192.0.2.1"))

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        UpdateRepository.saveHostsSettings(context, true, "https://example.test/a")
    }

    @Test fun completionMergesTheCurrentEnabledValue() {
        val request = UpdatePrefs.beginHostsMappingUpdate(context)
        UpdateRepository.saveHostsEnabled(context, false)
        assertTrue(UpdatePrefs.completeHostsMappingUpdate(context, request, mapping, 123L))
        val current = UpdatePrefs.loadHostsConfig(context)
        assertFalse(current.enabled)
        assertEquals(mapping, current.mapping)
        assertEquals(123L, current.updatedAt)
    }

    @Test fun sourceSwitchClearsTheOldMappingAndRejectsAnAbaCompletion() {
        val request = UpdatePrefs.beginHostsMappingUpdate(context)
        assertTrue(UpdatePrefs.completeHostsMappingUpdate(context, request, mapping, 123L))
        UpdateRepository.saveHostsSource(context, "https://example.test/b")
        assertTrue(UpdatePrefs.loadHostsConfig(context).mapping.isEmpty())
        assertEquals(0L, UpdatePrefs.loadHostsConfig(context).updatedAt)
        UpdateRepository.saveHostsSettings(context, false, "https://example.test/a")
        assertFalse(UpdatePrefs.completeHostsMappingUpdate(context, request, mapping, 456L))
        assertFalse(UpdatePrefs.loadHostsConfig(context).enabled)
        assertTrue(UpdatePrefs.loadHostsConfig(context).mapping.isEmpty())
    }

    @Test fun anOlderRequestCannotOverwriteANewerCompletion() {
        val older = UpdatePrefs.beginHostsMappingUpdate(context)
        val newer = UpdatePrefs.beginHostsMappingUpdate(context)
        assertTrue(UpdatePrefs.completeHostsMappingUpdate(context, newer, mapping, 456L))
        assertFalse(UpdatePrefs.completeHostsMappingUpdate(context, older, emptyMap(), 123L))
        assertEquals(mapping, UpdatePrefs.loadHostsConfig(context).mapping)
        assertEquals(456L, UpdatePrefs.loadHostsConfig(context).updatedAt)
    }
}
