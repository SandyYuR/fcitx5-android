/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

/**
 * 候选 ViewHolder 全局下标（`holder.idx`）的权威解析。
 *
 * 横向候选栏与展开候选面板都拿 `holder.idx` 去 `select()` / 弹候选动作菜单，而 `idx` 里
 * 混合了两部分信息：**位置**（`onBindViewHolder` 的 position，由 RecyclerView 维护）
 * 与 **窗口起点**（adapter 的 `indexOffset` / `offset`，由代码维护）。
 *
 * 只把结果缓存进 holder 是不安全的：DiffUtil 判定“内容没变”时 RecyclerView 只挪动
 * ViewHolder 而不重新绑定（重复候选、窗口整体平移后文本恰好相同都会命中），缓存值就会
 * 停在旧起点上，点击/长按于是选到别的候选。因此取用时刻应重新用
 * `RecyclerView.ViewHolder#getBindingAdapterPosition` 加当前起点计算；
 * `fallbackIndex`（缓存值）只在位置未知（`NO_POSITION`）时兜底。
 *
 * 纯函数、不含 Android 类型，便于单元测试。
 */
object CandidateIndexResolver {

    /**
     * @param bindingPosition ViewHolder 当前在 adapter 中的位置，未知时为负数
     * （`RecyclerView.NO_POSITION`）。
     * @param offset adapter 当前的全局窗口起点。
     * @param fallbackIndex 位置未知时使用的缓存下标（通常是 `holder.idx`）。
     */
    fun resolve(bindingPosition: Int, offset: Int, fallbackIndex: Int): Int =
        if (bindingPosition < 0) fallbackIndex else bindingPosition + offset
}