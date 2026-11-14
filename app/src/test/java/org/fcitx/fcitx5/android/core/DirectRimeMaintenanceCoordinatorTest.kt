/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real owner dispatch, mutex suspension and input gate; binding values stay opaque to the coordinator. */
class DirectRimeMaintenanceCoordinatorTest {
    private data class Binding(val program: String, val flags: Int, val needsRime: Boolean)

    // Keep controlled identity/suppression tests independent of the runtime's optional debug
    // stack-trace copies. A separate case below exercises ordinary copyable IO exceptions.
    @OptIn(ExperimentalCoroutinesApi::class)
    private class Failure(message: String) : IOException(message), CopyableThrowable<Failure> {
        override fun createCopy(): Failure? = null
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private class Cancel(message: String) : CancellationException(message), CopyableThrowable<Cancel> {
        override fun createCopy(): Cancel? = null
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private class Fatal(message: String) : LinkageError(message), CopyableThrowable<Fatal> {
        override fun createCopy(): Fatal? = null
    }

    private class Fixture : DirectRimeMaintenanceCoordinator.Owner<Binding>, AutoCloseable {
        private val ownerThread = AtomicReference<Thread>()
        private val dispatcher = Executors.newSingleThreadExecutor {
            Thread(it, "maintenance-fixture-owner").also(ownerThread::set)
        }.asCoroutineDispatcher()
        private val lock = Any()
        private val epoch = AtomicLong(1)
        val original = Binding("previous-editor", 0x41, true)
        val gate = InputKeyGate()
        val calls = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        val prepared = CopyOnWriteArrayList<Pair<Long, Binding>>()
        val begun = CopyOnWriteArrayList<Pair<Long, Binding>>()
        val beginRequests = CopyOnWriteArrayList<Long>()
        val cleared = CopyOnWriteArrayList<Long>()
        val failures = CopyOnWriteArrayList<Exception>()
        val ownerContextsActive = CopyOnWriteArrayList<Boolean>()
        val enters = AtomicInteger()
        val leaves = AtomicInteger()
        val reads = AtomicInteger()
        val refreshes = AtomicInteger()
        val completions = AtomicInteger()
        @Volatile override var started = true
        @Volatile var maintenance = false
        @Volatile var usableGeneration = 1L
        private var binding: Pair<Long, Binding>? = 1L to original
        private var ownerJob: Job? = null
        var onEnter: () -> Unit = {}
        var onPrepare: () -> Unit = {}
        var onRefresh: () -> Unit = {}
        var onComplete: () -> Unit = {}
        var onFailure: (Exception) -> Boolean = { false }
        val coordinator = DirectRimeMaintenanceCoordinator(this)

        init { gate.running(); gate.prepare(1, true); gate.resolve(1, true) }
        override val inputSession get() = epoch.get()
        private fun checkOwner() = assertSame(ownerThread.get(), Thread.currentThread())

        override fun enterMaintenance(): Pair<Long, Binding?> {
            enters.incrementAndGet()
            maintenance = true
            gate.block()
            onEnter()
            return synchronized(lock) {
                val previous = binding?.second
                val next = epoch.incrementAndGet()
                binding = null
                gate.invalidate(next)
                calls += "enter:$next"
                next to previous
            }
        }
        override fun leaveMaintenance() {
            leaves.incrementAndGet()
            maintenance = false
            gate.running(started)
            calls += "leave:$inputSession"
        }
        override suspend fun onOwner(operation: () -> Unit) = withContext(dispatcher) {
            checkOwner()
            ownerJob = currentCoroutineContext()[Job]
            ownerContextsActive += currentCoroutineContext().isActive
            operation()
        }
        override fun clearBinding(generation: Long) {
            checkOwner()
            calls += "clear:$generation"
            cleared += generation
            usableGeneration = 0
            gate.resolve(generation, false)
        }
        override fun publishMaintenanceStatus(generation: Long) {
            checkOwner()
            assertFalse(gate.canProcess())
            calls += "status:$generation"
        }
        override fun showUnavailable(message: String) {
            checkOwner()
            calls += "hint:$message"
            messages += message
        }
        override fun refreshSchemas() {
            checkOwner()
            refreshes.incrementAndGet()
            calls += "refresh"
            onRefresh()
        }
        override fun completeActivation() {
            checkOwner()
            completions.incrementAndGet()
            calls += "complete"
            onComplete()
        }
        override fun failActivation(error: Exception): Boolean {
            checkOwner()
            assertTrue(checkNotNull(ownerJob).isActive)
            failures += error
            calls += "failed"
            return onFailure(error)
        }
        override fun hasUsableBinding(generation: Long): Boolean {
            checkOwner()
            return usableGeneration == generation
        }
        override fun currentBinding(generation: Long): Binding? {
            checkOwner()
            reads.incrementAndGet()
            return bindingValue(generation)
        }
        fun bindingValue(generation: Long = inputSession): Binding? = synchronized(lock) {
            binding?.takeIf { it.first == generation }?.second
        }
        fun attach(value: Binding, generation: Long = inputSession) = synchronized(lock) {
            binding = generation to value
            gate.prepare(generation, value.needsRime)
        }
        fun changeEditor(value: Binding): Long = synchronized(lock) {
            val next = epoch.incrementAndGet()
            gate.invalidate(next)
            attach(value, next)
            next
        }
        fun detachBinding() = synchronized(lock) {
            binding = null
            usableGeneration = 0
            gate.invalidate(inputSession)
        }
        override fun prepareBinding(generation: Long, previous: Binding) {
            checkOwner()
            prepared += generation to previous
            if (generation == inputSession) attach(previous, generation)
            onPrepare()
        }
        override fun beginBinding(generation: Long) {
            checkOwner()
            beginRequests += generation
            if (!started || generation != inputSession) return
            val target = checkNotNull(bindingValue(generation))
            if (!target.needsRime) { clearBinding(generation); return }
            begun += generation to target
            usableGeneration = generation
            gate.resolve(generation, true)
            calls += "begin:$generation:${target.program}"
        }
        suspend fun holdOwner(entered: CountDownLatch, release: CountDownLatch) = withContext(dispatcher) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        override fun close() = dispatcher.close()
    }

    private fun exercise(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        supervisorScope {
            val fixture = Fixture()
            try { block(fixture) } finally { fixture.close() }
        }
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected operation to fail")
    }

    private fun assertSourceRetained(source: Throwable, actual: Throwable) {
        assertEquals(source.javaClass, actual.javaClass)
        assertEquals(source.message, actual.message)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = actual
        while (current != null && seen.add(current)) {
            if (current === source) return
            current = current.cause
        }
        fail("Original Throwable was not retained in the cause chain")
    }

    @Test fun ownerBarrierRejectsOldTicketsBeforeDispatchAndRestoresTheOpaqueBinding() = exercise { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(start = CoroutineStart.UNDISPATCHED) { fixture.holdOwner(entered, release) }
        var maintenance: Deferred<Unit>? = null
        val ticket = checkNotNull(fixture.gate.capture())
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            maintenance = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run { fixture.calls += "operation" } }
            assertEquals(2L, fixture.inputSession)
            assertTrue(fixture.maintenance)
            assertFalse(fixture.gate.canProcess())
            assertNull(fixture.gate.capture())
            assertFalse(fixture.gate.canDeliver(ticket))
            release.countDown()
            withTimeout(5_000) { checkNotNull(maintenance).await() }
            assertFalse(fixture.maintenance)
            assertTrue(fixture.gate.canProcess())
            assertEquals(2L, fixture.prepared.single().first)
            assertSame(fixture.original, fixture.prepared.single().second)
            assertSame(fixture.original, fixture.begun.single().second)
            assertTrue(fixture.calls.indexOf("status:2") < fixture.calls.indexOf("operation"))
            assertTrue(fixture.calls.indexOf("operation") < fixture.calls.indexOf("complete"))
            assertFalse(fixture.gate.canDeliver(ticket))
        } finally {
            release.countDown()
            maintenance?.cancelAndJoin()
            blocker.join()
            fixture.gate.release(ticket)
        }
    }

    @Test fun queuedMaintenanceDoesNotAdvanceTheEpochUntilTheFirstFlowLeaves() = exercise { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = async(Dispatchers.Default) {
            fixture.coordinator.run { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        var second: Deferred<Unit>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            second = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run {} }
            assertEquals(1, fixture.enters.get())
            assertEquals(2L, fixture.inputSession)
            assertFalse(checkNotNull(second).isCompleted)
            release.countDown()
            withTimeout(5_000) { first.await(); checkNotNull(second).await() }
            assertEquals(2, fixture.enters.get())
            assertEquals(3L, fixture.inputSession)
            assertTrue(fixture.calls.indexOf("leave:2") < fixture.calls.indexOf("enter:3"))
            assertTrue(fixture.gate.canProcess())
        } finally { release.countDown(); first.cancelAndJoin(); second?.cancelAndJoin() }
    }

    @Test fun cancellingTheMutexWaiterDoesNotTouchTheActiveFlowOrEditor() = exercise { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = async(Dispatchers.Default) {
            fixture.coordinator.run { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        var waiting: Deferred<Unit>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waiting = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run { fail("Cancelled waiter ran") } }
            withTimeout(5_000) { checkNotNull(waiting).cancelAndJoin() }
            assertEquals(1, fixture.enters.get())
            assertEquals(0, fixture.leaves.get())
            assertTrue(fixture.maintenance)
            assertEquals(2L, fixture.inputSession)
            release.countDown()
            withTimeout(5_000) { first.await() }
            assertEquals(1, fixture.leaves.get())
            assertTrue(fixture.gate.canProcess())
        } finally { release.countDown(); first.cancelAndJoin(); waiting?.cancelAndJoin() }
    }

    @Test fun cancellationBeforeOwnerDispatchRestoresInAnActiveRecoveryContext() = exercise { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(start = CoroutineStart.UNDISPATCHED) { fixture.holdOwner(entered, release) }
        var maintenance: Deferred<Unit>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            maintenance = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run { fail("Cancelled owner work ran") } }
            checkNotNull(maintenance).cancel()
            release.countDown()
            withTimeout(5_000) { checkNotNull(maintenance).join() }
            assertTrue(checkNotNull(maintenance).isCancelled)
            assertTrue(fixture.failures.single() is CancellationException)
            assertEquals(listOf(true), fixture.ownerContextsActive.toList())
            assertEquals(1, fixture.begun.size)
            assertTrue(fixture.messages.isEmpty())
            assertFalse(fixture.maintenance)
            assertTrue(fixture.gate.canProcess())
        } finally { release.countDown(); maintenance?.cancelAndJoin(); blocker.join() }
    }

    @Test fun cancellationAfterOwnerWorkDoesNotRepeatAnAlreadyUsableBinding() = exercise { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val operations = AtomicInteger()
        val maintenance = async(Dispatchers.Default) {
            fixture.coordinator.run {
                operations.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            maintenance.cancel()
            release.countDown()
            withTimeout(5_000) { maintenance.join() }
            assertTrue(maintenance.isCancelled)
            assertEquals(1, operations.get())
            assertEquals(1, fixture.begun.size)
            assertEquals(1, fixture.completions.get())
            assertTrue(fixture.failures.single() is CancellationException)
            assertEquals(listOf("正在部署，请稍候…"), fixture.messages.toList())
            assertFalse(fixture.maintenance)
            assertTrue(fixture.gate.canProcess())
        } finally { release.countDown(); maintenance.cancelAndJoin() }
    }

    @Test fun maintenanceMutexStaysHeldThroughNonCancellableRecovery() = exercise { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val original = Failure("maintenance failed")
        fixture.onFailure = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); false }
        val first = async(Dispatchers.Default) { fixture.coordinator.run { throw original } }
        var waiting: Deferred<Unit>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waiting = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run {} }
            assertEquals(1, fixture.enters.get())
            assertEquals(2L, fixture.inputSession)
            assertTrue(fixture.maintenance)
            release.countDown()
            assertSame(original, failure { withTimeout(5_000) { first.await() } })
            withTimeout(5_000) { checkNotNull(waiting).await() }
            assertTrue(fixture.calls.indexOf("leave:2") < fixture.calls.indexOf("enter:3"))
            assertFalse(fixture.maintenance)
        } finally { release.countDown(); first.cancelAndJoin(); waiting?.cancelAndJoin() }
    }

    @Test fun currentInactiveMetadataWinsOverThePreviousActiveEditor() = exercise { fixture ->
        val current = Binding("type-null-editor", 0x08, false)
        fixture.coordinator.run { fixture.attach(current) }
        assertTrue(fixture.prepared.isEmpty())
        assertSame(current, fixture.bindingValue())
        assertEquals(listOf(2L), fixture.beginRequests.toList())
        assertTrue(fixture.begun.isEmpty())
        assertFalse(fixture.gate.canProcess())
    }

    @Test fun previousInactiveMetadataIsRestoredWithoutPromotingInputDemand() = exercise { fixture ->
        val previous = Binding("previous-type-null", 0x12, false)
        fixture.attach(previous)
        fixture.coordinator.run {}
        assertSame(previous, fixture.prepared.single().second)
        assertSame(previous, fixture.bindingValue())
        assertTrue(fixture.begun.isEmpty())
        assertFalse(fixture.gate.canProcess())
    }

    @Test fun metadataArrivingAfterPrepareIsUsedByTheSecondBindingRead() = exercise { fixture ->
        val current = Binding("newer-same-generation", 0x92, true)
        fixture.onPrepare = { fixture.attach(current) }
        fixture.coordinator.run {}
        assertSame(fixture.original, fixture.prepared.single().second)
        assertSame(current, fixture.begun.single().second)
        assertEquals(2, fixture.reads.get())
        assertTrue(fixture.gate.canProcess())
    }

    @Test fun newerEditorGenerationIsPreservedAfterSuccessfulMaintenance() = exercise { fixture ->
        val current = Binding("next-editor", 0x10, true)
        fixture.coordinator.run { fixture.changeEditor(current) }
        assertEquals(3L, fixture.inputSession)
        assertSame(current, fixture.bindingValue())
        assertTrue(fixture.prepared.isEmpty())
        assertTrue(fixture.begun.isEmpty())
        assertEquals(listOf(2L), fixture.cleared.toList())
        assertFalse(fixture.gate.canProcess())
        val next = checkNotNull(fixture.gate.capture())
        try { assertEquals(3L, next.generation) } finally { fixture.gate.release(next) }
    }

    @Test fun failedMaintenanceStillSettlesActivationButDoesNotTouchTheNewEditor() = exercise { fixture ->
        val current = Binding("next-editor", 0x20, true)
        val original = Failure("old maintenance error")
        assertSame(original, failure { fixture.coordinator.run { fixture.changeEditor(current); throw original } })
        assertSame(original, fixture.failures.single())
        assertSame(current, fixture.bindingValue())
        assertTrue(fixture.prepared.isEmpty())
        assertTrue(fixture.begun.isEmpty())
        assertEquals(listOf("正在部署，请稍候…"), fixture.messages.toList())
        assertEquals(listOf(2L), fixture.cleared.toList())
    }

    @Test fun successfulOwnerBindingIsNotReplacedOrStartedAgain() = exercise { fixture ->
        val current = Binding("already-bound", 0x24, true)
        fixture.coordinator.run { fixture.attach(current); fixture.beginBinding(fixture.inputSession) }
        assertTrue(fixture.prepared.isEmpty())
        assertEquals(1, fixture.begun.size)
        assertSame(current, fixture.begun.single().second)
        assertEquals(0, fixture.reads.get())
        assertTrue(fixture.gate.canProcess())
    }

    @Test fun aStoppedEngineDoesNotRestoreAndLeavesTheInputGateFailed() = exercise { fixture ->
        fixture.coordinator.run { fixture.started = false }
        assertTrue(fixture.prepared.isEmpty())
        assertTrue(fixture.begun.isEmpty())
        assertEquals(0, fixture.reads.get())
        assertFalse(fixture.maintenance)
        assertFalse(fixture.gate.canProcess())
        assertNull(fixture.gate.capture())
    }

    @Test fun anExternalActivationFailureSuppressesBindingRecovery() = exercise { fixture ->
        val original = Failure("external activation failed")
        fixture.onFailure = { true }
        assertSame(original, failure { fixture.coordinator.run { throw original } })
        assertTrue(fixture.prepared.isEmpty())
        assertTrue(fixture.begun.isEmpty())
        assertEquals(0, fixture.reads.get())
        assertEquals(listOf("正在部署，请稍候…", original.message), fixture.messages.toList())
        assertFalse(fixture.gate.canProcess())
    }

    @Test fun activationCompletionFailureRunsRecoveryBeforeReportingTheOriginalError() = exercise { fixture ->
        val original = Failure("activation write failed")
        fixture.onComplete = { throw original }
        assertSame(original, failure { fixture.coordinator.run {} })
        assertSame(original, fixture.failures.single())
        assertEquals(1, fixture.begun.size)
        val begin = fixture.calls.indexOf("begin:2:previous-editor")
        val hint = fixture.calls.indexOf("hint:activation write failed")
        assertTrue(begin >= 0 && begin < hint)
        assertTrue(fixture.gate.canProcess())
    }

    @Test fun aRecoveryErrorIsSuppressedOnTheOriginalOperationError() = exercise { fixture ->
        val original = Failure("operation failed")
        val recovery = Failure("activation recovery failed")
        fixture.onFailure = { throw recovery }
        assertSame(original, failure { fixture.coordinator.run { throw original } })
        assertArrayEquals(arrayOf(recovery), original.suppressed)
        assertEquals(listOf("正在部署，请稍候…"), fixture.messages.toList())
        assertFalse(fixture.maintenance)
        assertEquals(1, fixture.leaves.get())
    }

    @Test fun reusingTheOperationErrorDuringRecoveryDoesNotSelfSuppress() = exercise { fixture ->
        val original = Failure("same operation and recovery error")
        fixture.onFailure = { throw it }
        assertSame(original, failure { fixture.coordinator.run { throw original } })
        assertTrue(original.suppressed.isEmpty())
        assertFalse(fixture.maintenance)
        assertEquals(1, fixture.leaves.get())
    }

    @Test fun cancellationAndRecoveredSwitchErrorsRestoreWithoutOrdinaryFailureFeedback() = exercise { fixture ->
        val cancellation = Cancel("cancelled")
        assertSame(cancellation, failure { fixture.coordinator.run { throw cancellation } })
        val recovered = DirectRimeEngineOperations.EngineSwitchRecoveredException("fallback is running", IOException("target"))
        val actual = failure { fixture.coordinator.run { throw recovered } }
        assertSame(fixture.failures.last(), actual)
        assertTrue(actual is DirectRimeEngineOperations.EngineSwitchRecoveredException)
        assertSourceRetained(recovered, actual)
        assertEquals(2, fixture.begun.size)
        assertEquals(listOf("正在部署，请稍候…", "正在部署，请稍候…"), fixture.messages.toList())
        assertTrue(fixture.gate.canProcess())
    }

    @Test fun anEntryBarrierFailureDoesNotEnterRecoveryButReleasesTheMaintenanceMutex() = exercise { fixture ->
        val original = Failure("entry barrier failed")
        fixture.onEnter = { throw original }
        assertSame(original, failure { fixture.coordinator.run { fail("Barrier failed") } })
        assertTrue(fixture.failures.isEmpty())
        assertEquals(0, fixture.leaves.get())
        assertEquals(1L, fixture.inputSession)
        assertTrue(fixture.maintenance)
        fixture.onEnter = {}
        withTimeout(5_000) { fixture.coordinator.run {} }
        assertEquals(2, fixture.enters.get())
        assertEquals(1, fixture.leaves.get())
        assertTrue(fixture.gate.canProcess())
    }

    @Test fun aFatalOwnerErrorRunsFinallyWithoutBeingTreatedAsARecoverableException() = exercise { fixture ->
        val original = Fatal("native linkage failed")
        assertSame(original, failure { fixture.coordinator.run { throw original } })
        assertTrue(fixture.failures.isEmpty())
        assertTrue(fixture.begun.isEmpty())
        assertFalse(fixture.maintenance)
        assertEquals(1, fixture.leaves.get())
        assertFalse(fixture.gate.canProcess())
    }

    @Test fun skippingSchemaRefreshStillCompletesActivationAndRestoresInput() = exercise { fixture ->
        fixture.onRefresh = { fail("Switch already refreshed schemas") }
        fixture.coordinator.run(refreshAfterOperation = false) {}
        assertEquals(0, fixture.refreshes.get())
        assertEquals(1, fixture.completions.get())
        assertEquals(1, fixture.begun.size)
        assertTrue(fixture.gate.canProcess())
    }

    @Test fun maintenanceWithoutAnyEditorKeepsTheBindingEmptyAndInputClosed() = exercise { fixture ->
        fixture.detachBinding()
        fixture.coordinator.run {}
        assertNull(fixture.bindingValue())
        assertTrue(fixture.prepared.isEmpty())
        assertTrue(fixture.beginRequests.isEmpty())
        assertFalse(fixture.gate.canProcess())
        assertNull(fixture.gate.capture())
    }

    @Test fun ordinaryIOExceptionTransportRetainsTheActualPrimaryAndSecondarySource() = exercise { fixture ->
        val source = IOException("ordinary operation failed")
        val recovery = IOException("ordinary recovery failed")
        fixture.onFailure = { throw recovery }
        val actual = failure { fixture.coordinator.run { throw source } }
        val primary = fixture.failures.single()
        assertSame(primary, actual)
        assertSourceRetained(source, primary)
        assertSourceRetained(recovery, primary.suppressed.single())
        assertTrue(fixture.prepared.isEmpty())
        assertTrue(fixture.begun.isEmpty())
        assertFalse(fixture.maintenance)
        assertFalse(fixture.gate.canProcess())
    }
}
