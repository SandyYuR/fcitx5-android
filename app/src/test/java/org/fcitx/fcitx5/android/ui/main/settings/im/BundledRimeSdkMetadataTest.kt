/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class, qualifiers = "en")
class BundledRimeSdkMetadataTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val commit = "0123456789abcdef0123456789abcdef01234567"
    private val tree = "fedcba9876543210fedcba9876543210fedcba98"

    @Test fun legacySdkShowsItsCommitWithoutInventingAFamily() {
        val metadata = BundledRimeSdkMetadata.parse("""{"schema_version":1,"librime_commit":"$commit"}""")
        assertEquals("Built-in Rime", metadata.displayName(context))
        assertEquals("SDK family: Unknown / not declared\nSource commit: 0123456789ab", metadata.description(context))
    }

    @Test fun developmentSdkShowsItsBaseAndSourceTreeSeparately() {
        val metadata = BundledRimeSdkMetadata.parse("""{
            "schema_version":1,"engine_family":"evo","librime_commit":"$commit",
            "development":true,"source_tree":"$tree"
        }""")
        assertEquals("Built-in librime-evo", metadata.displayName(context))
        assertEquals("SDK family: evo\nBase commit: 0123456789ab\nDevelopment build · Source tree: fedcba987654",
            metadata.description(context))
        assertFalse(metadata.description(context).contains("Source commit:"))
    }

    @Test fun missingOrMalformedMetadataClearlyReportsUnknown() {
        for (json in listOf("", "not JSON", "[]", "{}", """{"schema_version":2,"engine_family":"evo","librime_commit":"$commit"}""")) {
            val metadata = BundledRimeSdkMetadata.parse(json)
            assertEquals("Built-in Rime", metadata.displayName(context))
            assertEquals("SDK family: Unknown / not declared\nSource commit: Unknown / not declared", metadata.description(context))
        }
    }

    @Test fun invalidRevisionsAndNonStringFieldsAreNotPresentedAsProvenance() {
        val metadata = BundledRimeSdkMetadata.parse("""{
            "schema_version":1,"engine_family":42,"librime_commit":"$commit-dirty",
            "development":true,"source_tree":12345
        }""")
        assertNull(metadata.family)
        assertNull(metadata.commit)
        assertTrue(metadata.description(context).contains("Base commit: Unknown / not declared"))
        assertTrue(metadata.description(context).contains("Development build · Source tree: Unknown / not declared"))
    }

    @Test fun anotherFamilyKeepsItsDeclarationAndDoesNotBecomeEvo() {
        val metadata = BundledRimeSdkMetadata.parse("""{"schema_version":1,"engine_family":"custom-rime","librime_commit":"${commit.uppercase()}"}""")
        assertEquals("Built-in Rime", metadata.displayName(context))
        assertEquals("SDK family: custom-rime\nSource commit: 0123456789ab", metadata.description(context))
    }

    @Test fun apkContainsTheSdkManifestWithItsActualCommit() {
        val text = context.assets.open("bundled/rime-sdk-manifest.json").bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(text).jsonObject
        val sdkCommit = json.getValue("librime_commit").jsonPrimitive.content
        assertTrue(sdkCommit.matches(Regex("[0-9a-f]{40}")))
        assertEquals(sdkCommit, BundledRimeSdkMetadata.parse(text).commit)
    }
}
