/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 覆盖候选 ViewHolder 全局下标的取用规则。
 *
 * 报告 P1-6 第 3 条：把 Paging diff 从“恒 false”改成内容比较后，窗口起点变化时
 * DiffUtil 可能认为“内容没变”而不 rebind，缓存的 `holder.idx` 会停在旧起点上，
 * 点击/长按就会 `select()` 到错误候选。因此取用时刻必须用 RecyclerView 的位置
 * 重新计算，缓存值只在位置未知时兜底。
 */
class CandidateIndexResolverTest {

    private companion object {
        /** `RecyclerView.NO_POSITION`。 */
        const val NO_POSITION = -1
    }

    @Test
    fun positionPlusOffsetGivesGlobalIndex() {
        assertEquals(0, CandidateIndexResolver.resolve(0, 0, fallbackIndex = 99))
        assertEquals(48, CandidateIndexResolver.resolve(0, 48, fallbackIndex = 0))
        assertEquals(58, CandidateIndexResolver.resolve(10, 48, fallbackIndex = 10))
    }

    @Test
    fun staleCachedIndexIsOverriddenByCurrentPosition() {
        // 起点从 0 变到 48：同一个 ViewHolder 的缓存 idx 还是 5（旧值），
        // 但它的真实全局下标应是 position(5) + offset(48) = 53。
        assertEquals(53, CandidateIndexResolver.resolve(bindingPosition = 5, offset = 48, fallbackIndex = 5))
    }

    @Test
    fun unknownPositionFallsBackToCachedIndex() {
        // 布局/滚动中位置未知时不能凭空算出 0，退回绑定时缓存的值。
        assertEquals(7, CandidateIndexResolver.resolve(NO_POSITION, 48, fallbackIndex = 7))
        assertEquals(-1, CandidateIndexResolver.resolve(NO_POSITION, 48, fallbackIndex = -1))
    }

    @Test
    fun duplicateCandidatesStillResolveToTheirOwnGlobalIndex() {
        // 文本相同的重复候选：身份相同（label+text 相同）时 DiffUtil 认为没变，
        // 下标只能靠 position + offset 区分，不能靠缓存的 idx。
        assertEquals(12, CandidateIndexResolver.resolve(bindingPosition = 12, offset = 0, fallbackIndex = 0))
        assertEquals(60, CandidateIndexResolver.resolve(bindingPosition = 12, offset = 48, fallbackIndex = 12))
    }
}