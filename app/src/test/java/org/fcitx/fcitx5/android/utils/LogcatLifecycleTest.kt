/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LogcatLifecycleTest {
    private class ReaderProcess : java.lang.Process() {
        val destroyed = AtomicBoolean()
        val reading = CountDownLatch(1)
        val exited = CountDownLatch(1)
        private val input = object : InputStream() {
            override fun read(): Int { reading.countDown(); exited.await(); return -1 }
            override fun close() { check(destroyed.get()) { "Closing a live pipe can block" } }
        }
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun exitValue(): Int { check(destroyed.get()); return 0 }
        override fun destroy() { destroyed.set(true); exited.countDown() }
    }

    @Test fun processCreatedAfterShutdownIsStillDestroyed(): Unit = runBlocking {
        val entering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = ReaderProcess()
        val reader = Logcat(pid = null, createProcess = {
            entering.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            child
        })
        val job = reader.initLogFlow()
        assertTrue(entering.await(10, TimeUnit.SECONDS))
        reader.shutdownLogFlow() // Must not wait for a still-starting Runtime.exec.
        release.countDown()
        withTimeout(10_000) { job.join() }
        assertTrue(child.destroyed.get())
    }

    @Test fun anOldReaderCannotClearOrKillItsReplacement(): Unit = runBlocking {
        val entering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = ReaderProcess()
        val second = ReaderProcess()
        val calls = AtomicInteger()
        val reader = Logcat(pid = null, createProcess = {
            if (calls.incrementAndGet() == 1) {
                entering.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                first
            } else second
        })
        val old = reader.initLogFlow()
        assertTrue(entering.await(10, TimeUnit.SECONDS))
        reader.shutdownLogFlow()
        val current = reader.initLogFlow()
        assertTrue(second.reading.await(10, TimeUnit.SECONDS))
        release.countDown()
        withTimeout(10_000) { old.join() }
        assertTrue(first.destroyed.get())
        assertFalse(second.destroyed.get())
        reader.shutdownLogFlow()
        withTimeout(10_000) { current.join() }
        assertTrue(second.destroyed.get())
    }

    @Test fun stoppingAReaderUnblocksItsReadAndClosesOnlyAfterTermination(): Unit = runBlocking {
        val child = ReaderProcess()
        val reader = Logcat(pid = null, createProcess = { child })
        val job = reader.initLogFlow()
        assertTrue(child.reading.await(10, TimeUnit.SECONDS))
        reader.shutdownLogFlow()
        withTimeout(10_000) { job.join() }
        assertTrue(child.destroyed.get())
    }

    @Test fun theReaderStartsAtTheGivenBoundaryInsteadOfReplayingTheBacklog(): Unit = runBlocking {
        val child = ReaderProcess()
        val argv = AtomicReference<Array<String>>()
        val reader = Logcat(pid = 4242, createProcess = { argv.set(it); child })
        // 1_700_000_000_123 ms：logcat 的 -T 用不带年份的 epoch 形式，毫秒补零到三位。
        val job = reader.initLogFlow(1_700_000_000_123L)
        assertTrue(child.reading.await(10, TimeUnit.SECONDS))
        val args = argv.get().toList()
        assertTrue(args.contains("-T"))
        assertTrue(args.contains("1700000000.123"))
        assertTrue(args.contains("--pid=4242"))
        assertTrue(args.contains("threadtime"))
        reader.shutdownLogFlow()
        withTimeout(10_000) { job.join() }
    }

    @Test fun aReaderWithoutABoundaryKeepsReplayingWhatLogdBuffered(): Unit = runBlocking {
        val child = ReaderProcess()
        val argv = AtomicReference<Array<String>>()
        val reader = Logcat(pid = null, createProcess = { argv.set(it); child })
        val job = reader.initLogFlow()
        assertTrue(child.reading.await(10, TimeUnit.SECONDS))
        assertFalse(argv.get().toList().contains("-T"))
        reader.shutdownLogFlow()
        withTimeout(10_000) { job.join() }
    }

    @Test fun aSnapshotAsksLogcatForTheSameBoundaryAsTheReader(): Unit = runBlocking {
        val argv = AtomicReference<Array<String>>()
        val reader = Logcat(pid = null, createProcess = {
            argv.set(it)
            val line = "11-24 10:00:00.000  1234  1235 I RimeLifecycle: snapshot"
            object : java.lang.Process() {
                override fun getInputStream() = ByteArrayInputStream((line + "\n").toByteArray())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() = Unit
            }
        })
        val lines = reader.getLogAsync(1_700_000_000_500L).await().getOrThrow()
        assertEquals(listOf("11-24 10:00:00.000  1234  1235 I RimeLifecycle: snapshot"), lines)
        val args = argv.get().toList()
        assertTrue(args.contains("1700000000.500"))
        assertTrue(args.contains("-d"))
    }
}
