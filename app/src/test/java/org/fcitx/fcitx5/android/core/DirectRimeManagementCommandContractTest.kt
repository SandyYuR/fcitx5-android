/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.engine.PluginRimeEngineAuthorization
import org.fcitx.fcitx5.android.core.engine.RimeEngineKind
import org.fcitx.fcitx5.android.core.engine.RimeEngineRepository
import org.fcitx.fcitx5.android.core.engine.RimeEngineRuntimeInfo
import org.fcitx.fcitx5.android.core.engine.RimeEngineSelection
import org.fcitx.fcitx5.android.core.engine.RimeEngineStaticInfo
import org.fcitx.fcitx5.android.core.engine.RimeEngineStore
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the existing dispatcher, maintenance and repository guards with no native engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class DirectRimeManagementCommandContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher
    private lateinit var repository: RimeEngineRepository
    private var previousRepository: RimeEngineRepository? = null
    private var previousDirectBoot = false

    @Before fun prepare() {
        previousDirectBoot = FcitxApplication.getInstance().isDirectBootMode
        previousRepository = ReflectionHelpers.getField(RimeEngineStore, "instance")
        repository = RimeEngineRepository(temporary.newFolder("engines"))
        ReflectionHelpers.setField(RimeEngineStore, "instance", repository)
        NativeTestShadow.delegate = object : NativeTestDelegate {}
    }

    private fun createBackend(directBoot: Boolean = false): DirectRimeConfig {
        ReflectionHelpers.setField(FcitxApplication.getInstance(), "isDirectBootMode", directBoot)
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        return ReflectionHelpers.getField(backend, "config")
    }

    @After fun close() {
        if (::backend.isInitialized) {
            backend.lifeCycleScope.cancel()
            ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
            val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
            ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
            owner.close()
        }
        ReflectionHelpers.setField(RimeEngineStore, "instance", previousRepository)
        ReflectionHelpers.setField(FcitxApplication.getInstance(), "isDirectBootMode", previousDirectBoot)
    }

    @Test fun anUnknownPathDoesNotEnterMaintenanceOrChangePublicationAndInputGeneration() = runBlocking {
        val config = createBackend().apply { error = "previous error" }
        val context = backend.candidateActionContext
        val client = backend.clientPreeditCached
        val panel = backend.inputPanelCached
        val ime = backend.inputMethodEntryCached
        val actions = backend.statusAreaActionsCached

        backend.setAddonSubConfig("rime", "unknown-management-path", RawConfig())

        assertEquals(context, backend.candidateActionContext)
        assertFalse(ReflectionHelpers.getField<Boolean>(backend, "maintenance"))
        assertSame(client, backend.clientPreeditCached)
        assertSame(panel, backend.inputPanelCached)
        assertSame(ime, backend.inputMethodEntryCached)
        assertSame(actions, backend.statusAreaActionsCached)
        assertEquals("previous error", config.error)
    }

    @Test fun anUnknownProfileActionStillUsesTheMutationLeaseAndRecordsItsError() = runBlocking {
        val config = createBackend().apply { error = "previous error" }
        val generation = backend.inputSession

        val failure = runCatching {
            backend.setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(
                RawConfig("Name", "rime"), RawConfig("Action", "unknown"),
            )))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("无效目录操作", failure?.message)
        assertEquals("无效目录操作", config.error)
        assertTrue(backend.inputSession > generation)
        assertFalse(ReflectionHelpers.getField<Boolean>(backend, "maintenance"))
        repository.requireProfileMutationIdle()
    }

    @Test fun aBusyProfileLeaseTakesPriorityOverAnUnknownProfileAction() = runBlocking {
        val config = createBackend()
        repository.profileMutationLease().use {
            val failure = runCatching {
                backend.setAddonSubConfig("rime", "profile-manager", RawConfig())
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("引擎或工作目录正在切换，请稍后重试", failure?.message)
            assertEquals(failure?.message, config.error)
        }
        repository.requireProfileMutationIdle()
    }

    @Test fun directBootGuardsTakePriorityOverMissingOrUnknownManagementFields() = runBlocking {
        val config = createBackend(directBoot = true).apply { error = "previous error" }
        val messages = mapOf(
            "profile-manager" to "首次解锁前不能管理 Rime 工作目录",
            "import-profile" to "首次解锁前不能导入 Rime 工作目录",
        )
        for ((path, message) in messages) {
            val failure = runCatching { backend.setAddonSubConfig("rime", path, RawConfig()) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(message, failure?.message)
            assertEquals("previous error", config.error)
            assertFalse(ReflectionHelpers.getField<Boolean>(backend, "maintenance"))
        }
    }

    @Test fun importChecksTheBusyMutationGuardBeforeReportingAMissingArchive() = runBlocking {
        createBackend()
        repository.profileMutationLease().use {
            val failure = runCatching {
                backend.setAddonSubConfig("rime", "import-profile", RawConfig())
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("引擎或工作目录正在切换，请稍后重试", failure?.message)
        }
    }

    @Test fun importChecksTheActiveEngineBeforeReportingAMissingArchive() = runBlocking {
        createBackend()
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
        repository.authorizePlugin(PluginRimeEngineAuthorization(
            packageName = selection.id, displayName = "Example", providerId = "example",
            certificateSha256 = "11".repeat(32), payloadSha256 = "22".repeat(32), soname = "librime.so",
            staticInfo = RimeEngineStaticInfo("22".repeat(32), 1, "arm64-v8a", 64, "librime.so",
                null, "rime_get_api", listOf("libc.so"), 16384, "librime.so"),
            runtimeInfo = RimeEngineRuntimeInfo("1.0", 1024, emptyList()), profile = "rime", authorizedAt = 1,
        ))
        repository.select(selection, "rime")
        repository.beginActivation()
        repository.activationSucceeded(selection)

        val failure = runCatching {
            backend.setAddonSubConfig("rime", "import-profile", RawConfig())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("外部引擎正在使用；请在“Rime 引擎变体”页面更改它绑定的工作目录", failure?.message)
    }

    @Test fun importReportsTheMissingArchiveAfterTheExistingGuardsPass() = runBlocking {
        createBackend()

        val failure = runCatching {
            backend.setAddonSubConfig("rime", "import-profile", RawConfig())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("请选择方案 ZIP", failure?.message)
        assertFalse(ReflectionHelpers.getField<Boolean>(backend, "maintenance"))
    }
}
