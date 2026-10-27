/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

internal val upgradeBytes = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n".toByteArray()
internal fun wsHeader(opcode: Int, length: Long, final: Boolean = true): ByteArray {
    val out = ByteArrayOutputStream(); out.write(opcode or if (final) 128 else 0)
    DataOutputStream(out).apply {
        when { length < 126 -> writeByte(length.toInt()); length <= 65535 -> { writeByte(126); writeShort(length.toInt()) }
            else -> { writeByte(127); writeLong(length) } }
    }
    return out.toByteArray()
}

class WebSocketFrameGuardTest {
    private fun read(frames: ByteArray, limit: Long = 100) =
        WebSocketFrameGuard((upgradeBytes + frames).inputStream(), limit).readBytes()
    private fun rejected(frames: ByteArray, limit: Long = 100) {
        try { read(frames, limit); fail("Must reject") } catch (_: IOException) { }
    }
    @Test fun smallAndFragmentedMessagesPassWithInterspersedControlFrames() {
        val frames = wsHeader(1, 3, false) + "abc".toByteArray() + wsHeader(9, 1) + byteArrayOf(7) +
            wsHeader(0, 3) + "def".toByteArray() + wsHeader(1, 4) + "next".toByteArray()
        assertArrayEquals(upgradeBytes + frames, read(frames, 6))
    }
    @Test fun oversizedSingleFrameIsRejectedFromItsHeaderAlone() { rejected(wsHeader(1, 1L shl 30)) }
    @Test fun fragmentedSizeAccumulatesAcrossPingAndPong() {
        rejected(wsHeader(1, 70, false) + ByteArray(70) + wsHeader(9, 0) + wsHeader(10, 0) + wsHeader(0, 40))
    }
    @Test fun finalEmptyContinuationResetsTheBudget() {
        val frames = wsHeader(1, 100, false) + ByteArray(100) + wsHeader(0, 0) + wsHeader(1, 100) + ByteArray(100)
        assertArrayEquals(upgradeBytes + frames, read(frames))
    }
    @Test fun compressionMaskingUnsupportedAndInvalidControlFramesAreRejected() {
        rejected(byteArrayOf(0xc1.toByte(), 0)); rejected(byteArrayOf(0x81.toByte(), 0x80.toByte()))
        rejected(wsHeader(3, 0)); rejected(wsHeader(9, 1, false)); rejected(wsHeader(10, 126))
    }
    @Test fun invalidContinuationAndOverlappingMessagesAreRejected() {
        rejected(wsHeader(0, 0)); rejected(wsHeader(1, 0, false) + wsHeader(2, 0))
    }
    @Test fun signedAndNoncanonicalLengthsAreRejectedWithoutAllocatingPayload() {
        rejected(wsHeader(1, Long.MAX_VALUE)); rejected(byteArrayOf(0x81.toByte(), 127, 0x80.toByte()) + ByteArray(7))
        rejected(byteArrayOf(0x81.toByte(), 126, 0, 1))
        rejected(byteArrayOf(0x81.toByte(), 127) + ByteBuffer.allocate(8).putLong(100).array())
    }
    @Test fun everyPossibleHeaderSplitAndSmallReadsPreserveTheSameBytes() {
        val bytes = upgradeBytes + wsHeader(2, 130) + ByteArray(130) { it.toByte() }
        for (chunk in 1..17) {
            val source = object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, chunk))
            }
            assertArrayEquals(bytes, WebSocketFrameGuard(source, 130).readBytes())
        }
    }
    @Test fun responseHeaderBudgetExtensionsAndNonUpgradeResponsesAreRejected() {
        for (header in listOf("HTTP/1.1 101 OK\r\nX: " + "a".repeat(65536),
            "HTTP/1.1 101 OK\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n",
            "HTTP/1.1 302 Found\r\nLocation: https://elsewhere.invalid\r\n\r\n")) {
            try { WebSocketFrameGuard(header.byteInputStream(), 100).readBytes(); fail("Must reject") }
            catch (_: IOException) { }
        }
    }
    @Test fun skipCannotBypassFrameValidationAndReadAheadIsBounded() {
        val bytes = upgradeBytes + wsHeader(1, 1000) + ByteArray(1000)
        try { WebSocketFrameGuard(bytes.inputStream(), 100).skip(10000); fail("Must reject") }
        catch (_: ClipCascadeMessageTooLargeException) { }
        val guard = WebSocketFrameGuard((upgradeBytes + wsHeader(1, 20000) + ByteArray(20000)).inputStream(), 20000)
        assertTrue(guard.read(ByteArray(30000)) <= 8192)
    }
    @Test fun defaultPayloadBudgetScalesWithHeapAndNeverExceedsTheProtocolCeiling() {
        assertEquals(2L * 1024 * 1024, ClipCascadePayloadBudget.forHeap(64L * 1024 * 1024).maxPayloadBytes)
        assertEquals(8L * 1024 * 1024, ClipCascadePayloadBudget.forHeap(256L * 1024 * 1024).maxPayloadBytes)
        assertEquals(32L * 1024 * 1024, ClipCascadePayloadBudget.forHeap(Long.MAX_VALUE).maxPayloadBytes)
    }
    @Test fun isolatedSmallHeapRejectsGigabyteDeclarationBeforeReadingItsPayload() {
        val classes = listOf(WebSocketFrameGuard::class.java, WebSocketHeapProbe::class.java, kotlin.Unit::class.java,
            okhttp3.OkHttpClient::class.java, okio.Buffer::class.java, okio.ByteString::class.java)
        val classpath = classes.map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx64m", "-XX:-CompactStrings", "-cp", classpath, WebSocketHeapProbe::class.java.name).redirectErrorStream(true).start()
        assertTrue("Heap probe timed out", process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        println("WEBSOCKET_HEAP_PROBE $output")
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.contains("\"rejected\":true"))
        assertTrue(output, output.contains("\"valid_message_bytes\":4194304"))
    }
}

/** Kept out of Robolectric; no payload-sized arrays or synthetic heap enlargement. */
object WebSocketHeapProbe {
    @JvmStatic fun main(args: Array<String>) {
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
        pools.forEach { it.resetPeakUsage() }
        val before = pools.sumOf { it.usage.used }
        var generated = 0L
        val huge = object : InputStream() {
            override fun read(): Int { generated++; return 65 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                generated += len; java.util.Arrays.fill(b, off, off + len, 65.toByte()); return len
            }
        }
        val source = SequenceInputStream((upgradeBytes + wsHeader(1, 1L shl 30)).inputStream(), huge)
        var rejected = false
        try { WebSocketFrameGuard(source, 2L * 1024 * 1024).copyTo(OutputStream.nullOutputStream()) }
        catch (_: ClipCascadeMessageTooLargeException) { rejected = true }
        val peak = pools.sumOf { it.peakUsage.used }
        check(rejected && generated <= 8192 && peak - before < 8 * 1024 * 1024)
        println("{\"max_heap_bytes\":${Runtime.getRuntime().maxMemory()},\"peak_heap_bytes\":$peak,\"peak_delta_bytes\":${peak-before},\"payload_bytes_read\":$generated,\"declared_message_bytes\":1073741824,\"rejected\":$rejected}")
        validMessage()
    }

    private fun validMessage() {
        val limit = 4 * 1024 * 1024 // slightly above the 64 MiB heap's default wire budget
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val stop = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val sampled = java.util.concurrent.atomic.AtomicLong()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampleThread = kotlin.concurrent.thread(isDaemon = true) {
            val bean = ManagementFactory.getMemoryMXBean()
            while (sampling.get()) {
                sampled.accumulateAndGet(bean.heapMemoryUsage.used, ::maxOf)
                Thread.sleep(2)
            }
        }
        val peer = kotlin.concurrent.thread(isDaemon = true) {
            server.accept().use { socket ->
                val request = ByteArrayOutputStream()
                val input = socket.getInputStream()
                while (!request.toString("US-ASCII").endsWith("\r\n\r\n")) request.write(input.read())
                val key = request.toString("US-ASCII").split("\r\n").first { it.startsWith("Sec-WebSocket-Key:", true) }
                    .substringAfter(':').trim()
                val accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val out = socket.getOutputStream()
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                out.write(wsHeader(1, limit.toLong()))
                val chunk = ByteArray(8192) { 65 }
                repeat(limit / chunk.size) { out.write(chunk) }
                out.flush(); stop.await(15, TimeUnit.SECONDS)
            }
        }
        val base = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        val client = BoundedWebSocketTransport.client(base, secure = false, maxMessage = limit.toLong())
        var received = 0
        var failure: Throwable? = null
        val ws = client.newWebSocket(okhttp3.Request.Builder().url("ws://127.0.0.1:${server.localPort}/").build(),
            object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    received = text.length
                    sampled.accumulateAndGet(ManagementFactory.getMemoryMXBean().heapMemoryUsage.used, ::maxOf)
                    done.countDown()
                }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    failure = t; done.countDown()
                }
            })
        check(done.await(15, TimeUnit.SECONDS) && failure == null && received == limit) { "Valid message failed: $failure / $received" }
        ws.cancel(); stop.countDown(); server.close(); peer.join(2000)
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        sampling.set(false); sampleThread.join(1000)
        println("{\"valid_message_bytes\":$received,\"sampled_peak_heap_bytes\":${sampled.get()},\"max_heap_bytes\":${Runtime.getRuntime().maxMemory()},\"compact_strings\":false,\"okhttp_callback_received\":true}")
    }
}
