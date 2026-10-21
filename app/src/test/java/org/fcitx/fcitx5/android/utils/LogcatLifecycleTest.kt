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
}
