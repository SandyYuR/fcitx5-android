/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

/**
 * 输入引擎后端：统一「引擎实例」需要向 daemon 暴露的最小面。
 *
 * 之所以要有这一层：[Fcitx]（走 fcitx5）与 [org.fxrime.direct.DirectRimeEngine]
 * （Kotlin/JNI 直连 librime）是同一个角色的两个实现。[FcitxDaemon] 只需要
 * 生命周期 + 启停 + [FcitxAPI]，不关心底下是不是 fcitx5。
 *
 * 这样保留两份实现可以运行时切换，也满足报告 §6 阶段 B「出错能切回」的要求——
 * 迁移不是一次性的删除动作，而是先把入口抽象出来，再换实现。
 */
interface InputEngineBackend : FcitxAPI, FcitxLifecycleOwner {
    fun start()
    fun stop()
}
