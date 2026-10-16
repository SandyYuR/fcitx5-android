package org.fcitx.directrime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Trace
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Minimal real IME for behavior checks; it does not share the shipping IME's UI or preferences. */
class DirectRimeService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serial = Mutex()
    private var generation = 0L
    private var state: Snapshot? = null
    private var shift = false
    private var password = false
    private var accent = 0
    private val forwardedDown = HashSet<Int>()
    private lateinit var status: TextView
    private lateinit var preedit: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var tabs: LinearLayout

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val previous = generation
        generation = RimeRuntime.newGeneration()
        state = null; accent = 0; shift = false; forwardedDown.clear()
        clearUi()
        val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
        val kind = attribute.inputType and InputType.TYPE_MASK_CLASS
        password = (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val expected = generation
        enqueue(expected) {
            // Passwords bypass librime entirely, including its user dictionary.
            if (password) { RimeRuntime.end(previous); statusText("密码输入：直接英文"); null }
            else RimeRuntime.begin(applicationContext, expected, attribute.packageName ?: "unknown")
        }
    }

    override fun onFinishInput() {
        val old = generation
        generation = RimeRuntime.newGeneration() // invalidate before coroutine cancellation/queueing
        state = null; accent = 0; forwardedDown.clear()
        currentInputConnection?.finishComposingText()
        scope.launch { serial.withLock { RimeRuntime.end(old) } }
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        val old = generation
        generation = RimeRuntime.newGeneration()
        state = null; accent = 0; forwardedDown.clear()
        scope.launch { serial.withLock { RimeRuntime.end(old) } }
        super.onUnbindInput()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, 4, 4, 4) }
        status = TextView(this).also { root.addView(it) }
        preedit = TextView(this).apply { textSize = 20f }.also { root.addView(it) }
        tabs = row(); root.addView(HorizontalScrollView(this).apply { addView(tabs) })
        candidates = row(); root.addView(HorizontalScrollView(this).apply { addView(candidates) })
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { keys ->
            root.addView(row().apply { keys.forEach { c -> addView(button(c.toString()) { virtual(c.code) }, weight()) } })
        }
        root.addView(row().apply {
            addView(button("Shift") { shift = !shift; statusText(if (shift) "Shift" else state?.gateMessage.orEmpty()) }, weight())
            addView(button("中/英") { action(6) }, weight())
            addView(button("⌫") { virtual(0xff08) }, weight())
            addView(button("空格") { virtual(0x20) }, weight())
            addView(button("↵") { virtual(0xff0d) }, weight())
        })
        root.addView(row().apply {
            addView(button("上页") { action(2, -1) }, weight())
            addView(button("下页") { action(2, 1) }, weight())
            addView(button("清空") { action(0) }, weight())
            addView(button("方案") { chooseSchema() }, weight())
            addView(button("实验室") { startActivity(Intent(this@DirectRimeService, LabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, weight())
        })
        state?.let(::render) ?: statusText(if (password) "密码输入：直接英文" else "引擎启动中")
        return root
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label; minWidth = 0; minimumWidth = 0; setPadding(2, 2, 2, 2); setOnClickListener { click() }
    }
    private fun statusText(text: String) { if (::status.isInitialized) status.text = text }
    private fun clearUi() {
        if (!::status.isInitialized) return
        status.text = "会话切换中"; preedit.text = ""
        candidates.removeAllViews(); tabs.removeAllViews()
    }
    private fun enqueue(expected: Long = generation, block: suspend () -> Snapshot?) {
        scope.launch {
            serial.withLock {
                if (expected != generation || !RimeRuntime.current(expected)) return@withLock
                try {
                    val next = block() ?: return@withLock
                    if (expected != generation || !RimeRuntime.current(expected) || next.gate == 3) return@withLock
                    // A commit is a transaction result consumed once here on the main thread.
                    // It is never replayed by render(), view recreation, or a state collector.
                    Trace.beginSection("rime.direct.editor_update")
                    try {
                        if (next.commit.isNotEmpty()) {
                            Trace.beginSection("rime.direct.commit")
                            try { currentInputConnection?.commitText(next.commit, 1) }
                            finally { Trace.endSection() }
                        }
                        currentInputConnection?.setComposingText(next.preedit, 1)
                    }
                    finally { Trace.endSection() }
                    state = next
                    render(next)
                } catch (e: Exception) {
                    statusText("引擎错误：${e.message}。请打开实验室重新部署")
                }
            }
        }
    }
    private fun render(next: Snapshot) {
        if (!::status.isInitialized) return
        Trace.beginSection("rime.direct.render")
        try {
            status.text = next.gateMessage; preedit.text = next.preedit
            // The composing span and candidate strip are state; commits are not state.
            candidates.removeAllViews()
            next.candidates.forEachIndexed { index, text ->
                val label = buildString {
                    append(text)
                    next.comments.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { append(" $it") }
                    next.codes.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { append(" [$it]") }
                }
                candidates.addView(button(label) { action(1, index, expected = next) }.apply {
                    setOnLongClickListener { action(5, index, expected = next); true }
                })
            }
            tabs.removeAllViews()
            next.tabs.forEachIndexed { index, label -> tabs.addView(button(label) { action(4, index, expected = next) }) }
            if (next.tabs.isNotEmpty()) tabs.addView(button("清除音节约束") { action(7, expected = next) })
        } finally { Trace.endSection() }
    }
    private fun action(action: Int, index: Int = 0, value: String = "", expected: Snapshot? = null) {
        val snapshot = expected ?: state ?: return
        enqueue { RimeRuntime.action(snapshot, action, index, value) }
    }
    private fun chooseSchema() {
        scope.launch {
            try {
                val schemas = RimeRuntime.schemas(applicationContext)
                if (schemas.isEmpty()) statusText("无方案，请打开实验室导入 ZIP")
                else {
                    val selected = state ?: return@launch
                    val index = (schemas.indexOf(selected.schema) + 1) % schemas.size
                    action(3, value = schemas[index])
                }
            } catch (e: Exception) { statusText("读取方案失败：${e.message}") }
        }
    }
    private fun virtual(sym: Int) {
        val shifted = if (shift && sym in 'a'.code..'z'.code) sym - 32 else sym
        if (password) { fallback(shifted); return }
        // Capture deploy state at arrival, before the service's serial queue.
        if (RimeRuntime.blocked) { statusText("部署或启动中，按键已拦截"); return }
        enqueue {
            RimeRuntime.key(generation, shifted, if (shift) 1 else 0)?.also { result ->
                if (result.ready && !result.handled && RimeRuntime.current(result.generation)) fallback(shifted)
            }
        }
    }
    private fun fallback(sym: Int) {
        val codePoint = if ((sym and -0x1000000) == 0x01000000) sym and 0xffffff else sym
        when (sym) {
            0xff08 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            0xff0d -> sendKeyChar('\n')
            else -> if (codePoint in 0x20..0x10ffff && sym !in 0xff00..0xffff)
                currentInputConnection?.commitText(String(Character.toChars(codePoint)), 1)
        }
    }
    private fun hardware(keyCode: Int, event: KeyEvent): Boolean {
        if (password || keyCode == KeyEvent.KEYCODE_BACK) return false
        val sym = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> 0xff08
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
            KeyEvent.KEYCODE_TAB -> 0xff09
            KeyEvent.KEYCODE_ESCAPE -> 0xff1b
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
            KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            KeyEvent.KEYCODE_PAGE_UP -> 0xff55
            KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
            KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
            KeyEvent.KEYCODE_MOVE_END -> 0xff57
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
            KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
            else -> {
                val cp = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
                if (cp == 0) return false
                if (cp and KeyCharacterMap.COMBINING_ACCENT != 0) {
                    if (event.action == KeyEvent.ACTION_DOWN) accent = cp and KeyCharacterMap.COMBINING_ACCENT_MASK
                    return true
                }
                var composed = cp
                if (accent != 0 && event.action == KeyEvent.ACTION_DOWN) {
                    composed = KeyEvent.getDeadChar(accent, cp).takeIf { it != 0 } ?: cp
                    accent = 0
                }
                if (composed <= 0xff) composed else 0x01000000 or composed
            }
        }
        if (RimeRuntime.blocked) return true
        val expected = generation
        var mask = 0
        if (event.isShiftPressed) mask = mask or 1
        if (event.isCapsLockOn) mask = mask or 2
        if (event.isCtrlPressed) mask = mask or 4
        if (event.isAltPressed) mask = mask or 8
        if (event.isMetaPressed) mask = mask or 64
        enqueue(expected) {
            RimeRuntime.key(expected, sym, mask, event.action == KeyEvent.ACTION_UP)?.also { result ->
                if (result.ready && RimeRuntime.current(expected)) {
                    if (event.action == KeyEvent.ACTION_DOWN && !result.handled) {
                        currentInputConnection?.sendKeyEvent(event); forwardedDown.add(keyCode)
                    } else if (event.action == KeyEvent.ACTION_UP && forwardedDown.remove(keyCode)) {
                        currentInputConnection?.sendKeyEvent(event)
                    }
                }
            }
        }
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = hardware(keyCode, event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = hardware(keyCode, event) || super.onKeyUp(keyCode, event)
    override fun onEvaluateFullscreenMode() = false
    override fun onDestroy() {
        val old = generation
        val invalidation = RimeRuntime.newGeneration()
        scope.cancel()
        // Destruction must still enqueue session release after cancelling UI jobs.
        RimeRuntime.release(old, invalidation)
        super.onDestroy()
    }
}
