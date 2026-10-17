/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.os.Debug
import android.os.Process
import android.os.Trace
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Copied ONLY into androidTest of each checkout; requires no product-source changes. */
class EngineComparisonTest {
    private data class Candidates(val at: Long, val words: List<String>)
    private data class Commit(val at: Long, val text: String)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private fun extract(archive: File, directory: File) {
        val root = directory.canonicalPath + File.separator
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = File(directory, entry.name).canonicalFile
                require(destination.path.startsWith(root)) { "Archive path escapes profile" }
                if (entry.isDirectory) destination.mkdirs() else {
                    destination.parentFile!!.mkdirs()
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 512L * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        return JSONObject().apply {
            put("rss_kib", Regex("(?m)^VmRSS:\\s+(\\d+)").find(File("/proc/self/status").readText())?.groupValues?.get(1)?.toLong())
            put("pss_kib", info.totalPss)
            put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
        }
    }

    private fun metrics(samples: List<Long>) = JSONObject().apply {
        val sorted = samples.sorted()
        put("samples", sorted.size)
        for (p in listOf(50, 95, 99)) put("p${p}_ms", sorted[((sorted.size * p + 99) / 100 - 1).coerceAtLeast(0)] / 1e6)
        put("total_ms", samples.sum() / 1e6)
        put("raw_ns", JSONArray(samples))
    }

    @Test fun wanxiangReplay(): Unit = runBlocking {
        val backend = requireNotNull(arguments.getString("backend"))
        require(backend in listOf("fcitx", "direct"))
        val archive = File(context.filesDir, "wanxiang-perf.zip")
        check(archive.isFile)
        val connection = FcitxDaemon.connect(javaClass.name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val candidates = Channel<Candidates>(Channel.UNLIMITED)
        val commits = Channel<Commit>(Channel.UNLIMITED)
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val profile = "perf-${System.currentTimeMillis()}"
        var previous = "rime"
        var created = false
        try {
            connection.runOnReady {
                previous = getAddonSubConfig("rime", "profile-manager")["Current"].value
                setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "create"), RawConfig("Name", profile))))
                val state = getAddonSubConfig("rime", "profile-manager")
                check(state["Error"].value.isEmpty()) { state.toString() }
                created = true
            }
            val directory = File(context.getExternalFilesDir(null), "data/$profile")
            check(directory.isDirectory && directory.list().isNullOrEmpty())
            extract(archive, directory)
            connection.runOnReady {
                setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "No"))))
                setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "switch"), RawConfig("Name", profile))))
                check(getAddonSubConfig("rime", "profile-manager")["Current"].value == profile)
                activate(31001, "perf.replay")
                setCapFlags(CapabilityFlags.DefaultFlags)
                setCandidatePagingMode(1)
                activateIme("rime")
                focus(true)
            }
            // The Fcitx addon deploys asynchronously; don't time maintenance or swallowed keys.
            withTimeout(180_000) {
                while (connection.runOnReady { focus(true); currentIme().subMode.icon } != "fcitx-rime:wanxiang") delay(100)
            }
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.runImmediately { eventFlow }.collect { event ->
                    val at = System.nanoTime()
                    counts.getOrPut(event.javaClass.simpleName) { AtomicInteger() }.incrementAndGet()
                    when (event) {
                        is FcitxEvent.PagedCandidateEvent -> candidates.send(Candidates(at, event.data.candidates.map { it.text }))
                        is FcitxEvent.CommitStringEvent -> commits.send(Commit(at, event.data.text))
                        else -> Unit
                    }
                }
            }
            delay(200)
            while (candidates.tryReceive().isSuccess) { }
            while (commits.tryReceive().isSuccess) { }
            val inputs = listOf("nihao", "zhongguo", "shurufa", "wode", "xingneng", "zhongzhou", "fangan", "zhongwen")
            val keyReturn = mutableListOf<Long>()
            val candidateReady = mutableListOf<Long>()
            val commitReady = mutableListOf<Long>()
            val wordReady = mutableListOf<Long>()
            val output = mutableListOf<String>()
            val candidateDigest = MessageDigest.getInstance("SHA-256")
            suspend fun word(input: String, measure: Boolean) {
                val wordStart = System.nanoTime()
                for (key in input.map { it.toString() } + "space") {
                    assertTrue("Unconsumed candidate event before $key", candidates.tryReceive().isFailure)
                    val start = System.nanoTime()
                    connection.runOnReady { sendKey(key, KeyState.Virtual.state) }
                    val returned = System.nanoTime()
                    val update = withTimeout(30_000) { candidates.receive() }
                    assertTrue("Candidate update must belong to this key", update.at >= start)
                    assertEquals("Only space clears this replay's composition", key == "space", update.words.isEmpty())
                    if (measure) {
                        keyReturn += returned - start
                        candidateReady += update.at - start
                        for (text in update.words) {
                            val bytes = text.toByteArray(Charsets.UTF_8)
                            candidateDigest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                            candidateDigest.update(bytes)
                        }
                        candidateDigest.update(0xff.toByte())
                    }
                    if (key == "space") {
                        val commit = withTimeout(30_000) { commits.receive() }
                        assertTrue(commit.text.any { it.code in 0x3400..0x9fff })
                        if (measure) {
                            commitReady += commit.at - start
                            wordReady += maxOf(commit.at, update.at) - wordStart
                            output += commit.text
                        }
                    }
                }
            }
            repeat(20) { word(inputs[it % inputs.size], false) }
            delay(200)
            val maps = File("/proc/self/maps").readText()
            assertTrue("The official LTS model must actually be loaded", maps.contains("wanxiang-lts-zh-hans.gram"))
            assertEquals("Confirm the actual runtime backend", backend == "fcitx", maps.contains("libFcitx5Core.so"))
            val memoryBefore = memory()
            counts.clear()
            val cpuStart = Process.getElapsedCpuTime()
            val wallStart = System.nanoTime()
            Trace.beginAsyncSection("wanxiang.measure", 41)
            try { repeat(100) { word(inputs[it % inputs.size], true) } }
            finally { Trace.endAsyncSection("wanxiang.measure", 41) }
            val wallNanos = System.nanoTime() - wallStart
            val cpuMillis = Process.getElapsedCpuTime() - cpuStart
            val memoryAfter = memory()
            delay(200)
            assertTrue("No extra candidate updates", candidates.tryReceive().isFailure)
            assertTrue("No duplicate commits", commits.tryReceive().isFailure)
            assertEquals(100, counts["CommitStringEvent"]?.get())
            assertEquals(784, counts["PagedCandidateEvent"]?.get())
            collector.cancelAndJoin()
            val result = JSONObject().apply {
                put("backend", backend); put("app_commit", BuildConfig.BUILD_GIT_HASH)
                put("package", context.packageName); put("round", arguments.getString("round"))
                put("metric", "API call to candidate/commit event observed by the same collector; excludes touch handling and display rendering")
                put("warmup_words", 20); put("words", 100); put("keys", keyReturn.size)
                put("inputs", JSONArray(inputs)); put("commits", JSONArray(output))
                put("candidate_text_sha256", candidateDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                put("model_mapped", true)
                put("app_native_libraries", JSONArray(maps.lineSequence().map { it.substringAfterLast(' ') }
                    .filter { it.startsWith(context.applicationInfo.nativeLibraryDir + "/") && it.endsWith(".so") }
                    .map { File(it).name }.distinct().sorted().toList()))
                put("send_key_return", metrics(keyReturn)); put("key_to_candidates", metrics(candidateReady))
                put("space_to_commit", metrics(commitReady)); put("word_to_result", metrics(wordReady))
                put("wall_ms", wallNanos / 1e6); put("process_cpu_ms", cpuMillis)
                put("memory_before", memoryBefore); put("memory_after", memoryAfter)
                put("events", JSONObject(counts.mapValues { it.value.get() }))
            }
            File(context.filesDir, "engine-comparison.json").writeText(result.toString(2))
        } finally {
            scope.cancel()
            if (created) connection.runOnReady {
                setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "switch"), RawConfig("Name", previous))))
                setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "delete"), RawConfig("Name", profile))))
            }
            FcitxDaemon.disconnect(javaClass.name)
        }
    }
}
