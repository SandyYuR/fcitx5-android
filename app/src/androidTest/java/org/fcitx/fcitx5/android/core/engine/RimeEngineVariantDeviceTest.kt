/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fcitx.directrime.Native
import org.fcitx.directrime.ProfileArchive
import org.fcitx.fcitx5.android.core.data.DataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class RimeEngineVariantDeviceTest {
    @Test fun importedEnginePassesDisposableProbeAndRunsThroughDirectBridge(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = InstrumentationRegistry.getArguments().getString("enginePath")
            ?: error("Pass -e enginePath /data/local/tmp/<file>.so")
        require(source.matches(Regex("/data/local/tmp/[A-Za-z0-9._-]+\\.so")))
        val engine = File(context.codeCacheDir, "device-rime-engine.so")
        val repositoryRoot = File(context.cacheDir, "device-imported-engine-${System.nanoTime()}")
        val executionRoot = File(context.codeCacheDir, "device-imported-exec-${System.nanoTime()}")
        instrumentation.uiAutomation.executeShellCommand("cat $source").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(engine).use { output -> input.copyTo(output); output.fd.sync() }
            }
        }
        try {
            val staticInfo = RimeEngineElf.inspect(engine)
            val runtime = RimeEngineProbeService.probe(context, engine, staticInfo)
            assertTrue(runtime.apiDataSize > 0)
            assertTrue(runtime.librimeVersion.isNotBlank())
            assertTrue("探测未运行在 Android 隔离 UID", "isolated-uid" in runtime.capabilities)
            assertTrue("探测未使用 sealed memfd", "sealed-memfd" in runtime.capabilities)

            val repository = RimeEngineRepository(repositoryRoot, executionRoot)
            val record = repository.installImported(engine, "Device imported engine", staticInfo,
                runtime, "device-profile")
            val selection = RimeEngineSelection(RimeEngineKind.IMPORTED, record.id)
            repository.select(selection, "rime")
            repository.beginActivation()
            var resolved = repository.resolveActive(emptyList()) as ResolvedRimeEngine.External
            assertNotEquals(engine.canonicalPath, File(resolved.filePath).canonicalPath)
            assertEquals(staticInfo.sha256, RimeEngineElf.sha256(File(resolved.filePath)))
            // A damaged code-cache copy is never trusted; it is republished from the immutable
            // managed payload before native loading.
            assertTrue(File(resolved.filePath).setWritable(true))
            File(resolved.filePath).writeBytes(byteArrayOf(0, 1, 2, 3))
            resolved = repository.resolveActive(emptyList()) as ResolvedRimeEngine.External
            assertEquals(staticInfo.sha256, RimeEngineElf.sha256(File(resolved.filePath)))

            DataManager.sync()
            val user = File(context.cacheDir, "device-rime-profile-${System.nanoTime()}")
            assertTrue(user.mkdirs())
            var started = false
            try {
                ParcelFileDescriptor.open(File(resolved.filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    val configured = Native.configureExternal(descriptor.fd,
                        resolved.entrySymbol, resolved.payloadSha256)
                    assertEquals(runtime.librimeVersion, configured[0])
                }
                Native.start(File(DataManager.dataDir, "usr/share/rime-data").absolutePath,
                    user.absolutePath, "device-test", fullCheck = true)
                started = true
            } finally {
                if (started) Native.stop()
                user.deleteRecursively()
            }
        } finally {
            engine.delete()
            repositoryRoot.deleteRecursively()
            executionRoot.deleteRecursively()
        }
    }

    @Test fun installedProviderIsDiscoveredProbedAndRunsThroughDirectBridge(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = InstrumentationRegistry.getArguments().getString("providerPackage")
            ?: "org.rime.plugin.sandyyur.librime_evo"
        val profileSource = InstrumentationRegistry.getArguments().getString("profilePath")
            ?: error("Pass -e profilePath /data/local/tmp/<profile>.zip")
        require(profileSource.matches(Regex("/data/local/tmp/[A-Za-z0-9._-]+\\.zip")))
        val candidate = RimeEnginePlugins.discover(context)
            .singleOrNull { it.packageName == packageName }
            ?: error("Provider was not discovered: $packageName")
        assertEquals(1, candidate.apiVersion)
        assertEquals(1, candidate.rimeApiAbi)
        assertTrue(candidate.certificateSha256.isNotBlank())
        assertTrue(candidate.staticInfo.sha256.isNotBlank())

        val engine = File(candidate.libraryPath)
        val runtime = RimeEngineProbeService.probe(context, engine, candidate.staticInfo)
        assertTrue(runtime.apiDataSize > 0)
        assertTrue(runtime.librimeVersion.isNotBlank())
        assertTrue("探测未运行在 Android 隔离 UID", "isolated-uid" in runtime.capabilities)
        assertTrue("探测未使用 sealed memfd", "sealed-memfd" in runtime.capabilities)
        val verified = RimeEnginePlugins.requireCurrent(context, candidate)
        val repositoryRoot = File(context.cacheDir, "device-provider-engine-${System.nanoTime()}")
        val executionRoot = File(context.codeCacheDir, "device-provider-exec-${System.nanoTime()}")
        var user: File? = null
        var started = false
        try {
            val repository = RimeEngineRepository(repositoryRoot, executionRoot)
            val authorization = PluginRimeEngineAuthorization(
                packageName = verified.packageName,
                displayName = verified.displayName,
                providerId = verified.providerId,
                certificateSha256 = verified.certificateSha256,
                payloadSha256 = verified.staticInfo.sha256,
                soname = verified.soname,
                staticInfo = verified.staticInfo,
                runtimeInfo = runtime,
                profile = "device-provider-profile",
                authorizedAt = System.currentTimeMillis()
            )
            repository.authorizePlugin(authorization)
            val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, verified.packageName)
            repository.select(selection, "rime")
            repository.beginActivation()
            val resolved = repository.resolveActive(listOf(verified)) as ResolvedRimeEngine.External
            assertNotEquals(engine.canonicalPath, File(resolved.filePath).canonicalPath)
            assertTrue(File(resolved.filePath).canonicalPath.startsWith(executionRoot.canonicalPath + File.separator))
            assertEquals(verified.staticInfo.sha256, RimeEngineElf.sha256(File(resolved.filePath)))

            DataManager.sync()
            val profile = File(context.cacheDir, "provider-rime-profile-${System.nanoTime()}")
            user = profile
            instrumentation.uiAutomation.executeShellCommand("cat $profileSource").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    ProfileArchive.extract(input, profile)
                }
            }
            ParcelFileDescriptor.open(File(resolved.filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val configured = Native.configureExternal(descriptor.fd,
                    resolved.entrySymbol, resolved.payloadSha256)
                assertEquals(runtime.librimeVersion, configured[0])
            }
            Native.start(File(DataManager.dataDir, "usr/share/rime-data").absolutePath,
                profile.absolutePath, "provider-device-test", fullCheck = true)
            started = true
            assertTrue("万象 EVO 方案没有部署成功", "wanxiang" in Native.schemas())
            val generation = System.nanoTime()
            var snapshot = Native.beginInput(generation, "provider-wanxiang-evo-test", noLearning = true)
            assertTrue("万象 EVO 会话不可用：${snapshot.gateMessage}", snapshot.ready)
            assertEquals("wanxiang", snapshot.schema)
            "ni".forEach { snapshot = Native.key(generation, it.code, 0, release = false) }
            assertTrue("输入 ni 后没有候选", snapshot.candidates.isNotEmpty())
            val firstCandidate = snapshot.candidates.first()
            var selected = Native.action(generation, snapshot.revision, 1, 0, "")
            if (selected.commit.isEmpty()) {
                selected = Native.action(generation, selected.revision, 10, 0, "")
            }
            assertTrue("首选候选没有上屏：$firstCandidate", selected.commit.isNotEmpty())
            Native.end(generation)

            // An ordinary daemon stop/start keeps the native Engine object alive. The same
            // authorized DSO must remain reusable without forcing a process restart.
            Native.stop()
            started = false
            ParcelFileDescriptor.open(File(resolved.filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val configured = Native.configureExternal(descriptor.fd,
                    resolved.entrySymbol, resolved.payloadSha256)
                assertEquals(runtime.librimeVersion, configured[0])
            }
            Native.start(File(DataManager.dataDir, "usr/share/rime-data").absolutePath,
                profile.absolutePath, "provider-device-restart-test", fullCheck = false)
            started = true
            val restartedGeneration = System.nanoTime()
            val restarted = Native.beginInput(restartedGeneration,
                "provider-wanxiang-evo-restart", noLearning = true)
            assertTrue("外部引擎进程内重启后会话不可用：${restarted.gateMessage}", restarted.ready)
            assertEquals("wanxiang", restarted.schema)
            Native.end(restartedGeneration)
        } finally {
            if (started) Native.stop()
            user?.deleteRecursively()
            repositoryRoot.deleteRecursively()
            executionRoot.deleteRecursively()
        }
    }
}
