/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.Keep
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

class ThemeFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var previewUi: KeyboardPreviewUi

    private lateinit var tabLayout: TabLayout

    private lateinit var viewPager: ViewPager2

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            previewUi.setTheme(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = with(requireContext()) {
        previewUi = KeyboardPreviewUi(this, ThemeManager.activeTheme)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        val preview = previewUi.root.apply {
            scaleX = 0.5f
            scaleY = 0.5f
            outlineProvider = ViewOutlineProvider.BOUNDS
            elevation = dp(4f)
            tag = "theme_preview_capture"
        }

        tabLayout = TabLayout(this)

        viewPager = ViewPager2(this).apply {
            adapter = object : FragmentStateAdapter(this@ThemeFragment) {
                override fun getItemCount() = 2
                override fun createFragment(position: Int): Fragment = when (position) {
                    0 -> ThemeListFragment()
                    else -> ThemeSettingsFragment()
                }
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.theme
                    else -> R.string.configure
                }
            )
        }.attach()

        val previewWrapper = constraintLayout {
            add(preview, lParams(wrapContent, wrapContent) {
                topOfParent(dp(-52))
                startOfParent()
                endOfParent()
            })
            add(tabLayout, lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            })
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }

        constraintLayout {
            add(previewWrapper, lParams(height = wrapContent) {
                topOfParent()
                startOfParent()
                endOfParent()
            })
            add(viewPager, lParams {
                below(previewWrapper)
                startOfParent()
                endOfParent()
                bottomOfParent()
            })
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        previewUi.setTheme(ThemeManager.activeTheme)
        maybeOpenConfigTab()
    }

    /**
     * 搜索跳转过来的目标项若属于「配置」tab，先把 ViewPager 切过去。
     *
     * 必要性：两个 tab 由 [FragmentStateAdapter] 惰性创建，默认只建 tab 0。
     * 若不切换，`ThemeSettingsFragment` 根本不会被创建，也就没人去消费
     * [MainViewModel] 里的滚动键——用户会停在主题列表页，看不到跳转效果。
     *
     * 判断依据是「该键是否登记在 ThemePrefs」而不是硬编码键名，
     * 这样以后往 ThemePrefs 加项不必同步改这里。
     */
    private fun maybeOpenConfigTab() {
        val key = viewModel.peekPendingPreferenceScrollKey() ?: return
        if (ThemeManager.prefs.managedPreferences.containsKey(key)) {
            viewPager.setCurrentItem(CONFIG_TAB_INDEX, false)
        }
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
    }

    private companion object {
        const val CONFIG_TAB_INDEX = 1
    }

}
