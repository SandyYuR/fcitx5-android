/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import org.fcitx.fcitx5.android.core.CandidateWord

/**
 * 展开候选面板（Paging）的 DiffUtil 判定。
 *
 * - 身份 [areItemsTheSame]：标签 + 正文 + 注释 + 注释分隔方式。引擎给的标签（`1 `/`2 ` 之类）
 *   是候选编号，参与身份才能让编号不同的候选各占一个 ViewHolder，不至于因为正文恰好
 *   相同而互相顶替。
 * - 内容 [areContentsTheSame]：完整相等，凡是会被 `CandidateItemUi` 渲染出来的字段都算，
 *   所以只换 `PagingSource`（offset 变了但文本没变）时不会触发无谓的 rebind。
 *
 * 旧实现两个回调恒为 `false`（“每次都全量 rebind”），一次 offset 刷新就会把所有可见项
 * 重新绑定并重新布局。
 *
 * 注意：判定相同意味着 RecyclerView 可能只挪动 ViewHolder 而不重新绑定，因此全局下标
 * 必须在取用时刻重新计算（见 `CandidateViewHolder.currentIndex`），不能只依赖
 * `onBindViewHolder` 写入的缓存值。
 *
 * 纯函数、不含 Android 类型，便于单元测试。
 */
object PagingCandidateDiff {

    fun areItemsTheSame(oldItem: CandidateWord, newItem: CandidateWord): Boolean =
        oldItem.label == newItem.label &&
            oldItem.text == newItem.text &&
            oldItem.spaceBetweenComment == newItem.spaceBetweenComment

    fun areContentsTheSame(oldItem: CandidateWord, newItem: CandidateWord): Boolean =
        oldItem == newItem
}