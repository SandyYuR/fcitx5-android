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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private class Target(val view: View, val bounds: Rect)

    private class Touch(val key: String, val target: Target, val plannedDown: Long, val plannedUp: Long) {
        // Allocated on the injection thread, before dispatch; the UI only checks this target.
        val location = IntArray(2)
        @Volatile var injectDown = 0L
        @Volatile var injectUp = 0L
        @Volatile var downReturned = 0L
        @Volatile var upReturned = 0L
        @Volatile var dispatchDown = 0L
        @Volatile var dispatchUp = 0L
        @Volatile var dispatchReturned = 0L
        @Volatile var geometryValid = false
        val completed = CountDownLatch(1)
        fun result() = JSONObject().put("key", key)
            .put("planned_down_ns", plannedDown).put("planned_up_ns", plannedUp)
            .put("inject_down_ns", injectDown).put("inject_up_ns", injectUp)
            .put("inject_down_return_ns", downReturned).put("inject_up_return_ns", upReturned)
            .put("dispatch_down_ns", dispatchDown).put("dispatch_up_ns", dispatchUp)
            .put("dispatch_return_ns", dispatchReturned).put("geometry_valid", geometryValid)
    }

    private fun waitUntil(time: Long) {
        while (true) {
            val remaining = time - now()
            if (remaining <= 0) return
            SystemClock.sleep((remaining + 999_999L) / 1_000_000L)
        }
    }

    private fun target(text: String): Target {
        val view = requireNotNull(find(text)) { "Missing $text" }
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return Target(view, Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height))
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
        val inputClass = Class.forName("android.hardware.input.InputManagerGlobal")
        val inputManager = inputClass.getMethod("getInstance").invoke(null)
        val inject = inputClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType!!)
        var wrappedWindow: Window? = null
        var oldCallback: Window.Callback? = null
        try {
            ins.uiAutomation.adoptShellPermissionIdentity("android.permission.INJECT_EVENTS")
            // No input API warm-up, fake keys, or wait for native readiness in this fresh process.
            shell("am start -W -n io.github.sandyyur.inputbench/.BenchHost")
            await("Keyboard visible") { var shown = false; ins.runOnMainSync { shown = find("n") != null }; shown }
            // The initial Latin Android subtype may override the saved Chinese mode.
            // Select the real schema, without typing a warm-up word.
            connection.runOnReady {
                if (currentIme().subMode.icon != "fcitx-rime:wanxiang") activateIme("rime")
                setCandidatePagingMode(1)
            }
            val touches = ConcurrentHashMap<Long, Touch>()
            val letters = HashMap<String, Target>()
            ins.runOnMainSync {
                letters["n"] = target("n")
                letters["i"] = target("i")
                var view: View? = letters.getValue("n").view
                while (view != null && view !is BaseInputView) view = view.parent as? View
                val window = (view as BaseInputView).service.window.window!!
                val previous = window.callback
                wrappedWindow = window; oldCallback = previous
                window.callback = object : Window.Callback by previous {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val touch = touches[event.downTime]
                        if (event.actionMasked == MotionEvent.ACTION_DOWN && touch != null) {
                            touch.dispatchDown = now()
                            // Do not traverse every window/view inside the measured handler.
                            // A detached/replaced target fails instead of silently finding a new key.
                            val view = touch.target.view
                            val xy = touch.location
                            if (view.isAttachedToWindow && view.isShown) {
                                view.getLocationOnScreen(xy)
                                touch.geometryValid = event.rawX >= xy[0] && event.rawX < xy[0] + view.width &&
                                    event.rawY >= xy[1] && event.rawY < xy[1] + view.height
                            }
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP && touch != null) touch.dispatchUp = now()
                        return try { previous.dispatchTouchEvent(event) } finally {
                            if (event.actionMasked == MotionEvent.ACTION_UP && touch != null) {
                                touch.dispatchReturned = now()
                                touch.completed.countDown()
                            }
                        }
                    }
                }
            }
            fun tap(text: String, target: Target, plannedDown: Long): Touch {
                waitUntil(plannedDown)
                val down = SystemClock.uptimeMillis()
                val touch = Touch(text, target, plannedDown, plannedDown + 20_000_000L)
                check(touches.putIfAbsent(down, touch) == null) { "Duplicate touch identity" }
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    // A delayed DOWN must retain the intended physical hold instead of
                    // immediately chasing an UP deadline that has already passed.
                    if (action == MotionEvent.ACTION_UP) waitUntil(maxOf(touch.plannedUp, touch.injectDown + 20_000_000L))
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.bounds.exactCenterX(), target.bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    if (action == MotionEvent.ACTION_DOWN) touch.injectDown = now() else touch.injectUp = now()
                    // Test-only direct asynchronous injection avoids UiAutomationConnection's
                    // surface/animation transaction synchronization before DOWN and after UP.
                    try { check(inject.invoke(inputManager, event, 0) as Boolean) } finally { event.recycle() }
                    if (action == MotionEvent.ACTION_DOWN) touch.downReturned = now() else touch.upReturned = now()
                }
                return touch
            }
            fun awaitTouch(touch: Touch) {
                check(touch.completed.await(60, TimeUnit.SECONDS)) { "Missing ${touch.key} dispatch" }
                check(touch.dispatchDown > 0 && touch.dispatchUp >= touch.dispatchDown && touch.dispatchReturned >= touch.dispatchUp)
                check(touch.geometryValid) { "Cached coordinate missed actual ${touch.key} key" }
            }
            val rounds = JSONArray()
            repeat(6) { round ->
                // Six taps in one editor distinguish first-use cost from repeated binding cost.
                val plannedN = now() + 20_000_000L
                val n = tap("n", letters.getValue("n"), plannedN)
                val i = tap("i", letters.getValue("i"), plannedN + 100_000_000L)
                awaitTouch(n); awaitTouch(i)
                var preedit: Long? = null
                val niReady = withTimeout(60_000) {
                    var entry: Pair<String, Long>
                    do {
                        entry = preedits.receive()
                        if (entry.first == "n" && entry.second >= n.plannedDown) preedit = entry.second
                    } while (entry.first != "ni")
                    entry.second
                }
                // Selection is measured after the ni UI settles; this does not gate n/i injection.
                ins.waitForIdleSync()
                await("Candidate bound") { var found = false; ins.runOnMainSync { found = find("你") != null }; found }
                var candidateTarget: Target? = null
                ins.runOnMainSync { candidateTarget = target("你") }
                val select = tap("你", candidateTarget!!, now() + 5_000_000L)
                awaitTouch(select)
                val commit = withTimeout(60_000) { commits.receive() }
                assertEquals("你", commit.first)
                // Dumping happens AFTER the commit; polling never determines the recorded latency.
                shell("am broadcast -n io.github.sandyyur.inputbench/.BenchControl --es command dump")
                rounds.put(JSONObject().put("round", round + 1).put("n", n.result()).put("i", i.result()).put("select", select.result())
                    .put("ni_ready_ns", niReady).put("preedit_collected_ns", preedit ?: JSONObject.NULL).put("commit_collected_ns", commit.second))
            }
            assertNull(withTimeoutOrNull(250) { commits.receive() })
            val maps = File("/proc/self/maps").readText()
            val labels = connection.runOnReady { statusArea().map { it.shortText } }
            for (expected in listOf("上下文开", "简码开", "小字集")) assertTrue("Missing $expected: $labels", labels.any { it.startsWith(expected) })
            assertTrue(maps.contains("wanxiang-lts-zh-hans.gram"))
            File(ctx.filesDir, "candidate-taps.json").writeText(JSONObject().put("commit", BuildConfig.BUILD_GIT_HASH)
                .put("injection_protocol", "fixed_cadence_100ms_input_manager_async_cached_targets_v2")
                .put("touch_hold_policy", "minimum_20ms_from_actual_injection_down")
                .put("clock", "SystemClock.elapsedRealtimeNanos, across both Android processes")
                .put("model_mapped", true).put("fcitx_core_mapped", maps.contains("libFcitx5Core.so"))
                .put("switch_labels", JSONArray(labels)).put("rounds", rounds).toString(2))
        } finally {
            ins.uiAutomation.dropShellPermissionIdentity()
            ins.runOnMainSync { wrappedWindow?.callback = oldCallback }
            collector.cancelAndJoin()
            FcitxDaemon.disconnect(javaClass.name)
        }
    }
}
