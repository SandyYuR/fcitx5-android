/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * ConciseTree（Release 默认的 tree，或 verbose 关闭时的 Debug）必须只打印一份堆栈，且限长真的生效。
 *
 * Timber 的 prepareLog() 在调用 Tree.log 之前就把堆栈拼进 message 了（有 message 时
 * message += "\n" + 堆栈，只有 t 时 message 就是堆栈）。早先这里又追加了一次
 * Log.getStackTraceString(t)，于是同一份堆栈打印两遍，而且 truncateStackTrace 只作用于
 * 复制出来的那一份，真正打印的 message 仍然无界——限长形同虚设。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConciseTreeTest {

    private fun throwable(frames: Int, cause: Throwable? = null) =
        RuntimeException("boom", cause).apply {
            stackTrace = Array(frames) {
                StackTraceElement("com.example.Cls$it", "method$it", "Cls.kt", it + 1)
            }
        }

    /**
     * 捕获 ConciseTree 实际打出的内容。
     *
     * 只取本线程 tag 的条目：ConciseTree 没拿到业务 tag 时会退回 `[线程名]`，而 Robolectric 在整个
     * 测试进程里还会收到应用启动阶段别的 tag 的日志，不过滤会混进来。
     */
    private fun capture(priority: Int, message: String, t: Throwable?): List<String> {
        val expectedTag = "[${Thread.currentThread().name}]"
        ShadowLog.clear()
        // 走 Tree 的公开重载，才和真实调用一样经过 prepareLog（堆栈就是在那里拼进 message 的）
        ConciseTree().log(priority, t, message)
        return ShadowLog.getLogs().filter { it.tag == expectedTag }.map { it.msg }
    }

    private val String.frameCount get() = lines().count { it.trimStart().startsWith("at ") }
    private val String.causedByFrameCount
        get() = lines().dropWhile { !it.startsWith("Caused by:") }
            .drop(1)
            .takeWhile { it.trimStart().startsWith("at ") }
            .size

    @Test
    fun theStackTraceIsPrintedOnceAndTheFrameLimitActuallyApplies() {
        val printed = capture(Log.WARN, "engine died", throwable(frames = 200))

        assertEquals("Timber prints one entry per log call", 1, printed.size)
        val text = printed.single()
        assertTrue("message must still be present: " + text.take(200), text.startsWith("engine died"))
        assertEquals(
            "the throwable was appended twice: " + text.take(300),
            1,
            Regex(Regex.escape("java.lang.RuntimeException: boom")).findAll(text).count()
        )
        assertTrue("the frame limit did not apply to the printed message", text.contains("(frames truncated)"))
        assertEquals("main exception must keep at most 40 frames", 40, text.frameCount)
    }

    @Test
    fun eachCauseKeepsOnlyTenFrames() {
        val printed = capture(
            Log.ERROR,
            "engine died",
            throwable(frames = 5, cause = throwable(frames = 80))
        )

        val text = printed.single()
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: boom"))
        assertEquals("a nested cause must keep at most 10 frames", 10, text.causedByFrameCount)
    }

    @Test
    fun shortStackTracesAreNotRewritten() {
        val text = capture(Log.ERROR, "boom here", throwable(frames = 3)).single()

        assertTrue(text.startsWith("boom here"))
        assertEquals(3, text.frameCount)
        assertTrue("short traces must not gain a truncation marker", !text.contains("truncated"))
    }

    @Test
    fun belowInfoIsStillDropped() {
        assertTrue(capture(Log.DEBUG, "verbose detail", null).isEmpty())
        assertTrue(capture(Log.VERBOSE, "verbose detail", null).isEmpty())
        assertEquals(1, capture(Log.INFO, "kept", null).size)
    }
}
