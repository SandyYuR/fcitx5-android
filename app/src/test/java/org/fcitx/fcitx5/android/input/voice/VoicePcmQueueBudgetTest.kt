/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音 PCM 队列的容量推导与运行时统计（纯函数，无 Android 依赖）。
 *
 * 这些断言固定了两件事：容量按"音频时长"推导（而不是包数），以及过载判定依赖的深度/
 * 最旧包年龄统计在真实包节奏下是对的。
 */
class VoicePcmQueueBudgetTest {

    private val sampleRate = 16000
    private val bitsPerSample = 16
    private val channels = 1

    private fun packetBytes() = VoicePcmQueueBudget.packetBytes(sampleRate, bitsPerSample, channels)

    @Test
    fun capturePacketIsAlwaysOneHundredMilliseconds() {
        // 采集层按 sampleRate / 10 采样切包 ⇒ 与采样率无关，恒为 100ms。
        assertEquals(100L, VoicePcmQueueBudget.PACKET_DURATION_MS)
        assertEquals(3200, packetBytes())
        // 48kHz 时包变大，但时长不变：容量包数只由时长决定，字节占用随格式变化。
        assertEquals(9600, VoicePcmQueueBudget.packetBytes(48000, bitsPerSample, channels))
    }

    @Test
    fun threeSecondBudgetBecomesThirtyOnePackets() {
        // 3000ms / 100ms = 30 个间隔，加上窗口首尾两个端点包 = 31 包。
        assertEquals(31, VoicePcmQueueBudget.capacityPackets(3000L))
        // 不足一个包长也按一个包算：ceil(2048/100) = 21，再加端点包 = 22。
        assertEquals(22, VoicePcmQueueBudget.capacityPackets(2048L))
    }

    @Test
    fun capacityNeverCollapsesToZero() {
        // 容量 0 的 Channel 会让 trySend 永远失败，把每次入队都误判成过载。
        assertEquals(1, VoicePcmQueueBudget.capacityPackets(0L))
        assertEquals(2, VoicePcmQueueBudget.capacityPackets(1L))
        // 0 或负的包长也不能除零。
        assertTrue(VoicePcmQueueBudget.capacityPackets(3000L, 0L) >= 1)
    }

    @Test
    fun bufferedBytesDerivesTheMemoryUpperBound() {
        // 31 包 × 3200 B = 99200 B ≈ 97KB（改前 UNLIMITED 会按 32KB/s 线性增长）。
        assertEquals(31 * 3200, VoicePcmQueueBudget.bufferedBytes(sampleRate, bitsPerSample, channels, 3000L))
    }

    @Test
    fun feedQueueCapacityFitsTheWholePreRollWindow() {
        // 预滚窗口与 feed 队列用同一个 3000ms 预算，因此回灌（最多 31 包）必然全部放得下。
        val preRoll = VoicePcmQueueBudget.capacityPackets(3000L)
        val feedQueue = VoicePcmQueueBudget.capacityPackets(3000L)
        assertEquals(31, preRoll)
        assertTrue(feedQueue >= preRoll)
    }

    @Test
    fun depthAndOldestPacketAgeTrackTheQueue() {
        val metrics = VoicePcmQueueMetrics()
        metrics.onEnqueued(0L)
        assertEquals(1, metrics.maxDepth)
        assertEquals(0L, metrics.maxHeadAgeMs)

        // 第二个包入队时，队首包已经等了 100ms。
        metrics.onEnqueued(100L)
        assertEquals(2, metrics.maxDepth)
        assertEquals(100L, metrics.maxHeadAgeMs)

        metrics.onFed(5L)
        metrics.onFed(5L)
        assertEquals(2, metrics.calls)
        assertEquals(5L, metrics.maxFeedMs)

        // 深度归零后重新锚定队首：新包入队时年龄从 0 重新算，不会把上一段积压算进来。
        metrics.onEnqueued(9000L)
        assertEquals(100L, metrics.maxHeadAgeMs)
        // 高水位是历史峰值，不会因为重新锚定而回落。
        assertEquals(2, metrics.maxDepth)
    }

    @Test
    fun fullWindowOfBacklogReportsTheWholeBudgetAsOldestAge() {
        val metrics = VoicePcmQueueMetrics()
        // 32 个包以 100ms 间隔到达（容量 31，第 32 个就会入队失败）。
        for (i in 0..30) metrics.onEnqueued(i * 100L)
        assertEquals(31, metrics.maxDepth)
        assertEquals(3000L, metrics.maxHeadAgeMs)
    }

    @Test
    fun skippedPacketsDoNotCountAsFeedCallsButReleaseDepth() {
        val metrics = VoicePcmQueueMetrics()
        metrics.onEnqueued(0L)
        metrics.onFedSkipped()
        assertEquals(0, metrics.calls)
        assertEquals(0L, metrics.maxFeedMs)
        // 深度已归零，再多的 skip 也不会把它压成负数。
        metrics.onFedSkipped()
        metrics.onEnqueued(100L)
        assertEquals(1, metrics.maxDepth)
    }

    @Test
    fun slowCallsAndOverloadsAreCounted() {
        val metrics = VoicePcmQueueMetrics()
        metrics.onFed(50L)
        metrics.onFed(400L)
        assertEquals(2, metrics.calls)
        assertEquals(400L, metrics.maxFeedMs)
        metrics.onSlowFeed()
        assertEquals(1, metrics.slowCalls)

        assertEquals(1, metrics.onOverflow())
        assertEquals(2, metrics.onOverflow())
        assertEquals(2, metrics.overloads)
        assertTrue(metrics.summary().contains("overloads=2"))
    }

    @Test
    fun resetClearsThePreviousSession() {
        val metrics = VoicePcmQueueMetrics()
        metrics.onEnqueued(0L)
        metrics.onEnqueued(100L)
        metrics.onFed(10L)
        metrics.onSlowFeed()
        metrics.onOverflow()

        metrics.reset()

        assertEquals(0, metrics.maxDepth)
        assertEquals(0L, metrics.maxHeadAgeMs)
        assertEquals(0, metrics.calls)
        assertEquals(0L, metrics.maxFeedMs)
        assertEquals(0, metrics.slowCalls)
        assertEquals(0, metrics.overloads)
        /** 单行汇总里不出现上一会话的过载计数。 */
        assertFalse(metrics.summary().contains("overloads=1"))
    }
}