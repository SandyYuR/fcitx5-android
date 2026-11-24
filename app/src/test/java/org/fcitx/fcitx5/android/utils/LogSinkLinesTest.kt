/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.ui.main.log.logLevelOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 持久日志的每一行都必须自带 threadtime 前缀（时间、PID、TID、级别、tag），包括异常堆栈的续行。
 *
 * 续行一旦没有前缀，读取端 [logLevelOf] 就解析不出级别：级别过滤会删掉事件头却留下全部栈帧
 * （无级别行恒通过级别过滤），"rime" 内容过滤又反过来只留事件头、把诊断帧全删——同一个异常
 * 被两种过滤切成两半。前缀里的数字还必须固定 Locale.US，否则 ar-EG 这类用本地数字的 locale
 * 会让整批日志解析不出级别。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogSinkLinesTest {

    private companion object {
        const val TAG = "RimeLifecycle"
        const val TIMESTAMP = 1_700_000_000_000L
        const val TID = 42

        /** 严格匹配 logcat threadtime 的 ASCII 前缀，末尾要求 "RimeLifecycle: " */
        val threadtime = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} +\d+ +\d+ [VDIWEF] RimeLifecycle: """)
    }

    private fun lines(level: Char, message: String, throwable: Throwable? = null): List<String> =
        LogSink.formatLogLines(level, TAG, message, throwable, TIMESTAMP, TID)

    private fun throwable(frames: Int): Throwable = IllegalStateException("boom").apply {
        stackTrace = Array(frames) { StackTraceElement("com.example.Cls$it", "method$it", "Cls.kt", it + 1) }
    }

    @Test
    fun everyLineOfAThrowableCarriesTheEventLevelAndTag() {
        val rendered = lines('E', "engine died", throwable(frames = 5))

        // 事件头 + 异常消息行 + 5 个栈帧
        assertEquals(7, rendered.size)
        for (line in rendered) {
            assertTrue("continuation line lost its threadtime prefix: $line", threadtime.containsMatchIn(line))
            assertEquals('E', logLevelOf(line))
        }
        assertTrue(rendered.first().endsWith(": engine died"))
        assertTrue(rendered[1].contains("java.lang.IllegalStateException: boom"))
        assertTrue(rendered.last().contains("method4"))
    }

    @Test
    fun everyPersistedLineIsEitherKeptOrDroppedTogetherByLevel() {
        // 级别过滤按行生效，所以整条异常的每一行都必须是 E，不能有解析不出级别的裸行
        val levels = lines('W', "recovered", throwable(frames = 3)).map { logLevelOf(it) }
        assertTrue(levels.isNotEmpty())
        assertTrue("unparsed persisted lines bypass level filtering: $levels", levels.all { it == 'W' })
    }

    @Test
    fun aSingleLineMessageStaysOneLineWithoutTrailingNewline() {
        val rendered = lines('I', "startup phase=native duration_ms=11")
        assertEquals(1, rendered.size)
        assertFalse(rendered.first().contains('\n'))
        assertEquals('I', logLevelOf(rendered.first()))
        assertTrue(rendered.first().endsWith(": startup phase=native duration_ms=11"))
    }

    @Test
    fun aMessageWithEmbeddedNewlinesIsSplitPerLine() {
        val rendered = lines('D', "first\nsecond\nthird")
        assertEquals(3, rendered.size)
        assertTrue(rendered.all { logLevelOf(it) == 'D' })
        assertTrue(rendered[1].endsWith(": second"))
    }

    @Test
    fun aThrowableWithoutAMessageStillStartsWithTheStack() {
        val rendered = lines('E', "", throwable(frames = 2))
        assertEquals(3, rendered.size)
        assertTrue(rendered.first().contains("java.lang.IllegalStateException: boom"))
        assertTrue(rendered.all { logLevelOf(it) == 'E' })
    }

    @Test
    fun nonAsciiDigitLocalesCannotHideTheLevel() {
        val previous = Locale.getDefault()
        try {
            // ar-EG 的默认数字是 ٠١٢٣：不固定 Locale.US 时整行会变成非 ASCII 数字，
            // 读取端 logLevelRegex 失配，日志按 Info 着色并绕过级别过滤。
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val rendered = lines('W', "Unexpected CPU variant", throwable(frames = 2))
            for (line in rendered) {
                assertTrue("non-ASCII digits leaked into the prefix: $line", threadtime.containsMatchIn(line))
                assertEquals('W', logLevelOf(line))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
