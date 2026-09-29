/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.appContext

class MainViewModel : ViewModel() {
    private val appLabel = AppUtil.appLabel(appContext)

    enum class ButtonMode { NONE, EDIT, DELETE }

    val toolbarTitle = MutableLiveData(appLabel)

    val toolbarShadow = MutableLiveData(true)

    val toolbarButton = MutableLiveData(ButtonMode.NONE)

    val fcitx: FcitxConnection = FcitxDaemon.connect(javaClass.name)

    /**
     * 搜索跳转后需要滚动到的偏好键。
     *
     * 用 ViewModel 而不是 Nav 参数传递，原因有二：
     * 1. 目标页可能还没创建（导航是异步的），参数会丢失传递时机；
     * 2. 搜索结果点击后先存键再导航，目标页在自己的 `onViewCreated` 里取用，
     *    顺序天然正确，不需要额外的握手协议。
     *
     * 只保留一个待滚动键：用户不会同时点两条结果，后一次点击覆盖前一次即可。
     */
    private var pendingPreferenceScrollKey: String? = null

    /** 请求目标页滚动到指定偏好项。 */
    fun requestPreferenceScroll(key: String) {
        pendingPreferenceScrollKey = key
    }

    /** 读取待滚动键但不消费。 */
    fun peekPendingPreferenceScrollKey(): String? = pendingPreferenceScrollKey

    /**
     * 消费待滚动键。
     *
     * 只在键仍与 [key] 相同时清空——这样「A 页没有该项、B 页才有」的场景中，
     * A 页的失败尝试不会把 B 页该用的键清掉。
     */
    fun consumePendingPreferenceScrollKey(key: String) {
        if (pendingPreferenceScrollKey == key) pendingPreferenceScrollKey = null
    }

    fun setToolbarTitle(title: String) {
        toolbarTitle.value = title
    }

    fun enableToolbarShadow() {
        toolbarShadow.value = true
    }

    fun disableToolbarShadow() {
        toolbarShadow.value = false
    }

    fun disableToolbarEditButton() {
        toolbarButton.value = ButtonMode.NONE
    }

    override fun onCleared() {
        FcitxDaemon.disconnect(javaClass.name)
    }
}
