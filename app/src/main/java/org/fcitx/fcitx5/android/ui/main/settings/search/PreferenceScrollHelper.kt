/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.search

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.fcitx.fcitx5.android.ui.main.MainViewModel

/**
 * 设置搜索跳转后的「滚动定位」。
 *
 * 搜索结果只跳到目标页，用户还得自己在长列表里找那一项；这里让它直接滚到目标项。
 *
 * 做法：搜索点击时把偏好键存进 [MainViewModel]，目标页建好自己的 PreferenceScreen
 * 之后调用本函数消费它。选这个路径而不是 Nav 参数，是因为 Nav 参数要求目标页在
 * 导航时就已知键，而设置页是异步构建列表的，参数容易丢时机。
 *
 * **不必自己等布局**：[PreferenceFragmentCompat.scrollToPreference] 内部在目标项
 * 尚未进入 adapter 时会注册 `ScrollToPreferenceObserver` 等待，且在列表未就绪时
 * 把动作存进 `mSelectPreferenceRunnable` 于 `bindPreferences()` 后执行
 * （它内部实际调 `RecyclerView.scrollToPosition`）。
 *
 * ⚠️ **调用时机有两类，别混**：普通页在 `onViewCreated`（super 之后）调用即可；
 * 但引擎配置页（`FcitxPreferenceFragment`）的列表是**异步**建成的（要等引擎返回
 * `desc` 树），必须在赋值 `preferenceScreen` **之后**再调，否则 `findPreference`
 * 必然返回 null。
 *
 * 注：曾尝试给目标行加一次性高亮（`foreground` 叠色淡出），实测在真机上不可见，
 * 已按用户要求移除，只保留滚动定位。
 */
fun PreferenceFragmentCompat.scrollToPendingPreference(viewModel: MainViewModel) {
    val key = viewModel.peekPendingPreferenceScrollKey() ?: return

    // 本页没有这一项：**保留键**，交给真正拥有它的那一页消费。
    // （搜索结果里同一标题可能存在于多页，键只对其中一页有效。）
    val preference = findPreference<Preference>(key) ?: return

    if (!preference.isVisible) {
        // 该项当前被隐藏（例如「首选语音输入」要等语音按钮打开才出现）。
        // 滚动无处可去，但键已算处理完毕——留着会让下次进入本页莫名滚动。
        viewModel.consumePendingPreferenceScrollKey(key)
        return
    }

    // 放到下一帧再滚：调用点通常刚赋完 `preferenceScreen`，列表还没测量，
    // 此时直接调用虽然也能被内部 Observer 兜住，但等一帧更稳。
    listView.post {
        scrollToPreference(preference)
        viewModel.consumePendingPreferenceScrollKey(key)
    }
}
