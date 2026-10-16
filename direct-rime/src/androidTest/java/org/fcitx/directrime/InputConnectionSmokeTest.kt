package org.fcitx.directrime

import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream

/** Run on a disposable emulator: temporarily selects the experimental IME, then restores it. */
class InputConnectionSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val waitMs = InstrumentationRegistry.getArguments().getString("waitMs")?.toLongOrNull() ?: 30_000L
        require(waitMs in 1_000..180_000)
        val deadline = SystemClock.uptimeMillis() + waitMs
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
    @Test fun hardwareKeysCommitIntoTheCorrectEditor(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val directComponent = "${context.packageName}/org.fcitx.directrime.DirectRimeService"
        // Optional external IME lets the same editor/key workload validate the
        // baseline. Its deterministic fixture must be installed separately.
        val component = InstrumentationRegistry.getArguments().getString("imeComponent") ?: directComponent
        val direct = component == directComponent
        require(component.matches(Regex("[A-Za-z0-9_./]+")))
        if (direct) assertTrue(RimeRuntime.deploy(context, smoke = true))
        val previous = shell("settings get secure default_input_method")
        val wasEnabled = component in shell("ime list -s")
        var activity: LabActivity? = null
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("ime enable $component"); shell("ime set $component")
            activity = instrumentation.startActivitySync(Intent(context, LabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LabActivity
            val first = activity.findViewById<EditText>(R.id.editor_one)
            val second = activity.findViewById<EditText>(R.id.editor_two)
            fun focus(editor: EditText) {
                instrumentation.runOnMainSync {
                    editor.requestFocus()
                    (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                instrumentation.waitForIdleSync()
                await("Rime session did not become ready") {
                    if (direct) !RimeRuntime.blocked
                    else {
                        // A hardware-keyboard IME may use a floating candidate
                        // window with zero IME insets. Wait for the service's
                        // view lifecycle, not a particular keyboard geometry.
                        (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).isActive(editor) &&
                            "mInputViewStarted=true" in shell("dumpsys activity service $component")
                    }
                }
                val delay = InstrumentationRegistry.getArguments().getString("readyDelayMs")?.toLongOrNull() ?: 0L
                require(delay in 0..30_000)
                if (delay > 0) SystemClock.sleep(delay)
            }
            fun text(editor: EditText): String {
                var value = ""
                instrumentation.runOnMainSync { value = editor.text.toString() }
                return value
            }
            focus(first)
            shell("input text ni")
            shell("input keyevent KEYCODE_SPACE")
            await("First editor did not receive commit") { text(first) == "你" }
            focus(second)
            shell("input text hao")
            shell("input keyevent KEYCODE_SPACE")
            await("Second editor did not receive commit") { text(second) == "好" }
            assertEquals("你", text(first))
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
            if (!wasEnabled) shell("ime disable $component")
        }
    }
}
