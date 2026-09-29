/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsSearchEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsSearchIndex
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsSearchPreference
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/**
 * 设置首页。
 *
 * 结构：顶部常驻搜索框 + 两个分类（日常调整 / 按需进入），共 6 个入口。
 * 分组理由见 [SettingsGroupSpecs]。
 */
class MainFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    /** 常驻列表首项的搜索输入框。 */
    private lateinit var searchPreference: SettingsSearchPreference

    /**
     * 常规条目（含分类本身与其全部子项）。
     *
     * 搜索时逐条 `isVisible = false`，而**不是**增删 Preference：增删会触发整份
     * 列表重新绑定，正在输入的 EditText 有丢焦点、软键盘收起的风险。逐条改
     * 可见性则不改变列表结构，输入框的 ViewHolder 原样保留。
     *
     * 之所以连分类和子项一起收，是因为不依赖「隐藏 PreferenceGroup 会自动隐藏
     * 其子项」这一未在文档中承诺的行为。
     */
    private val normalPreferences = mutableListOf<Preference>()

    /** 有查询时显示的结果分类。 */
    private lateinit var resultCategory: PreferenceCategory

    /** 静态目录缓存，避免每敲一个字重建约 90 条 data class。 */
    private var searchEntries: List<SettingsSearchEntry> = emptyList()

    /** 搜索状态下的返回键拦截，见 [setupSearchBackHandling]。 */
    private var searchBackCallback: OnBackPressedCallback? = null

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        // AboutMenuProvider is tied to viewLifecycleOwner, so the about menu items
        // are automatically shown when this Fragment is visible and removed when it's not
        requireActivity().addMenuProvider(
            AboutMenuProvider(), viewLifecycleOwner, Lifecycle.State.STARTED
        )
        setupSearchBackHandling()
    }

    /**
     * 有搜索结果时，返回键先「退出搜索」而不是退出应用。
     *
     * 为什么需要：搜索结果**不是独立页面**，只是本页的另一视图状态（靠 `isVisible`
     * 逐条切换，见 [onSearchQueryChanged]）。因此导航返回栈里 `SettingsRoute.Index`
     * 之上空无一物，返回键会一路走到兜底分支——左上角箭头走 `moveTaskToBack(false)`
     * （看起来像退出），系统返回键走 Activity 默认的 `finish()`。
     *
     * ⚠️ **为什么不用 `addCallback(viewLifecycleOwner, ...)` 就了事**：那个重载会按
     * owner 的生命周期自动启停（进入 STARTED 即启用），而我们要的启用条件是**更窄**的
     * 「本页在前台 **且** 确有查询」。两者一旦不等价就会出问题：无查询时
     * `hasEnabledCallbacks()` 也会为真，`MainActivity` 的工具栏监听因此改走
     * `onBackPressed()`，把原本的「最小化」变成 `finish()`——一个用户没预料到的行为变化。
     *
     * 所以这里**显式**控制 enabled，只在 [syncSearchBackCallback] 一处收口：
     * 「RESUMED 且 hasQuery」。其余时刻回调保持 disabled，原有返回语义完整保留。
     */
    private fun setupSearchBackHandling() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                // 清空输入框 → textWatcher → onQueryChanged → 复原常规列表。
                searchPreference.clearQuery()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        searchBackCallback = callback
    }

    /** 回调只在「本页在前台且确有查询」时启用，见 [setupSearchBackHandling]。 */
    private fun syncSearchBackCallback() {
        searchBackCallback?.isEnabled = isResumed && searchPreference.hasQuery
    }

    override fun onResume() {
        super.onResume()
        syncSearchBackCallback()
    }

    override fun onPause() {
        // 离开本页（进子页面或退到后台）立刻交还返回键。
        searchBackCallback?.isEnabled = false
        super.onPause()
    }

    private inner class AboutMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            }
            menu.item(R.string.developer) {
                navigateWithAnim(SettingsRoute.Developer)
            }
            menu.item(R.string.about) {
                navigateWithAnim(SettingsRoute.About)
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute,
        @StringRes summary: Int? = null
    ) {
        addPreference(title, summary, icon) {
            navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val rimeSettingsTitle = getString(R.string.rime_settings)
        searchEntries = SettingsSearchIndex.entries(requireContext())

        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // 搜索框常驻第 0 项
            searchPreference = SettingsSearchPreference(context).apply {
                onQueryChanged = ::onSearchQueryChanged
            }
            addPreference(searchPreference)

            addCategory(getString(R.string.settings_home_category_daily)) {
                addDestinationPreference(
                    R.string.settings_group_input,
                    R.drawable.ic_status_rime,
                    SettingsRoute.SettingsGroup(SettingsGroupSpecs.ID_INPUT),
                    R.string.settings_group_input_summary
                )
                // 键盘直接复用既有的「虚拟键盘」页——那边已是五个子分组的列表，
                // 再套一层分组页只会多一次点击、多一个重复入口。
                addDestinationPreference(
                    R.string.settings_group_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard,
                    R.string.settings_group_keyboard_summary
                )
                addDestinationPreference(
                    R.string.settings_group_appearance,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.SettingsGroup(SettingsGroupSpecs.ID_APPEARANCE),
                    R.string.settings_group_appearance_summary
                )
                addDestinationPreference(
                    R.string.settings_group_convenience,
                    R.drawable.ic_clipboard,
                    SettingsRoute.SettingsGroup(SettingsGroupSpecs.ID_CONVENIENCE),
                    R.string.settings_group_convenience_summary
                )
            }

            addCategory(getString(R.string.settings_home_category_more)) {
                // 数据与备份从「高级」拆出：导入导出是用户主动执行的独立任务，
                // 与「兼容性开关」这种一次性配置混在一页里互相干扰。
                addDestinationPreference(
                    R.string.settings_group_data,
                    R.drawable.ic_baseline_settings_backup_restore_24,
                    SettingsRoute.DataBackup,
                    R.string.settings_group_data_summary
                )
                addDestinationPreference(
                    R.string.settings_group_advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced,
                    R.string.settings_group_advanced_summary
                )
            }

            // 结果分类：仅在有查询时可见，条目在每次查询时重建。
            resultCategory = PreferenceCategory(context).apply {
                isVisible = false
                isIconSpaceReserved = false
            }
            addPreference(resultCategory)

            collectNormalPreferences(this)
        }
    }

    /** 递归收集常规条目（跳过搜索框与结果分类）。 */
    private fun collectNormalPreferences(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref === searchPreference || pref === resultCategory) continue
            normalPreferences += pref
            if (pref is PreferenceGroup) collectNormalPreferences(pref)
        }
    }

    /**
     * 查询变化：有查询则只显示结果分类，无查询则复原。
     */
    private fun onSearchQueryChanged(query: String) {
        if (!::resultCategory.isInitialized) return
        val ctx = requireContext()
        val searching = query.isNotBlank()

        // 有查询才让返回键拦截生效，见 [setupSearchBackHandling]。
        syncSearchBackCallback()

        normalPreferences.forEach { it.isVisible = !searching }
        resultCategory.isVisible = searching
        resultCategory.removeAll()

        if (!searching) return

        val results = SettingsSearchIndex.filter(ctx, searchEntries, query)
        resultCategory.title = if (results.isEmpty()) {
            ctx.getString(R.string.settings_search_no_result)
        } else {
            ctx.getString(R.string.settings_search_result_count, results.size)
        }
        results.forEachIndexed { index, entry ->
            resultCategory.addPreference(buildResultPreference(ctx, index, entry))
        }
    }

    /**
     * 结果行：标题＝设置项名，摘要＝它所在的层级路径。
     *
     * key 用下标生成（同一设置项可能因别名命中多次），`isPersistent = false`
     * 避免把临时结果写进 SharedPreferences。
     */
    private fun buildResultPreference(
        ctx: Context,
        index: Int,
        entry: SettingsSearchEntry
    ): Preference = Preference(ctx).apply {
        key = "settings_search_result_$index"
        isPersistent = false
        isSingleLineTitle = false
        isIconSpaceReserved = false
        setTitle(entry.title(ctx))
        setSummary(entry.path(ctx))
        setOnPreferenceClickListener {
            launchEntry(entry)
            true
        }
    }

    /**
     * 跳到目标设置页。
     *
     * 查询保留，返回时仍停在结果列表，便于继续查看其它结果。
     *
     * 若条目带 [SettingsSearchEntry.preferenceKey]，先把键交给 [MainViewModel]，
     * 目标页建好列表后会自行滚到该项（见 `PreferenceScrollHelper`）。
     * **只对路由型目标这样做**：Activity 型目标（图标主题、字体设定等）不是
     * PreferenceFragment，没人会消费这个键，记下来只会污染下一次跳转。
     */
    private fun launchEntry(entry: SettingsSearchEntry) {
        entry.activityClass?.let {
            startActivity(Intent(requireContext(), it))
            return
        }
        entry.route?.let { route ->
            entry.preferenceKey?.let { viewModel.requestPreferenceScroll(it) }
            navigateWithAnim(route)
        }
    }
}
