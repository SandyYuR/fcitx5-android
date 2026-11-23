/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import timber.log.Timber

/**
 * Domain tags for structured logging.
 *
 * These tags group logs by subsystem, making it easier to filter and diagnose issues.
 * Prefer using these tags through [Logx] rather than raw string tags.
 */
enum class LogTag(val tag: String) {
    RimeInputQueue("RimeInputQueue"),
    RimeLifecycle("RimeLifecycle"),
    RimeInputTiming("RimeInputTiming"),
    RimeInputMetrics("RimeInputMetrics"),
    RimeAssetPrewarm("RimeAssetPrewarm"),
    ColdStart("FcitxColdStart"),
    Input("Input"),
    Keyboard("Keyboard"),
    Layout("Layout"),
    Theme("Theme"),
    Clipboard("Clipboard"),
    Voice("FcitxVoiceInput"),
    VoiceKbd("FcitxVoiceKbd"),
    AI("AI"),
    Sync("Sync"),
    Stats("Stats"),
    Settings("Settings"),
    Update("Update"),
    Backup("Backup"),
    Boot("Boot"),
}

/**
 * Unified logging facade with zero-cost debug/verbose guards.
 *
 * ## Level semantics
 *
 * - **E (Error)**: Function failed, user-visible impact. Always include a [Throwable].
 *   Examples: deployment failed, import failed, engine start failed.
 *
 * - **W (Warning)**: Exception occurred but recovered or degraded gracefully. Include reason.
 *   Examples: stale key rejected (`reason=stale`), config fell back to default.
 *
 * - **I (Info)**: Low-frequency lifecycle and state changes.
 *   Examples: input binding established, cold-start stage timings, deployment completed.
 *
 * - **D (Debug)**: Troubleshooting details, can be frequent.
 *   Examples: single key processing, layout computation.
 *
 * - **V (Verbose)**: Very dense tracing.
 *   Examples: per-frame, per-sample.
 *
 * ## Message format
 *
 * Use `event_name key=value key=value` style for structured parsing.
 * Example: `key_rejected generation=3 reason=stale`
 *
 * ## Hot-path usage
 *
 * [d] and [v] take a lambda that is only evaluated when `timberDebugEnabled` is true.
 * On input/rendering hot paths, prefer these over building the message string unconditionally.
 */
object Logx {
    /**
     * Debug log. Only evaluated when verbose logging is enabled.
     * Use on troubleshooting details that may be frequent.
     */
    inline fun d(tag: LogTag, msg: () -> String) {
        if (timberDebugEnabled) {
            val message = msg()
            Timber.tag(tag.tag).d(message)
            LogSink.log('D', tag.tag, message)
        }
    }

    /**
     * Debug log with exception. Only logged when verbose logging is enabled.
     */
    fun d(tag: LogTag, msg: String, t: Throwable? = null) {
        if (timberDebugEnabled) {
            Timber.tag(tag.tag).d(t, msg)
            LogSink.log('D', tag.tag, msg, t)
        }
    }

    /**
     * Verbose log. Only evaluated when verbose logging is enabled.
     * Use for very dense tracing (per-frame, per-sample).
     */
    inline fun v(tag: LogTag, msg: () -> String) {
        if (timberDebugEnabled) {
            val message = msg()
            Timber.tag(tag.tag).v(message)
            LogSink.log('V', tag.tag, message)
        }
    }

    /**
     * Info log. Use for low-frequency lifecycle and state changes.
     */
    fun i(tag: LogTag, msg: String) {
        Timber.tag(tag.tag).i(msg)
        LogSink.log('I', tag.tag, msg)
    }

    /**
     * Warning log. Use for exceptions that were recovered or degraded.
     * Always include reason in the message.
     */
    fun w(tag: LogTag, msg: String, t: Throwable? = null) {
        Timber.tag(tag.tag).w(t, msg)
        LogSink.log('W', tag.tag, msg, t)
    }

    /**
     * Error log. Use for function failures with user-visible impact.
     * Always include the [Throwable].
     */
    fun e(tag: LogTag, msg: String, t: Throwable? = null) {
        Timber.tag(tag.tag).e(t, msg)
        LogSink.log('E', tag.tag, msg, t)
    }
}
