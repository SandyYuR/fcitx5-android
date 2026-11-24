/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.util.Log
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber

/**
 * logcat tag 必须是纯业务 tag（例如 RimeLifecycle）。
 *
 * logcat 的 `-s RimeLifecycle:I` 是精确 tag 匹配，任何前缀（曾经的 `[thread] RimeLifecycle`）
 * 都会失配；而 tools/foxy-perf 正是靠这个过滤，再用 `RimeLifecycle: bind ... ready=true`
 * 正则定位事件。线程身份由 threadtime 的 TID 列承载，不需要塞进 tag。
 */
private fun businessTagOrThreadName(tag: String?): String =
    tag?.takeIf { it.isNotEmpty() } ?: "[${Thread.currentThread().name}]"

class VerboseTree : Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, businessTagOrThreadName(tag), message, t)
    }
}

class ConciseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return

        val effectiveTag = businessTagOrThreadName(tag)

        // Timber 的 prepareLog() 已经在 message 末尾拼好堆栈（有 t 时 message += "\n" + 堆栈，
        // 无 message 只有 t 时 message 就是堆栈），且用的是自己那份不隐藏
        // UnknownHostException 的 getStackTraceString。所以这里不能再追加一次
        // Log.getStackTraceString(t)，否则同一份堆栈会打印两遍，而且限长只作用于复制出来的
        // 那一份，真正打印的 message 仍然无界。
        //
        // 单行日志（热路径上的绝大多数）不可能是堆栈，跳过切分以免每次分配行列表。
        val printable = if (t != null || message.indexOf('\n') >= 0) {
            truncateStackTrace(message)
        } else {
            message
        }
        Log.println(priority, effectiveTag, printable)
    }

    /**
     * Truncate stack trace to keep output manageable while preserving diagnostic value.
     * Keeps up to 40 frames for the main exception and 10 frames for each cause.
     */
    private fun truncateStackTrace(stackTrace: String): String {
        val lines = stackTrace.lines()
        if (lines.size <= 60) return stackTrace

        val result = mutableListOf<String>()
        var currentFrameCount = 0
        var maxFrames = 40  // main exception gets more frames

        for (line in lines) {
            when {
                line.startsWith("Caused by:") || line.startsWith("Suppressed:") -> {
                    result.add(line)
                    currentFrameCount = 0
                    maxFrames = 10  // subsequent causes get fewer frames
                }
                line.trim().startsWith("at ") -> {
                    if (currentFrameCount < maxFrames) {
                        result.add(line)
                        currentFrameCount++
                    } else if (currentFrameCount == maxFrames) {
                        result.add("\t... (frames truncated)")
                        currentFrameCount++
                    }
                }
                line.trim().startsWith("... ") && line.contains("more") -> {
                    // Preserve "... N more" summary lines
                    result.add(line)
                }
                else -> {
                    // Exception message lines (first line of each exception)
                    result.add(line)
                }
            }
        }

        return result.joinToString("\n")
    }
}

/**
 * Whether a DEBUG log would actually be printed right now.
 *
 * `setupForest` plants [VerboseTree] in debug builds or when the verbose preference is on, and
 * [ConciseTree] drops everything below INFO otherwise. Dropping happens inside `log()`, so the
 * message string has already been built by then — and on the input hot path that string is the
 * expensive part: every native event built `"Handling $event"`, and a candidate event's
 * `toString()` joins its candidates.
 *
 * Callers use this to skip building the string in the first place. Never call it in a loop
 * condition that already has a cheaper guard, and keep it out of per-pixel/per-sample paths: it is
 * a volatile read, which is far cheaper than the formatting it replaces.
 *
 * Set by [setupForest], so it stays correct when the user flips the verbose preference
 * (DeveloperFragment re-plants the forest).
 */
@Volatile
var timberDebugEnabled: Boolean = BuildConfig.DEBUG
    private set

fun Timber.Forest.setupForest(verbose: Boolean) {
    if (treeCount > 0) {
        uprootAll()
    }
    val debugEnabled = BuildConfig.DEBUG || verbose
    timberDebugEnabled = debugEnabled
    plant(if (debugEnabled) VerboseTree() else ConciseTree())
}
