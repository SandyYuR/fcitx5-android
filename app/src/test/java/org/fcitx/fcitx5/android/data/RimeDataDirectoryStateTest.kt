package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Test

class RimeDataDirectoryStateTest {
    private fun hex(value: String) = value.toByteArray(Charsets.UTF_8)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun encodedNamesAndPathsPreserveSeparatorsAndUnicode() {
        val name = "中文|方案"
        val path = "/storage/1234-5678/Rime|同步/ trailing "
        assertEquals(mapOf(name to path), RimeDataDirectoryState.decodePaths(
            "${hex(name)}|${hex(path)}", "", listOf(name)
        ))
    }

    @Test fun legacyFormatUsesTheLongestRegisteredName() {
        assertEquals(mapOf("foo|bar" to "/storage/Rime|sync"),
            RimeDataDirectoryState.decodePaths(null, "foo|bar|/storage/Rime|sync", listOf("foo", "foo|bar")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedHexDoesNotFallBackToAStalePath() {
        RimeDataDirectoryState.decodePaths("zz|2f", "rime|/storage/old", listOf("rime"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativePathsAreRejected() {
        RimeDataDirectoryState.decodePaths("${hex("rime")}|${hex("../private")}", "", listOf("rime"))
    }

    @Test fun portableConfigQuotesIniEscapesAndClearsExternalRegistrations() {
        val state = RimeDataDirectoryState("foo|bar", listOf("rime", "foo|bar", "a\"b"), emptyMap())
        assertEquals("Current=\"foo|bar\"\nProfiles=\"rime\\nfoo|bar\\na\\\"b\"\nExternalPaths=\nExternalPathsV2=\n",
            state.portableConfig())
    }

    @Test fun profileNameValidationAllowsPipesButRejectsTraversalAndControlCharacters() {
        assertTrue(RimeDataDirectoryState.isValidName("foo|bar"))
        assertFalse(RimeDataDirectoryState.isValidName("../rime"))
        assertFalse(RimeDataDirectoryState.isValidName("a\nb"))
        assertFalse(RimeDataDirectoryState.isValidName("中".repeat(22)))
    }

    @Test fun selectedExternalPathsUseTheEngineEncodingAndPreservePipeNames() {
        val config = RimeDataDirectoryState.externalPathConfig(mapOf("中文|目录" to "/storage/emulated/0/Rime|同步"))
        assertTrue(config.startsWith("ExternalPaths=\"\"\n"))
        val encoded = config.lineSequence().first { it.startsWith("ExternalPathsV2=") }
            .substringAfter('=').removeSurrounding("\"")
        assertEquals(mapOf("中文|目录" to "/storage/emulated/0/Rime|同步"),
            RimeDataDirectoryState.decodePaths(encoded, "", listOf("中文|目录")))
    }
}
