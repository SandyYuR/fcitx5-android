/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.engine.ImportedRimeEngine
import org.fcitx.fcitx5.android.core.engine.RimeEngineElf
import org.fcitx.fcitx5.android.core.engine.RimeEngineKind
import org.fcitx.fcitx5.android.core.engine.RimeEngineRepository
import org.fcitx.fcitx5.android.core.engine.RimeEngineRuntimeInfo
import org.fcitx.fcitx5.android.core.engine.RimeEngineSelection
import org.fcitx.fcitx5.android.core.engine.RimeEngineState
import org.fcitx.fcitx5.android.core.engine.RimeEngineStaticInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowParcelFileDescriptor
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.time.Duration

/** Actual activation metadata and directory transactions; native execution is the shared shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [NativeTestShadow::class, DirectRimeEngineOperationsTest.ObservedParcelFileDescriptor::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DirectRimeEngineOperationsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var config: DirectRimeConfig
    private lateinit var repository: RimeEngineRepository
    private lateinit var repositoryRoot: File
    private lateinit var engine: DirectRimeEngineOperations
    private lateinit var native: EngineNative
    private lateinit var owner: EngineOwner
    private lateinit var first: RimeEngineSelection
    private lateinit var second: RimeEngineSelection
    private val calls = mutableListOf<String>()
    private val configuredDescriptors = mutableListOf<FileDescriptor>()

    /** Observe the existing Robolectric open/getFd/close behavior without replacing its fd. */
    @Implements(ParcelFileDescriptor::class)
    class ObservedParcelFileDescriptor : ShadowParcelFileDescriptor() {
        companion object {
            var lastRead: Pair<Int, ObservedParcelFileDescriptor>? = null
        }

        @Implementation override fun getFd(): Int = super.getFd().also { lastRead = it to this }
        val hostDescriptor: FileDescriptor get() = super.getFileDescriptor()
        val payload: File get() = ReflectionHelpers.getField(this, "origFile")
        val openMode: Int get() = ReflectionHelpers.getField(this, "origMode")
    }

    private inner class EngineOwner : DirectRimeEngineOperations.Owner {
        override var started = false
            set(value) { calls += "running:$value"; field = value }
        var hasSnapshot = true
        var startupError = "previous startup error"
        var onRefresh: () -> Unit = {}
        override fun clearSnapshot() { calls += "clear-snapshot"; hasSnapshot = false }
        override fun clearStartupError() { calls += "clear-error"; startupError = "" }
        override fun refreshSchemas() { calls += "schemas"; onRefresh() }
        override fun resetSlotPresentationCaches() { calls += "reset-slot-caches" }
    }

    private inner class EngineNative : NativeTestDelegate {
        var onStop: () -> Unit = {}
        var onConfigure: (String) -> Unit = {}
        var onSelectBuiltin: () -> Unit = {}
        var onStart: (Boolean) -> Unit = {}
        override fun stop() { calls += "stop:${owner.started}:${owner.hasSnapshot}"; onStop() }
        override fun selectBuiltin() { calls += "builtin"; onSelectBuiltin() }
        override fun configureExternal(fd: Int, entrySymbol: String, identity: String) {
            val (openedFd, opened) = checkNotNull(ObservedParcelFileDescriptor.lastRead)
            ObservedParcelFileDescriptor.lastRead = null
            assertEquals(openedFd, fd)
            // Robolectric 4.17 exposes the host Java fd field. On Windows the valid handle
            // need not populate that integer; this is not a real Android/Linux fd check.
            assertTrue(opened.hostDescriptor.valid())
            assertEquals(ParcelFileDescriptor.MODE_READ_ONLY, opened.openMode)
            assertEquals(File(repositoryRoot, "imported/$identity.so").canonicalFile, opened.payload.canonicalFile)
            assertEquals(identity, RimeEngineElf.sha256(opened.payload))
            configuredDescriptors += opened.hostDescriptor
            assertEquals("rime_get_api", entrySymbol)
            calls += "external:$identity"
            onConfigure(identity)
        }
        override fun start(shared: String, user: String, version: String, fullCheck: Boolean) {
            assertEquals("test-version", version)
            assertEquals(config.user.absolutePath, user)
            calls += "start:${config.current}:$fullCheck"
            onStart(fullCheck)
        }
    }

    @Before fun prepare() {
        ObservedParcelFileDescriptor.lastRead = null
        val files = temporary.newFolder("files")
        val external = temporary.newFolder("external")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getExternalFilesDir(type: String?) = external
        }
        config = DirectRimeConfig(context).apply { load(); saveDirectories(); create("first", ""); create("second", "") }
        repositoryRoot = temporary.newFolder("engines")
        repository = RimeEngineRepository(repositoryRoot)
        // Previously admitted imported payloads. Admission/ELF/probe validation belongs to the
        // existing engine-store tests; every selection and activation below uses the real store.
        val importedRoot = File(repositoryRoot, "imported").apply { mkdirs() }
        val records = listOf("first", "second").mapIndexed { index, profile ->
            val payload = File(importedRoot, "payload-$index").apply { writeText("admitted payload $profile") }
            val digest = RimeEngineElf.sha256(payload)
            val size = payload.length()
            assertTrue(payload.renameTo(File(importedRoot, "$digest.so")))
            ImportedRimeEngine(digest, profile, "$digest.so",
                RimeEngineStaticInfo(digest, size, "arm64-v8a", 64, "librime.so", null,
                    "rime_get_api", listOf("libc.so"), 16384, "librime.so"),
                RimeEngineRuntimeInfo("1.0", 1024, emptyList()), profile, index.toLong())
        }
        File(repositoryRoot, "state.json").writeText(Json.encodeToString(RimeEngineState(imported = records)))
        first = RimeEngineSelection(RimeEngineKind.IMPORTED, records[0].id)
        second = RimeEngineSelection(RimeEngineKind.IMPORTED, records[1].id)
        owner = EngineOwner()
        native = EngineNative()
        NativeTestShadow.delegate = native
        engine = operations()
        ShadowProcess.clearKilledProcesses()
        calls.clear()
    }

    @After fun clean() {
        ObservedParcelFileDescriptor.lastRead = null
        ReflectionHelpers.getField<Handler>(engine, "processHandler").removeCallbacksAndMessages(null)
        ShadowProcess.clearKilledProcesses()
        configuredDescriptors.forEach { assertFalse("Configured payload descriptor was not closed", it.valid()) }
    }

    private fun operations(
        directBoot: Boolean = false,
        provider: () -> RimeEngineRepository = { repository },
    ) = DirectRimeEngineOperations(context, directBoot, config, provider,
        { File(config.root, "shared") }, "test-version", owner)

    private fun watchdog(): Runnable? = ReflectionHelpers.getField(engine, "externalOperationWatchdog")
    private fun pending(): RimeEngineSelection? = ReflectionHelpers.getField(engine, "maintenanceActivationSelection")
    private fun blockStoreWrite() { assertTrue(File(repositoryRoot, "state.json.tmp").mkdir()) }
    private fun activeFirst() {
        repository.select(first, config.current)
        assertTrue(engine.configureSelectedEngine())
        owner.started = true
        engine.startupSucceeded()
        calls.clear()
    }
    private fun selectSecond() { activeFirst(); repository.select(second) }
    private fun advance(milliseconds: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds)) }

    @Test fun directBootConstructionConfigurationAndRestartNeverReadTheRepository() {
        engine = operations(directBoot = true, provider = { fail("CE repository read"); repository })
        ReflectionHelpers.setField(engine, "newActivation", true)
        assertFalse(engine.configureSelectedEngine())
        assertTrue("Direct Boot configure does not reset this field", engine.newActivation)
        assertEquals(RimeEngineSelection.Builtin, engine.activeSelection)
        assertTrue(calls.isEmpty())
        engine.resetStartupState()
        owner.started = true
        calls.clear()
        engine.restart()
        assertEquals(listOf("stop:true:true", "running:false", "start:rime:true", "running:true"), calls)
        assertNull(watchdog())
    }

    @Test fun anUnchangedStableSelectionDoesNotStopConfigureOrStart() {
        activeFirst()
        engine.switchVariantOnOwner()
        assertTrue(calls.isEmpty())
        assertNull(repository.state().activation)
    }

    @Test fun initialSwitchStopFailureClearsRunningAndSnapshotBeforeStopAndNeverLoadsTheTarget() {
        selectSecond()
        val failure = IOException("current slot stop failed")
        native.onStop = { throw failure }
        assertSame(failure, assertThrows(IOException::class.java) { engine.switchVariantOnOwner() })
        assertEquals(listOf("running:false", "clear-snapshot", "stop:false:false"), calls)
        assertFalse(owner.started)
        assertFalse(owner.hasSnapshot)
        assertEquals(false, repository.state().activation?.attempted)
        assertNotNull(watchdog())
        advance(9999)
        assertFalse(ShadowProcess.wasKilled(Process.myPid()))
        advance(1)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun successfulSwitchRefreshesSlotCachesBeforeCompletingActivationAndClearingTheError() {
        selectSecond()
        owner.onRefresh = { assertTrue(repository.state().activation!!.attempted) }
        engine.switchVariantOnOwner()
        assertEquals(listOf("running:false", "clear-snapshot", "stop:false:false", "external:${second.id}",
            "start:second:true", "running:true", "schemas", "reset-slot-caches", "clear-error"), calls)
        assertEquals(second, repository.state().active)
        assertEquals(first, repository.state().fallback)
        assertNull(repository.state().activation)
        assertNull(watchdog())
        assertEquals("", owner.startupError)
    }

    @Test fun targetCleanupStopFailurePreservesTheOriginalErrorAndNeverLoadsFallback() {
        selectSecond()
        val target = IOException("target setup failed")
        val stop = IOException("target stop failed")
        native.onConfigure = { if (it == second.id) throw target }
        var stops = 0
        native.onStop = { if (++stops == 2) throw stop }
        assertSame(target, assertThrows(IOException::class.java) { engine.switchVariantOnOwner() })
        assertArrayEquals(arrayOf(stop), target.suppressed)
        assertEquals(second, repository.state().active)
        assertTrue(repository.state().activation!!.attempted)
        assertFalse(calls.any { it == "external:${first.id}" || it.startsWith("start:") })
        assertFalse(owner.started)
        assertNull(watchdog())
        advance(500)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun targetCleanupReusingTheTargetErrorKeepsTheOriginalErrorAndSchedulesRestart() {
        selectSecond()
        val target = IOException("same target stop error")
        native.onConfigure = { if (it == second.id) throw target }
        var stops = 0
        native.onStop = { if (++stops == 2) throw target }
        assertSame(target, assertThrows(IOException::class.java) { engine.switchVariantOnOwner() })
        // Kotlin's addSuppressed extension ignores the same Throwable before the original
        // cleanup path cancels its watchdog and schedules the guarded process restart.
        assertTrue(target.suppressed.isEmpty())
        assertEquals(second, repository.state().active)
        assertTrue(repository.state().activation!!.attempted)
        assertFalse(owner.started)
        assertNull(watchdog())
        assertFalse(calls.any { it == "external:${first.id}" })
        advance(500)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun targetFailureStartsThePreviousSlotAndReportsARecoveredErrorWithoutReplayingState() {
        selectSecond()
        val target = IOException("target setup failed")
        native.onConfigure = { if (it == second.id) throw target }
        val recovered = assertThrows(DirectRimeEngineOperations.EngineSwitchRecoveredException::class.java) {
            engine.switchVariantOnOwner()
        }
        assertSame(target, recovered.cause)
        assertEquals(first, repository.state().active)
        assertNull(repository.state().activation)
        assertTrue(repository.state().lastFallbackReason!!.contains(target.message!!))
        assertTrue(owner.started)
        assertFalse(owner.hasSnapshot)
        assertNull(watchdog())
        assertEquals(listOf("start:first:true"), calls.filter { it.startsWith("start:") })
        assertEquals(listOf("schemas", "reset-slot-caches", "clear-error"), calls.takeLast(3))
    }

    @Test fun fallbackFailureRetainsItsAttemptedGuardAndAddsOnlyTheRecoveryError() {
        selectSecond()
        val target = IOException("target setup failed")
        val recovery = IOException("fallback setup failed")
        native.onConfigure = { throw if (it == second.id) target else recovery }
        assertSame(target, assertThrows(IOException::class.java) { engine.switchVariantOnOwner() })
        assertArrayEquals(arrayOf(recovery), target.suppressed)
        assertEquals(first, repository.state().active)
        assertTrue(repository.state().activation!!.attempted)
        assertFalse(owner.started)
        assertNull(watchdog())
        advance(500)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun fallbackThrowingTheOriginalErrorDoesNotAddSelfSuppression() {
        selectSecond()
        val target = IOException("same setup error")
        native.onConfigure = { throw target }
        assertSame(target, assertThrows(IOException::class.java) { engine.switchVariantOnOwner() })
        assertTrue(target.suppressed.isEmpty())
        assertTrue(repository.state().activation!!.attempted)
        advance(500)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun targetStartFailureStopsThatSlotBeforeConfiguringTheFallback() {
        selectSecond()
        val target = IOException("target start failed")
        native.onStart = { if (config.current == "second") throw target }
        assertThrows(DirectRimeEngineOperations.EngineSwitchRecoveredException::class.java) { engine.switchVariantOnOwner() }
        val targetStart = calls.indexOf("start:second:true")
        val fallbackConfigure = calls.indexOf("external:${first.id}")
        assertTrue(calls.subList(targetStart + 1, fallbackConfigure).contains("stop:false:false"))
        assertEquals(first, repository.state().active)
        assertTrue(owner.started)
    }

    @Test fun switchingToStableExternalStateUsesTheFullCheckReturnedByConfiguration() {
        repository.select(first, "rime")
        repository.beginActivation()
        repository.activationSucceeded(first)
        engine.switchVariantOnOwner()
        assertEquals(listOf("external:${first.id}", "start:first:false", "running:true", "schemas",
            "reset-slot-caches", "clear-error"), calls)
        assertNull(repository.state().activation)
    }

    @Test fun externalMaintenanceRestartArmsOneGuardAndRebindsBeforeStopping() {
        activeFirst()
        config.switch("rime")
        native.onStop = {
            assertEquals("first", config.current)
            assertTrue(repository.state().activation!!.attempted)
            assertEquals(first, pending())
        }
        native.onStart = { assertFalse(repository.state().activation!!.forceFullCheck) }
        engine.restart()
        assertEquals(listOf("stop:true:true", "running:false", "start:first:true", "running:true"), calls)
        assertEquals(first, repository.state().active)
        assertEquals(first, pending())
        assertNotNull(watchdog())
        engine.completeMaintenanceActivation()
        assertNull(pending())
        assertNull(repository.state().activation)
        assertNull(watchdog())
    }

    @Test fun maintenanceStopFailureLeavesTheRunningFlagAndSnapshotUntouched() {
        activeFirst()
        val stop = IOException("maintenance stop failed")
        native.onStop = { throw stop }
        assertSame(stop, assertThrows(IOException::class.java) { engine.stopForRestart() })
        assertEquals(listOf("stop:true:true"), calls)
        assertTrue(owner.started)
        assertTrue(owner.hasSnapshot)
        assertTrue(repository.state().activation!!.attempted)
        assertNotNull(watchdog())
    }

    @Test fun anUnstartedMaintenanceStopStillRejectsAChangedSelectionBeforeNativeAccess() {
        repository.select(first, "rime")
        assertThrows(IllegalStateException::class.java) { engine.stopForRestart() }
        assertTrue(calls.isEmpty())
        assertEquals(false, repository.state().activation!!.attempted)
        advance(500)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun aPendingBuiltinTransitionCannotBeRestartedInTheOldProcess() {
        repository.select(first, "rime")
        repository.beginActivation()
        repository.activationSucceeded(first)
        repository.select(RimeEngineSelection.Builtin)
        val failure = assertThrows(IllegalStateException::class.java) { engine.restart() }
        assertEquals("引擎选择已改变，正在用新进程应用设置", failure.message)
        assertTrue(calls.isEmpty())
        assertEquals(RimeEngineSelection.Builtin, repository.state().active)
    }

    @Test fun anUnfinishedExternalRestartPersistsFallbackWithoutInitializingItHere() {
        activeFirst()
        repository.beginActivation()
        val failure = assertThrows(IllegalStateException::class.java) { engine.restart() }
        assertEquals("外部引擎上次重新启动未完成，正在恢复回退引擎", failure.message)
        assertTrue(calls.isEmpty())
        assertEquals(RimeEngineSelection.Builtin, repository.state().active)
        assertNull(pending())
        assertTrue(owner.started)
    }

    @Test fun profileValidationFailureKeepsThePendingActivationForMaintenanceRecovery() {
        activeFirst()
        config.error = "profile unavailable"
        val failure = assertThrows(IllegalArgumentException::class.java) { engine.restart() }
        assertEquals("profile unavailable", failure.message)
        assertEquals(first, pending())
        assertTrue(repository.state().activation!!.attempted)
        assertTrue(calls.isEmpty())
        assertTrue(engine.failMaintenanceActivation(failure))
        assertNull(pending())
        assertFalse(owner.started)
        assertEquals(RimeEngineSelection.Builtin, repository.state().active)
    }

    @Test fun maintenanceFailureClearsPendingBeforeAStoreWriteFailureWithoutClearingRuntimeState() {
        activeFirst()
        native.onStop = { throw IOException("stop failed") }
        val stop = assertThrows(IOException::class.java) { engine.stopForRestart() }
        val timeout = watchdog()
        blockStoreWrite()
        assertThrows(IllegalArgumentException::class.java) { engine.failMaintenanceActivation(stop) }
        assertNull(pending())
        assertTrue(owner.started)
        assertSame(timeout, watchdog())
        assertEquals(first, repository.state().active)
        advance(500)
        assertFalse(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun maintenanceSuccessClearsPendingAndWatchdogOnlyAfterTheStoreWriteSucceeds() {
        activeFirst()
        native.onStop = { throw IOException("stop failed") }
        assertThrows(IOException::class.java) { engine.stopForRestart() }
        val timeout = watchdog()
        blockStoreWrite()
        assertThrows(IllegalArgumentException::class.java) { engine.completeMaintenanceActivation() }
        assertEquals(first, pending())
        assertSame(timeout, watchdog())
        assertTrue(repository.state().activation!!.attempted)
    }

    @Test fun absentMaintenanceActivationDoesNotChangeRunningStateOrWatchdog() {
        repository.select(first, "rime")
        engine.configureSelectedEngine()
        owner.started = true
        val timeout = watchdog()
        engine.completeMaintenanceActivation()
        assertFalse(engine.failMaintenanceActivation(IOException("unrelated maintenance error")))
        assertTrue(owner.started)
        assertSame(timeout, watchdog())
        assertTrue(repository.state().activation!!.attempted)
    }

    @Test fun resettingAReusedStartupDoesNotAttributeAnAssetFailureToThePriorExternalEngine() {
        activeFirst()
        engine.resetStartupState()
        engine.startupFailed(IOException("assets failed"))
        assertEquals(RimeEngineSelection.Builtin, engine.activeSelection)
        assertFalse(engine.newActivation)
        assertEquals(first, repository.state().active)
        assertNull(repository.state().activation)
        assertTrue(calls.isEmpty())
        advance(500)
        assertFalse(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun startupResetDoesNotClearAnExistingMaintenanceGuardOrWatchdog() {
        activeFirst()
        native.onStop = { throw IOException("stop failed") }
        assertThrows(IOException::class.java) { engine.stopForRestart() }
        val timeout = watchdog()
        engine.resetStartupState()
        assertEquals(first, pending())
        assertSame(timeout, watchdog())
    }

    @Test fun externalResolveFailureIsAttributedBeforePayloadOrProfileProcessing() {
        repository.select(first, "rime")
        assertTrue(File(repositoryRoot, "imported/${first.id}.so").delete())
        val failure = assertThrows(IllegalArgumentException::class.java) { engine.configureSelectedEngine() }
        assertEquals(first, engine.activeSelection)
        assertTrue(engine.newActivation)
        assertTrue(repository.state().activation!!.attempted)
        assertNotNull(watchdog())
        assertTrue(calls.isEmpty())
        engine.startupFailed(failure)
        assertEquals(RimeEngineSelection.Builtin, repository.state().active)
        assertTrue(repository.state().lastFallbackReason!!.startsWith("外部引擎启动失败"))
        assertNull(watchdog())
    }

    @Test fun startupActivationWriteFailureStopsBeforeSchedulingAProcessRestart() {
        repository.select(first, "rime")
        engine.configureSelectedEngine()
        owner.started = true
        blockStoreWrite()
        assertThrows(IllegalArgumentException::class.java) { engine.startupFailed(IOException("startup failed")) }
        assertNull(watchdog())
        assertTrue(owner.started)
        assertEquals("previous startup error", owner.startupError)
        assertEquals(first, repository.state().active)
        advance(500)
        assertFalse(ShadowProcess.wasKilled(Process.myPid()))
    }
}
