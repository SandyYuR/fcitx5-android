/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.util.Log
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber

class VerboseTree : Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, "[${Thread.currentThread().name}] $tag", message, t)
    }
}

class ConciseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return
        
        // Build effective tag: "[thread]" or "[thread] domain"
        val threadName = Thread.currentThread().name
        val effectiveTag = if (tag.isNullOrEmpty()) {
            "[$threadName]"
        } else {
            "[$threadName] $tag"
        }
        
        // Append truncated stack trace if present
        val fullMessage = if (t != null) {
            message + "\n" + truncateStackTrace(Log.getStackTraceString(t))
        } else {
            message
        }
        
        Log.println(priority, effectiveTag, fullMessage)
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
