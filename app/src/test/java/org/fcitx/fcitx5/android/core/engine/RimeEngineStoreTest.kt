/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RimeEngineStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unfinishedActivationRollsBackExactlyOnce() {
        val repository = RimeEngineRepository(temporary.newFolder())
        repository.authorizePlugin(plugin())
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
        repository.select(selection, "builtin-profile")

        val (attempted, firstIsActivation) = repository.beginActivation()
        assertEquals(selection, attempted.active)
        assertEquals(true, firstIsActivation)
        assertEquals(true, attempted.activation?.attempted)

        val (rolledBack, secondIsActivation) = repository.beginActivation()
        assertFalse(secondIsActivation)
        assertEquals(RimeEngineSelection.Builtin, rolledBack.active)
        assertNull(rolledBack.activation)
        assertNotNull(rolledBack.lastFallbackReason)
        assertEquals(true, rolledBack.restoreBuiltinProfile)

        repository.activationSucceeded(RimeEngineSelection.Builtin)
        val restored = repository.state()
        assertFalse(restored.restoreBuiltinProfile)
        assertNotNull("回退原因应保留给设置页显示", restored.lastFallbackReason)
    }

    @Test fun successfulActivationBecomesStable() {
        val repository = RimeEngineRepository(temporary.newFolder())
        repository.authorizePlugin(plugin())
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
        repository.select(selection, "rime")
        repository.beginActivation()
        repository.activationSucceeded(selection)

        val (stable, pending) = repository.beginActivation()
        assertFalse(pending)
        assertEquals(selection, stable.active)
        assertNull(stable.activation)
        assertEquals("rime", stable.builtinProfile)
    }

    private fun plugin() = PluginRimeEngineAuthorization(
        packageName = "org.example.engine",
        displayName = "Example",
        providerId = "example",
        certificateSha256 = "11".repeat(32),
        payloadSha256 = "22".repeat(32),
        soname = "librime.so",
        staticInfo = RimeEngineStaticInfo(
            sha256 = "22".repeat(32), size = 1, abi = "arm64-v8a", bits = 64,
            soname = "librime.so", entrySymbol = "rime_get_api", neededLibraries = listOf("libc.so"),
            loadAlignment = 16384, sourceName = "librime.so"
        ),
        runtimeInfo = RimeEngineRuntimeInfo("1.0", 1024, emptyList()),
        profile = "plugin-profile",
        authorizedAt = 1
    )
}
