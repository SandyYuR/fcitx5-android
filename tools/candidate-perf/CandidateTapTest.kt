/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.*
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/** The identical, instrumentation-only harness is built against both original products. */
class CandidateTapTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
    }
    private suspend fun FcitxAPI.profiles(): RawConfig = getAddonSubConfig("rime", "profile-manager").let { it.findByName("cfg") ?: it }
    private suspend fun await(message: String, condition: () -> Boolean) {
        withTimeout(60_000) { while (!condition()) delay(10) }
        check(condition()) { message }
    }
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
                            size += n; require(size <= 1024L * 1024 * 1024)
                            dest.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    @Test fun prepare(): Unit = runBlocking {
        val connection = FcitxDaemon.connect(javaClass.name)
        try {
            connection.runOnReady {
                setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "create"), RawConfig("Name", "candidate-bench"))))
                check(profiles()["Error"].value.isEmpty())
            }
            val directory = File(ctx.getExternalFilesDir(null), "data/candidate-bench")
            extract(File(ctx.filesDir, "candidate-fixture.zip"), directory)
            connection.runOnReady {
                setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "No"))))
                setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "switch"), RawConfig("Name", "candidate-bench"))))
                check(profiles()["Current"].value == "candidate-bench")
                setCapFlags(CapabilityFlags.DefaultFlags); setCandidatePagingMode(1)
                activate(31001, "io.github.sandyyur.inputbench"); activateIme("rime"); focus(true)
            }
            withTimeout(240_000) {
                while (connection.runOnReady { focus(true); currentIme().subMode.icon } != "fcitx-rime:wanxiang") delay(100)
            }
            assertTrue(File("/proc/self/maps").readText().contains("wanxiang-lts-zh-hans.gram"))
        } finally { FcitxDaemon.disconnect(javaClass.name) }
    }

    private fun find(text: String): View? {
        fun walk(view: View): View? {
            if (!view.isShown) return null
            if (view is AutoScaleTextView && (view.text.toString().trim().equals(text, true) ||
                    (text == "你" && view.text.toString().trim().startsWith(text)))) {
                var parent: View? = view
                while (parent != null && parent !is CustomGestureView) parent = parent.parent as? View
                return parent ?: view
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::walk)
    }

    @Test fun replay(): Unit = runBlocking {
        check(BuildConfig.BUILD_GIT_HASH == args.getString("expectedCommit")) { "Wrong product: ${BuildConfig.BUILD_GIT_HASH}" }
        ins.runOnMainSync {
            val prefs = AppPrefs.getInstance()
            prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
            prefs.keyboard.textKeyboardLayoutProfile.setValue("default")
            prefs.keyboard.popupOnKeyPress.setValue(false)
        }
        val connection = FcitxDaemon.connect(javaClass.name)
        val commits = Channel<Pair<String, Long>>(Channel.UNLIMITED)
        val preedits = Channel<Pair<String, Long>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            connection.runImmediately { eventFlow }.collect {
                when (it) {
                    is FcitxEvent.CommitStringEvent -> commits.send(it.data.text to now())
                    is FcitxEvent.InputPanelEvent -> preedits.send(it.data.preedit.toString() to now())
                    else -> {}
                }
            }
        }
        var wrappedWindow: Window? = null
        var oldCallback: Window.Callback? = null
        try {
            // No input API warm-up, fake keys, or wait for native readiness in this fresh process.
            shell("am start -W -n io.github.sandyyur.inputbench/.BenchHost")
            await("Keyboard visible") { var shown = false; ins.runOnMainSync { shown = find("n") != null }; shown }
            // The initial Latin Android subtype may override the saved Chinese mode.
            // Select the real schema, without typing a warm-up word.
            connection.runOnReady {
                if (currentIme().subMode.icon != "fcitx-rime:wanxiang") activateIme("rime")
                setCandidatePagingMode(1)
            }
            var dispatchDown = 0L
            var dispatchUp = 0L
            var dispatchReturned = 0L
            ins.runOnMainSync {
                var view: View? = find("n")
                while (view != null && view !is BaseInputView) view = view.parent as? View
                val window = (view as BaseInputView).service.window.window!!
                val previous = window.callback
                wrappedWindow = window; oldCallback = previous
                window.callback = object : Window.Callback by previous {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) dispatchDown = now()
                        if (event.actionMasked == MotionEvent.ACTION_UP) dispatchUp = now()
                        return previous.dispatchTouchEvent(event).also {
                            if (event.actionMasked == MotionEvent.ACTION_UP) dispatchReturned = now()
                        }
                    }
                }
            }
            fun tap(text: String): JSONObject {
                var bounds: Rect? = null
                ins.runOnMainSync {
                    val view = requireNotNull(find(text)) { "Missing $text" }
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    bounds = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
                    dispatchDown = 0; dispatchUp = 0; dispatchReturned = 0
                }
                val target = bounds!!
                val down = SystemClock.uptimeMillis()
                var injectUp = 0L
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    if (action == MotionEvent.ACTION_UP) injectUp = now()
                    try { check(ins.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(20)
                }
                check(dispatchUp > 0 && dispatchReturned >= dispatchUp) { "Touch did not reach IME window" }
                return JSONObject().put("key", text).put("inject_up_ns", injectUp)
                    .put("dispatch_down_ns", dispatchDown).put("dispatch_up_ns", dispatchUp).put("dispatch_return_ns", dispatchReturned)
            }
            val rounds = JSONArray()
            repeat(6) { round ->
                // Six taps in one editor distinguish first-use cost from repeated binding cost.
                val n = tap("n")
                val preedit = withTimeout(60_000) { var p: Pair<String, Long>; do { p = preedits.receive() } while (p.first != "n"); p.second }
                val i = tap("i")
                await("Candidate bound") { var found = false; ins.runOnMainSync { found = find("你") != null }; found }
                val select = tap("你")
                val commit = withTimeout(60_000) { commits.receive() }
                assertEquals("你", commit.first)
                // Dumping happens AFTER the commit; polling never determines the recorded latency.
                shell("am broadcast -n io.github.sandyyur.inputbench/.BenchControl --es command dump")
                rounds.put(JSONObject().put("round", round + 1).put("n", n).put("i", i).put("select", select)
                    .put("preedit_collected_ns", preedit).put("commit_collected_ns", commit.second))
            }
            assertNull(withTimeoutOrNull(250) { commits.receive() })
            val maps = File("/proc/self/maps").readText()
            val labels = connection.runOnReady { statusArea().map { it.shortText } }
            for (expected in listOf("上下文开", "简码开", "小字集")) assertTrue("Missing $expected: $labels", labels.any { it.startsWith(expected) })
            assertTrue(maps.contains("wanxiang-lts-zh-hans.gram"))
            File(ctx.filesDir, "candidate-taps.json").writeText(JSONObject().put("commit", BuildConfig.BUILD_GIT_HASH)
                .put("clock", "SystemClock.elapsedRealtimeNanos, across both Android processes")
                .put("model_mapped", true).put("fcitx_core_mapped", maps.contains("libFcitx5Core.so"))
                .put("switch_labels", JSONArray(labels)).put("rounds", rounds).toString(2))
        } finally {
            ins.runOnMainSync { wrappedWindow?.callback = oldCallback }
            collector.cancelAndJoin()
            FcitxDaemon.disconnect(javaClass.name)
        }
    }
}
