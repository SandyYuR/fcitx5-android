/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.os.Debug
import android.os.Process
import android.view.*
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/** Identical instrumentation-only fixture in two independently pinned product checkouts. */
class ScreenFixtureTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText().trim()
    }
    private suspend fun FcitxAPI.profiles(): RawConfig = getAddonSubConfig("rime", "profile-manager").let { it.findByName("cfg") ?: it }
    private fun rect(view: View): Rect {
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return Rect(xy[0], xy[1], xy[0]+view.width, xy[1]+view.height)
    }
    private fun json(r: Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
    private fun extract(archive: File, target: File) {
        val root = target.canonicalPath + File.separator
        var size = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                require(out.path.startsWith(root))
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile!!.mkdirs()
                    out.outputStream().buffered().use { dest ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            size += n; require(size <= 1024L*1024*1024)
                            dest.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
    private suspend fun await(message: String, timeout: Long = 30_000, condition: () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(100) }
        assertTrue(message, condition())
    }
    private fun views(): List<Pair<String, Rect>> {
        val out = mutableListOf<Pair<String, Rect>>()
        ins.runOnMainSync {
            fun walk(v: View) {
                if (!v.isShown) return
                if (v is AutoScaleTextView) {
                    var parent: View? = v
                    while (parent != null && parent !is KeyView) parent = parent.parent as? View
                    out += v.text.toString().trim() to rect(parent ?: v)
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            WindowInspector.getGlobalWindowViews().forEach(::walk)
        }
        return out
    }
    private fun touch(r: Rect, hold: Long = 32): Long {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, r.exactCenterX(), r.exactCenterY(), 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(ins.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(hold)
        }
        return down
    }
    private fun hardware(c: Char) {
        val code = if (c == ' ') KeyEvent.KEYCODE_SPACE else KeyEvent.KEYCODE_A + (c - 'a')
        val now = SystemClock.uptimeMillis()
        check(ins.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true))
        check(ins.uiAutomation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true))
    }

    @Test fun replay(): Unit = runBlocking {
        val backend = args.getString("backend")!!
        val expectedCommit = if (backend == "fcitx") "cecea494" else "cfd0ddc4"
        check(BuildConfig.BUILD_GIT_HASH.startsWith(expectedCommit)) { "Unexpected product revision: ${BuildConfig.BUILD_GIT_HASH}" }
        val mode = args.getString("mode")!!
        val schedule = JSONObject(File(ctx.filesDir, "screen-schedule.json").readText())
        val profile = "screen-bench"
        val directory = File(ctx.getExternalFilesDir(null), "data/$profile")
        check(FcitxDaemon.getFirstConnectionOrNull() == null) { "Engine must be stopped before resetting the benchmark profile" }
        val reset = directory.listFiles().orEmpty().filter { it.name.contains(".userdb") }
        reset.forEach { check(it.deleteRecursively()) { "Cannot reset $it" } }
        if (directory.isDirectory) File(ctx.filesDir, "screen-user.yaml").copyTo(File(directory, "user.yaml"), overwrite = true)
        val connection = FcitxDaemon.connect(javaClass.name)
        try {
            val prefs = AppPrefs.getInstance()
            ins.runOnMainSync {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
                prefs.keyboard.textKeyboardLayoutProfile.setValue("benchmark_builtin")
                // Preview balloons can cover the candidate strip before Rime reacts.
                prefs.keyboard.popupOnKeyPress.setValue(false)
                ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
                ThemeManager.prefs.keyRippleEffect.setValue(false)
                ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
            }
            connection.runOnReady {
                activate(31001, "io.github.sandyyur.inputbench")
                if (!directory.isDirectory) {
                    setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "create"), RawConfig("Name", profile))))
                    check(profiles()["Error"].value.isEmpty())
                }
            }
            if (!File(directory, "wanxiang.schema.yaml").isFile) extract(File(ctx.filesDir, "wanxiang-perf.zip"), directory)
            connection.runOnReady {
                setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "No"))))
                setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "switch"), RawConfig("Name", profile))))
                check(profiles()["Current"].value == profile)
                setCapFlags(CapabilityFlags.DefaultFlags); setCandidatePagingMode(1)
                activateIme("rime"); focus(true)
            }
            withTimeout(240_000) { while (connection.runOnReady { focus(true); currentIme().subMode.icon } != "fcitx-rime:wanxiang") delay(100) }
            val component = "${ctx.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
            shell("ime enable $component"); shell("ime set $component")
            shell("am start -W -n io.github.sandyyur.inputbench/.BenchHost")
            await("Keyboard visible") { views().any { it.first.equals("n", true) } }
            delay(1500)
            connection.runOnReady {
                // Set after the real editor has created its input session.
                // User requested context reorder ON; their YAML lacks its saved option.
                val action = statusArea().firstOrNull { it.shortText.startsWith("上下文关") }
                if (action != null) activateAction(action.id)
            }
            // Untimed warmup populates identical paths; real Android hardware events, not API replay.
            for (c in "nihao zhongguo shurufa ") { hardware(c); delay(80) }
            delay(600)
            shell("am broadcast -n io.github.sandyyur.inputbench/.BenchControl --es command clear")
            // A non-empty candidate bar is required to locate its text region.
            for (c in "nihao") { hardware(c); delay(100) }
            delay(500)
            val all = views()
            val keys = JSONObject()
            for (c in 'a'..'z') {
                val r = all.filter { it.first.equals(c.toString(), true) }.maxByOrNull { it.second.width()*it.second.height() }?.second
                requireNotNull(r) { "No key $c; $all" }; keys.put(c.toString(), json(r))
            }
            val n = keys.getJSONArray("n")
            val space = all.filter { (it.first.contains("Rime", true) || it.first.contains("中州")) && it.second.top > n.getInt(1) }
                .maxByOrNull { it.second.width() }?.second
            requireNotNull(space) { "No space key; $all" }; keys.put(" ", json(space))
            val topKeys = ('a'..'z').minOf { keys.getJSONArray(it.toString()).getInt(1) }
            val candidate = all.filter { it.first.any { c -> c.code in 0x3400..0x9fff } && it.second.bottom <= topKeys }
                .maxByOrNull { it.second.width() }?.second
            requireNotNull(candidate) { "No candidate region; $all" }
            hardwareEscape()
            delay(400)
            // The first composition may resize the IME. A fixed coordinate must land
            // inside the same key in both states, rather than silently hitting a neighbour.
            val idle = views()
            for (c in ('a'..'z').map { it.toString() } + " ") {
                val a = keys.getJSONArray(c)
                val active = Rect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
                val other = idle.filter { if (c == " ") it.first.contains("Rime", true) || it.first.contains("中州") else it.first.equals(c, true) }
                    .maxByOrNull { it.second.width()*it.second.height() }?.second
                requireNotNull(other)
                check(other.contains(active.centerX(), active.centerY())) { "Keyboard geometry moves: $c $active -> $other" }
            }
            shell("am broadcast -n io.github.sandyyur.inputbench/.BenchControl --es command clear")
            val maps = File("/proc/self/maps").readText()
            val labels = connection.runOnReady { statusArea().map { it.shortText } }
            for (expected in listOf("上下文开", "简码开", "小字集"))
                assertTrue("Missing $expected in actual editor session; actual=$labels", labels.any { it.startsWith(expected) })
            assertTrue("LTS model really mapped", maps.contains("wanxiang-lts-zh-hans.gram"))
            assertEquals("Runtime engine identity", backend == "fcitx", maps.contains("libFcitx5Core.so"))
            val result = JSONObject().put("backend", backend).put("mode", mode).put("commit", BuildConfig.BUILD_GIT_HASH)
                .put("keys", keys).put("candidate", json(candidate)).put("switch_labels", JSONArray(labels))
                .put("all_labels", JSONArray(all.map { JSONObject().put("label", it.first).put("rect", json(it.second)) }))
                .put("model_mapped", true).put("fcitx_core_mapped", maps.contains("libFcitx5Core.so"))
                .put("reset_databases", JSONArray(reset.map { it.name }))
                .put("native_libraries_mapped", JSONArray(maps.lineSequence().map { it.substringAfterLast(' ') }
                    .filter { it.startsWith(ctx.applicationInfo.nativeLibraryDir + "/") && it.endsWith(".so") }
                    .map { File(it).name }.distinct().sorted().toList()))
            val compiled = File(directory, "build/wanxiang.schema.yaml").readText()
            assertTrue("User page size 6 compiled", Regex("(?m)^\\s+page_size:\\s+6\\s*$").containsMatchIn(compiled))
            assertTrue("User fallback reorder setting compiled", Regex("enable_fallback_reorder:\\s+true").containsMatchIn(compiled))
            File(ctx.filesDir, "screen-effective-schema.yaml").writeText(compiled)
            File(ctx.filesDir, "screen-ready.json").writeText(result.toString(2))
            val go = File(ctx.filesDir, "screen-go")
            await("Host recording started", 120_000) { go.isFile }
            val events = JSONArray()
            val marker = Rect(10, 10, 70, 50)
            fun mark() { val t = touch(marker); events.put(JSONObject().put("kind", "marker").put("down_ms", t)); SystemClock.sleep(250) }
            repeat(6) { mark() }
            delay(600)
            val start = SystemClock.uptimeMillis()
            val cpuStart = Process.getElapsedCpuTime()
            val steps = schedule.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val due = start + step.getLong("at_ms")
                SystemClock.sleep((due - SystemClock.uptimeMillis()).coerceAtLeast(0))
                val c = step.getString("key")[0]
                val before = SystemClock.uptimeMillis()
                if (mode == "hardware") hardware(c) else {
                    val a = keys.getJSONArray(c.toString()); touch(Rect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)))
                }
                events.put(JSONObject().put("kind", "key").put("key", c.toString()).put("down_ms", before)
                    .put("return_ms", SystemClock.uptimeMillis()).put("due_ms", due))
            }
            val typingCpu = Process.getElapsedCpuTime() - cpuStart
            val typingWall = SystemClock.uptimeMillis() - start
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            delay(1200)
            repeat(6) { mark() }
            result.put("events", events).put("wall_ms", SystemClock.uptimeMillis()-start)
                .put("typing_cpu_ms", typingCpu).put("typing_wall_ms", typingWall).put("typing_end_pss_kib", memory.totalPss)
            File(ctx.filesDir, "screen-result.json").writeText(result.toString(2))
        } finally { FcitxDaemon.disconnect(javaClass.name) }
    }
    private fun hardwareEscape() {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            check(ins.uiAutomation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_ESCAPE, 0), true))
    }
}
