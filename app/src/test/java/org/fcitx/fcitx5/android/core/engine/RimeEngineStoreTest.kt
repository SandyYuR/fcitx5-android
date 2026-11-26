/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test fun successfulActivationArmsAGuardOnEveryLaterStartup() {
        val repository = RimeEngineRepository(temporary.newFolder())
        repository.authorizePlugin(plugin())
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
        repository.select(selection, "rime")
        repository.beginActivation()
        repository.activationSucceeded(selection)

        val (guarded, pending) = repository.beginActivation()
        assertFalse(pending)
        assertEquals(selection, guarded.active)
        assertEquals(true, guarded.activation?.attempted)
        assertEquals(false, guarded.activation?.forceFullCheck)
        assertEquals("rime", guarded.builtinProfile)

        repository.activationSucceeded(selection)
        assertNull(repository.state().activation)

        repository.beginActivation()
        val (rolledBack, _) = repository.beginActivation()
        assertEquals(RimeEngineSelection.Builtin, rolledBack.active)
        assertNotNull(rolledBack.lastFallbackReason)
    }

    @Test fun successfulExternalSwitchKeepsPreviousEngineAsFallback() {
        val repository = RimeEngineRepository(temporary.newFolder())
        val first = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.first")
        val second = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.second")
        repository.authorizePlugin(plugin(first.id, "11".repeat(32), "22".repeat(32)))
        repository.authorizePlugin(plugin(second.id, "33".repeat(32), "44".repeat(32)))
        repository.select(first, "rime")
        repository.beginActivation()
        repository.activationSucceeded(first)
        repository.select(second)
        repository.beginActivation()
        repository.activationSucceeded(second)

        assertEquals(first, repository.state().fallback)
        repository.beginActivation()
        val (rolledBack, _) = repository.beginActivation()
        assertEquals(first, rolledBack.active)
        assertEquals(RimeEngineSelection.Builtin, rolledBack.fallback)
        assertEquals(first, rolledBack.activation?.selected)
        assertEquals(true, rolledBack.activation?.attempted)
        assertEquals(true, rolledBack.activation?.forceFullCheck)

        repository.activationSucceeded(first)
        assertNotNull("自动回退原因应保留给设置页", repository.state().lastFallbackReason)
    }

    @Test fun activeProfileUpdateIsOneGuardedStateWrite() {
        val repository = RimeEngineRepository(temporary.newFolder())
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
        repository.authorizePlugin(plugin())
        repository.select(selection, "rime")
        repository.beginActivation()
        repository.activationSucceeded(selection)

        assertEquals(true, repository.updateProfile(selection, "next-profile"))
        val updated = repository.state()
        assertEquals("next-profile", updated.plugins.single().profile)
        assertEquals(selection, updated.activation?.selected)
        assertEquals(false, updated.activation?.attempted)
        assertEquals(true, updated.activation?.forceFullCheck)
    }

    @Test fun explicitFailureRestoresPreviousExternalBehindANewGuard() {
        val repository = RimeEngineRepository(temporary.newFolder())
        val first = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.first")
        val second = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.second")
        repository.authorizePlugin(plugin(first.id, payload = "22".repeat(32)))
        repository.authorizePlugin(plugin(second.id, payload = "44".repeat(32)))
        repository.select(first, "rime")
        repository.beginActivation()
        repository.activationSucceeded(first)
        repository.select(second)
        repository.beginActivation()

        repository.activationFailed(second, "failed")
        val restored = repository.state()
        assertEquals(first, restored.active)
        assertEquals(RimeEngineSelection.Builtin, restored.fallback)
        assertEquals(first, restored.activation?.selected)
        assertEquals(false, restored.activation?.attempted)
        assertEquals(true, restored.activation?.forceFullCheck)
        assertNotNull(restored.lastFallbackReason)

        val (attempted, fullCheck) = repository.beginActivation()
        assertEquals(true, fullCheck)
        assertEquals(true, attempted.activation?.attempted)
        repository.activationSucceeded(first)
        assertNotNull("恢复成功后仍应向用户说明回退原因", repository.state().lastFallbackReason)
    }

    @Test fun switchingBackToBuiltinCanCompleteOrRecoverPreviousExternal() {
        fun activeExternal(): Pair<RimeEngineRepository, RimeEngineSelection> {
            val repository = RimeEngineRepository(temporary.newFolder())
            val external = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
            repository.authorizePlugin(plugin())
            repository.select(external, "rime")
            repository.beginActivation()
            repository.activationSucceeded(external)
            return repository to external
        }

        val (successful, external) = activeExternal()
        successful.select(RimeEngineSelection.Builtin)
        val (builtinAttempt, fullCheck) = successful.beginActivation()
        assertEquals(external, builtinAttempt.activation?.previous)
        assertEquals(true, fullCheck)
        successful.activationSucceeded(RimeEngineSelection.Builtin)
        assertEquals(RimeEngineSelection.Builtin, successful.state().active)
        assertEquals(RimeEngineSelection.Builtin, successful.state().fallback)

        val (failed, previous) = activeExternal()
        failed.select(RimeEngineSelection.Builtin)
        failed.beginActivation()
        val (rolledBack, retryFullCheck) = failed.beginActivation()
        assertEquals(previous, rolledBack.active)
        assertEquals(true, retryFullCheck)
        assertEquals(previous, rolledBack.activation?.selected)
    }

    @Test fun profileUpdatesPreserveOnlyActuallyKnownGoodFallbacks() {
        val repository = RimeEngineRepository(temporary.newFolder())
        val first = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.first")
        val second = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.second")
        repository.authorizePlugin(plugin(first.id, payload = "22".repeat(32)))
        repository.authorizePlugin(plugin(second.id, payload = "44".repeat(32)))
        repository.select(first, "rime")
        repository.beginActivation()
        repository.activationSucceeded(first)
        repository.select(second)
        repository.beginActivation()
        repository.activationSucceeded(second)

        assertEquals(true, repository.updateProfile(second, "second-next"))
        assertEquals(first, repository.state().activation?.previous)
        repository.beginActivation()
        repository.activationSucceeded(second)

        assertEquals(false, repository.updateProfile(first, "first-unverified"))
        val changedFallback = repository.state()
        assertEquals(RimeEngineSelection.Builtin, changedFallback.fallback)
        assertNull(changedFallback.activation)
        assertEquals("first-unverified",
            changedFallback.plugins.single { it.packageName == first.id }.profile)
    }

    @Test fun pendingTransitionRejectsCompetingMutations() {
        val repository = RimeEngineRepository(temporary.newFolder())
        val first = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.first")
        val second = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.second")
        repository.authorizePlugin(plugin(first.id, payload = "22".repeat(32)))
        repository.authorizePlugin(plugin(second.id, payload = "44".repeat(32)))
        repository.select(first, "rime")

        assertThrows(IllegalArgumentException::class.java) { repository.select(second) }
        assertThrows(IllegalArgumentException::class.java) { repository.updateProfile(first, "next") }
        assertThrows(IllegalArgumentException::class.java) { repository.requireProfileMutationIdle() }
        assertThrows(IllegalArgumentException::class.java) { repository.requireBuiltinForProfileSwitch() }
        assertThrows(IllegalArgumentException::class.java) {
            repository.authorizePlugin(plugin("org.example.third", payload = "66".repeat(32)))
        }
    }

    @Test fun profileAndAuthorizationGuardsCoverActiveAndFallbackOwners() {
        val repository = RimeEngineRepository(temporary.newFolder())
        val first = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.first")
        val second = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.second")
        repository.authorizePlugin(plugin(first.id, payload = "22".repeat(32), profile = "first-profile"))
        repository.authorizePlugin(plugin(second.id, payload = "44".repeat(32), profile = "second-profile"))
        repository.requireBuiltinForProfileSwitch()
        repository.requireProfileUnreferenced("unused")
        assertThrows(IllegalArgumentException::class.java) {
            repository.requireProfileUnreferenced("first-profile")
        }

        repository.select(first, "builtin-profile")
        repository.beginActivation()
        repository.activationSucceeded(first)
        assertEquals("first-profile", repository.activeExternalProfile())
        assertThrows(IllegalArgumentException::class.java) { repository.requireBuiltinForProfileSwitch() }
        assertThrows(IllegalArgumentException::class.java) {
            repository.requireProfileUnreferenced("builtin-profile")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.authorizePlugin(plugin(first.id, payload = "33".repeat(32), profile = "changed"))
        }

        repository.select(second)
        repository.beginActivation()
        repository.activationSucceeded(second)
        assertEquals(first, repository.state().fallback)
        assertThrows(IllegalArgumentException::class.java) {
            repository.authorizePlugin(plugin(first.id, payload = "33".repeat(32), profile = "changed"))
        }
        repository.revokePlugin(first.id)
        assertEquals(RimeEngineSelection.Builtin, repository.state().fallback)
    }

    @Test fun profileMutationLeaseRejectsCompetingThreadWithoutHoldingMonitor() {
        val repository = RimeEngineRepository(temporary.newFolder())
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
        repository.authorizePlugin(plugin())
        val executor = Executors.newSingleThreadExecutor()
        val lease = repository.profileMutationLease()
        try {
            val error = executor.submit<Throwable?> {
                runCatching { repository.select(selection, "rime") }.exceptionOrNull()
            }.get(2, TimeUnit.SECONDS)
            assertTrue(error is IllegalArgumentException)
            assertEquals(RimeEngineSelection.Builtin, repository.state().active)
        } finally {
            lease.close()
            executor.shutdownNow()
        }
        repository.select(selection, "rime")
        assertEquals(selection, repository.state().active)
    }

    @Test fun importedEvoFamilyRequiresTheExactAdmittedSoname() {
        assertTrue(resolveImportedFamily("librime-evo.so"))
        for (soname in listOf(null, "librime.so", "librime-evo-test.so", "LIBRIME-EVO.SO")) {
            assertFalse("soname=$soname", resolveImportedFamily(soname))
        }
    }

    private fun resolveImportedFamily(soname: String?): Boolean {
        val root = temporary.newFolder()
        val imported = File(root, "imported").apply { mkdirs() }
        val payload = File(imported, "admitted").apply { writeText("previously admitted payload") }
        val digest = RimeEngineElf.sha256(payload)
        val info = plugin(payload = digest).staticInfo.copy(size = payload.length(), soname = soname,
            sourceName = "librime-evo.so")
        assertTrue(payload.renameTo(File(imported, "$digest.so")))
        val record = ImportedRimeEngine(digest, "EVO engine", "$digest.so", info,
            RimeEngineRuntimeInfo("evo-1.0", 1024, emptyList()), "profile", 1)
        val selected = RimeEngineSelection(RimeEngineKind.IMPORTED, digest)
        File(root, "state.json").writeText(Json.encodeToString(RimeEngineState(active = selected, imported = listOf(record))))
        return (RimeEngineRepository(root).resolveActive(emptyList()) as ResolvedRimeEngine.External).evoFamily
    }

    @Test fun pluginEvoFamilyRequiresExactProviderPackageAndAllSonameFacts() {
        assertTrue((resolvePlugin() as ResolvedRimeEngine.External).evoFamily)
        val others = listOf(
            resolvePlugin(packageName = "org.example.librime_evo"),
            resolvePlugin(providerId = "example-librime-evo"),
            resolvePlugin(soname = "librime.so"),
            resolvePlugin(staticSoname = "librime.so"),
            resolvePlugin(authorizedStaticSoname = "librime.so"),
        )
        others.forEach { assertFalse((it as ResolvedRimeEngine.External).evoFamily) }
    }

    @Test fun evoIdentityNeverBypassesPluginAuthorization() {
        assertEquals(ResolvedRimeEngine.Builtin, resolvePlugin {
            it.copy(certificateSha256 = "33".repeat(32))
        })
        assertEquals(ResolvedRimeEngine.Builtin, resolvePlugin {
            it.copy(staticInfo = it.staticInfo.copy(sha256 = "44".repeat(32)))
        })
        assertEquals(ResolvedRimeEngine.Builtin, resolvePlugin {
            it.copy(providerId = "changed-provider")
        })
    }

    private fun resolvePlugin(
        packageName: String = "org.rime.plugin.sandyyur.librime_evo",
        providerId: String = "sandyyur-librime-evo",
        soname: String = "librime-evo.so",
        staticSoname: String? = soname,
        authorizedStaticSoname: String? = staticSoname,
        transform: (RimePluginEngineCandidate) -> RimePluginEngineCandidate = { it },
    ): ResolvedRimeEngine {
        val root = temporary.newFolder()
        val execution = temporary.newFolder()
        val source = temporary.newFile().apply { writeText("previously admitted provider payload") }
        val digest = RimeEngineElf.sha256(source)
        // Model an already published immutable payload, without invoking Android Os.chmod on the host.
        File(execution, "plugin-$digest.so").writeBytes(source.readBytes())
        val authorization = plugin(packageName, payload = digest).let {
            it.copy(providerId = providerId, displayName = "EVO", soname = soname,
                staticInfo = it.staticInfo.copy(size = source.length(), soname = authorizedStaticSoname),
                runtimeInfo = it.runtimeInfo.copy(librimeVersion = "evo-1.0"))
        }
        val repository = RimeEngineRepository(root, execution)
        repository.authorizePlugin(authorization)
        repository.select(RimeEngineSelection(RimeEngineKind.PLUGIN, packageName), "rime")
        val candidate = RimePluginEngineCandidate(packageName, "EVO", providerId, 1, 1, soname,
            "evo-1.0", null, authorization.certificateSha256, source.absolutePath,
            authorization.staticInfo.copy(soname = staticSoname))
        return repository.resolveActive(listOf(transform(candidate)))
    }

    private fun plugin(
        packageName: String = "org.example.engine",
        certificate: String = "11".repeat(32),
        payload: String = "22".repeat(32),
        profile: String = "plugin-profile"
    ) = PluginRimeEngineAuthorization(
        packageName = packageName,
        displayName = "Example",
        providerId = "example",
        certificateSha256 = certificate,
        payloadSha256 = payload,
        soname = "librime.so",
        staticInfo = RimeEngineStaticInfo(
            sha256 = payload, size = 1, abi = "arm64-v8a", bits = 64,
            soname = "librime.so", entrySymbol = "rime_get_api", neededLibraries = listOf("libc.so"),
            loadAlignment = 16384, sourceName = "librime.so"
        ),
        runtimeInfo = RimeEngineRuntimeInfo("1.0", 1024, emptyList()),
        profile = profile,
        authorizedAt = 1
    )
}
