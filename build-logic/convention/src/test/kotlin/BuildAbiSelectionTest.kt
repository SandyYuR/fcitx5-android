/* SPDX-License-Identifier: LGPL-2.1-or-later */
import org.junit.Assert.*
import org.junit.Test

class BuildAbiSelectionTest {
    private val supported = linkedSetOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

    @Test fun unspecifiedAbisKeepAllSupportedArchitectures() {
        assertEquals(supported.toList(), selectBuildAbis(null, supported))
    }

    @Test fun whitespaceAndDuplicateEntriesAreNormalized() {
        assertEquals(listOf("x86_64", "arm64-v8a"), selectBuildAbis(" x86_64 , arm64-v8a, x86_64 ", supported))
    }

    @Test fun everySupportedAbiCanBeSelectedIndividually() {
        supported.forEach { assertEquals(listOf(it), selectBuildAbis(it, supported)) }
    }

    @Test fun emptyAndUnknownSelectionsFailBeforeAgpConfiguration() {
        for (value in listOf("", " ", ",", "x86_64,", ",arm64-v8a", "x86_64,,arm64-v8a", "arm64", "mips")) {
            assertThrows(IllegalArgumentException::class.java) { selectBuildAbis(value, supported) }
        }
    }

    @Test fun bundleTasksAreRecognizedWithAbsoluteRelativeOrNoProjectPrefix() {
        for (task in listOf(":app:bundleFxRelease", "app:bundleFxRelease", "bundleFxRelease")) {
            assertTrue(task, requestsBundle(listOf(task), ":app"))
        }
    }

    @Test fun apkTasksAndOtherProjectsDoNotDisableThisProjectsSplits() {
        for (task in listOf(":app:assembleFxRelease", "assembleFxDebug", ":other:bundleRelease")) {
            assertFalse(task, requestsBundle(listOf(task), ":app"))
        }
    }
}
