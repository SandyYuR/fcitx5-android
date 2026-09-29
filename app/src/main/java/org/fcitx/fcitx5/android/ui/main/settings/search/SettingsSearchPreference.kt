/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fcitx.fcitx5.android.R

/**
 * 设置搜索输入框（自定义 Preference）。
 *
 * 放在设置首页列表的第 0 项，占一整行。
 *
 * 两个必须遵守的点：
 * 1. **onBindViewHolder 里不能回写文本**。列表在输入过程中会因刷新而重新绑定，
 *    一旦把 `input.setText(query)` 写回去，光标会跳到开头、输入被打断。
 * 2. **不要在绑定阶段主动 requestFocus**，否则一进设置页就弹键盘。焦点交给用户点击。
 *
 * 参考实现：boomker/fcitx5-android 的 SettingsSearchPreference（占位延迟实例化版本）。
 * 这里不采用「占位 TextView + 点击后动态 new SearchView」的写法，因为本项目的设置首页
 * 结构简单、列表很短，直接常驻 EditText 不会带来启动开销，代码也更少一层状态。
 */
class SettingsSearchPreference(context: Context) : Preference(context) {

    /** 查询串变化回调；传空串表示清空。 */
    var onQueryChanged: ((String) -> Unit)? = null

    /** 当前输入框内容，供 Fragment 在需要时读取。 */
    var query: String = ""
        private set

    /**
     * 单一 TextWatcher 实例。
     *
     * ⚠️ 必须复用同一实例并在注册前先移除：`onBindViewHolder` 每次绑定都会被调用，
     * 若直接用 `doAfterTextChanged` 就变成「每绑定一次加一个 listener」，
     * 列表刷新几轮后一次按键会触发多次搜索。先 remove 再 add 可保证恒为一个。
     */
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            val value = s?.toString().orEmpty()
            if (value == query) return
            query = value
            onQueryChanged?.invoke(value)
        }
    }

    /** 最近一次绑定的输入框，用于点击行空白处时聚焦。 */
    private var boundInput: EditText? = null

    /**
     * 当前是否有查询串。供首页决定是否拦截返回键。
     *
     * 判据必须与 [MainFragment.onSearchQueryChanged] 的 `searching` 一致（都用 isNotBlank
     * 语义）：若这里用 `isNotEmpty`、那边用 `isNotBlank`，输入纯空格时两边会打架——
     * 列表已复原（因为 blank）但返回键仍被吞掉，用户按返回没反应。
     */
    val hasQuery: Boolean get() = query.isNotBlank()

    /**
     * 清空查询串并收起键盘（由首页在处理返回键时调用）。
     *
     * 通过改 EditText 文本来清空，而不是直接改 [query]：文本变化会走 [textWatcher]，
     * 那条路径会同步 `query` 并回调 [onQueryChanged]，界面因此自动恢复常规列表。
     * 直接改字段则只会让状态与实际输入框不一致。
     */
    fun clearQuery() {
        val input = boundInput ?: return
        if (input.text.isNotEmpty()) input.text.clear()
        // 返回键的语义是「退出当前输入状态」，所以顺带撤焦点、收键盘。
        input.clearFocus()
        val imm = input.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    init {
        key = KEY
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        layoutResource = R.layout.preference_settings_search
        // 点行内空白（输入框两侧的 padding）时聚焦输入框，省得用户必须精准点在框上。
        setOnPreferenceClickListener {
            boundInput?.requestFocus()
            true
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val input = holder.itemView.findViewById<EditText>(R.id.settings_search_input) ?: return
        boundInput = input

        // 注册监听：必须先 remove 再 add，保证恒为一个 listener（见 [textWatcher] 的说明）。
        //
        // ⚠️ 注册必须在下面那次可能的 setText **之前**完成，否则 setText 触发的变化
        // 不会走 watcher、`query` 字段就与输入框内容脱节了。
        input.removeTextChangedListener(textWatcher)
        input.addTextChangedListener(textWatcher)

        // 绑定阶段**不做无条件写回**：列表会因刷新而重新绑定本行，把 query 无条件
        // setText 回去会让光标跳到开头、正在输入的内容被打断。
        //
        // 但**文本确实缺失时**要补回：从搜索结果跳到子页面再返回时，本行的 View 是
        // 重建的（文本为空），而 `query` 仍旧；不补的话输入框显示空、列表却还是
        // 搜索状态，两边对不上。正常输入过程中两者恒等，因此这里不会触发写回。
        if (input.text.toString() != query) input.setText(query)

        // 软键盘上的「搜索」键：收起键盘即可，不需要额外动作。
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                v.clearFocus()
                true
            } else {
                false
            }
        }
    }

    companion object {
        const val KEY = "settings_search"
    }
}
