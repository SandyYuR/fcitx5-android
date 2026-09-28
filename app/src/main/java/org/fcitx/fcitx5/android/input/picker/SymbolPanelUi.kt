/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

/**
 * Foxy 风格的符号 / 表情 / 颜文字面板。
 *
 * 布局模型取自 Foxy 输入法（`com.fxliang.foxy` 的 `ly` 视图）：
 *
 * ```
 * ┌────────┬──────────────────────────────┐
 * │ 分组栏  │  符号网格（可纵向滚动）        │
 * │ (纵向)  │  每行 N 个                    │
 * │  最近   │                              │
 * │  标点   │                              │
 * │  ...   │                              │
 * ├────────┤                              │
 * │  ⌨  ⌫  │                              │
 * └────────┴──────────────────────────────┘
 * ```
 *
 * **面板独占整个键盘区域**，底部不再有 `ABC , 空格 . 回车` 那一整行：面板按键本身是
 * 「点一下即时上屏」，空格/回车/逗号在面板里没有意义，那行只会白白吃掉约 1/4 高度。
 * 键盘级操作只有左栏底部这两枚小键，与 Foxy 的 `ly.j` / `ly.k` 一一对应：
 *
 * - **⌨** 返回文字键盘（与 Foxy `gu.java` case 8 的 `k5("default")` 同义）；
 * - **⌫** 退格删除，长按连续删除（与 Foxy case 15 的 `wz(j30.BACKSPACE, 0)` 同义）。
 *
 * **面板之间的切换不在这里**，而是和 Foxy 一样交由**布局按键**与**宏**配置：
 * 布局编辑器里给 LayoutSwitchKey 选 Emoji / Kaomoji 目标，或在宏里用「切层」
 * 动作指向这三个面板（见 `PickerWindow.Key.layerTargetNames`）。
 *
 * 「返回键盘」在工具栏最左侧还有第二个入口（见 `IdleUi.setBackToKeyboardMode`）：
 * 主键盘工具栏原样保留、只换最左图标。
 *
 * 与 Foxy 的差异（均为有意为之）：
 * - Foxy 的 `ly.c()` 一次性把整份 catalog（符号最多 4773 条）全部 `new` 成视图；
 *   这里改用 [RecyclerView] 复用视图，避免上千个子 View 常驻内存。
 * - 网格项复用本项目的 [TextKeyView]，因此自动继承主题圆角、边框、按压高亮与字体设置。
 * - 保留长按弹出（emoji 肤色）能力，Foxy 面板没有该交互。
 *
 * 列数由 [columns] 决定：符号/表情为 6 列，颜文字为 1 列（条目很长，见 Foxy 的
 * `multiLine` 语义）。
 */
@SuppressLint("ViewConstructor")
class SymbolPanelUi(
    override val ctx: Context,
    private val theme: Theme,
    private val catalogType: SymbolCatalogType,
    private val columns: Int,
    private val textSize: Float,
    private val policy: PickerPolicy,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener
) : Ui {

    private companion object {
        /** 左栏宽度与行高，对齐 Foxy 的 `b(84)` 与 `b(40)`。 */
        const val SIDEBAR_WIDTH_DP = 84
        const val SIDEBAR_ITEM_HEIGHT_DP = 40
        const val UTILITY_ROW_HEIGHT_DP = 40
        const val GRID_ITEM_HEIGHT_DP = 40

        /** 功能键文字大小，对齐 Foxy 的 20sp（两枚键均分 84dp，每枚 42dp）。 */
        const val UTILITY_KEY_TEXT_SIZE = 20f

        /** 与 Foxy 的 `ly.j` / `ly.k` 相同的字形。 */
        const val LABEL_BACK_TO_KEYBOARD = "⌨"
        const val LABEL_BACKSPACE = "⌫"

        val DigitRange = '0'.code..'9'.code
        val FullWidthDigitRange = '０'.code..'９'.code
    }

    /**
     * 当前生效的 catalog。**不缓存在本地**：用户可能在设置里切换自备 catalog，
     * 故每次 [rebuild] 都重新取（[SymbolCatalogs] 内部按「种类 + 所选文件」缓存）。
     */
    private var catalog: SymbolCatalog = SymbolCatalog(emptyList(), multiLine = false)

    private val recentlyUsed = RecentlyUsed(catalogType.recentKey, columns * 3)

    private var groups: List<SymbolGroup> = emptyList()
    private var selectedIndex = 0

    private val gridItemHeight = ctx.dp(GRID_ITEM_HEIGHT_DP)

    private val keyAppearance = KeyDef.Appearance.Text(
        displayText = "",
        textSize = textSize,
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Default
    )

    private val gridAdapter = object : RecyclerView.Adapter<GridHolder>() {

        var items: List<String> = emptyList()
            set(value) {
                field = value
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridHolder {
            val keyView = TextKeyView(ctx, theme, keyAppearance)
            keyView.id = View.generateViewId()
            keyView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, gridItemHeight
            )
            return GridHolder(keyView)
        }

        override fun onBindViewHolder(holder: GridHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class GridHolder(private val keyView: TextKeyView) :
        RecyclerView.ViewHolder(keyView) {

        fun bind(raw: String) {
            val text = policy.transform(raw)
            keyView.mainText.text = text
            keyView.mainText.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                FontProviders.getFontSize("key_main_font", textSize)
            )
            keyView.mainText.setFontTypeFace("key_main_font")
            // 面板靠滚动翻页，不用滑动切页手势
            keyView.swipeEnabled = false
            keyView.onGestureListener = null
            keyView.setOnClickListener { commit(raw) }
            keyView.setOnLongClickListener {
                val popup = policy.popup(raw)
                if (popup == null) {
                    false
                } else {
                    // 弹出键盘需要按键在屏幕上的实际位置
                    keyView.updateBounds()
                    popupActionListener.onPopupAction(
                        PopupAction.ShowKeyboardAction(keyView.id, popup, keyView.bounds)
                    )
                    true
                }
            }
        }
    }

    private val sidebarAdapter = object : RecyclerView.Adapter<SidebarHolder>() {

        override fun getItemCount() = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarHolder {
            val label = AutoScaleTextView(ctx).apply {
                gravity = Gravity.CENTER
                scaleMode = AutoScaleTextView.Mode.Proportional
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val h = ctx.dp(4)
                val v = ctx.dp(2)
                setPadding(h, v, h, v)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(SIDEBAR_ITEM_HEIGHT_DP)
                )
                isClickable = true
                isFocusable = true
                contentDescription = null
            }
            return SidebarHolder(label)
        }

        override fun onBindViewHolder(holder: SidebarHolder, position: Int) {
            holder.bind(position)
        }
    }

    private inner class SidebarHolder(private val label: AutoScaleTextView) :
        RecyclerView.ViewHolder(label) {

        fun bind(position: Int) {
            val active = position == selectedIndex
            label.text = groups[position].name
            label.setTextColor(
                if (active) theme.accentKeyTextColor else theme.keyTextColor.alpha(0.65f)
            )
            label.background = if (active) {
                ColorDrawable(theme.genericActiveBackgroundColor)
            } else if (ThemeManager.prefs.keyRippleEffect.getValue()) {
                rippleDrawable(theme.keyPressHighlightColor)
            } else {
                pressHighlightDrawable(theme.keyPressHighlightColor)
            }
            label.setOnClickListener { selectGroup(position) }
        }
    }

    private val sidebarRecycler = RecyclerView(ctx).apply {
        id = View.generateViewId()
        layoutManager = LinearLayoutManager(ctx)
        adapter = sidebarAdapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private val gridRecycler = RecyclerView(ctx).apply {
        id = View.generateViewId()
        layoutManager = GridLayoutManager(ctx, columns)
        adapter = gridAdapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    /**
     * 构造左栏底部的一个小功能键。
     *
     * 复用 [TextKeyView]，因此自动继承主题的按键背景、圆角、边框、按压高亮与字体设置。
     */
    private fun createUtilityKey(
        label: String,
        descriptionRes: Int,
        action: KeyAction,
        repeat: Boolean = false
    ): TextKeyView {
        val appearance = KeyDef.Appearance.Text(
            displayText = label,
            textSize = UTILITY_KEY_TEXT_SIZE,
            variant = KeyDef.Appearance.Variant.Alternative,
            border = KeyDef.Appearance.Border.Default
        )
        return TextKeyView(ctx, theme, appearance).apply {
            id = View.generateViewId()
            mainText.text = label
            mainText.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                FontProviders.getFontSize("key_main_font", UTILITY_KEY_TEXT_SIZE)
            )
            mainText.setFontTypeFace("key_main_font")
            contentDescription = ctx.getString(descriptionRes)
            // 功能键不参与滑动切页/长按弹窗等键盘手势
            swipeEnabled = false
            onGestureListener = null
            val fire = { keyActionListener.onKeyAction(action, KeyActionListener.Source.Keyboard) }
            setOnClickListener { fire() }
            if (repeat) {
                // 退格长按连续删除，与键盘上的退格键一致
                repeatEnabled = true
                onRepeatListener = { fire() }
            }
        }
    }

    private val utilityRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    /** 左栏 = 分组列表（占满剩余高度）+ 底部功能键行，宽度 84dp（同 Foxy）。 */
    private val sidebarColumn = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    override val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(theme.keyboardColor)
        addView(
            sidebarColumn,
            LinearLayout.LayoutParams(
                ctx.dp(SIDEBAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            gridRecycler,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
    }

    init {
        sidebarColumn.addView(
            sidebarRecycler,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        sidebarColumn.addView(
            utilityRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(UTILITY_ROW_HEIGHT_DP)
            )
        )

        // ⌨ 返回键盘 · ⌫ 删除 —— 与 Foxy 的 ly.j / ly.k 一一对应，两枚均分 84dp。
        // 面板之间的切换不在这里：那属于布局按键/宏的配置（见类注释）。
        val gap = ctx.dp(1)
        val keys = listOf(
            createUtilityKey(
                label = LABEL_BACK_TO_KEYBOARD,
                descriptionRes = R.string.back_to_keyboard,
                action = KeyAction.LayoutSwitchAction(TextKeyboard.Name)
            ),
            createUtilityKey(
                label = LABEL_BACKSPACE,
                descriptionRes = R.string.backspace,
                action = SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)),
                repeat = true
            )
        )
        keys.forEachIndexed { index, key ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) lp.marginStart = gap
            utilityRow.addView(key, lp)
        }

        rebuild()
    }

    /**
     * 重新构建分组列表（语言、过滤偏好、所选 catalog 或最近使用变化后调用）。
     */
    fun rebuild() {
        // 每次都重新取：用户可能刚在设置里换了自备 catalog
        catalog = SymbolCatalogs.get(catalogType)
        val list = ArrayList<SymbolGroup>(catalog.groups.size + 1)
        val recent = recentlyUsed.items.filter(policy::filter)
        if (recent.isNotEmpty()) {
            list.add(SymbolGroup(ctx.getString(R.string.symbol_panel_recent), recent))
        }
        catalog.groups.forEach { group ->
            val filtered = group.symbols.filter(policy::filter)
            if (filtered.isNotEmpty()) {
                list.add(SymbolGroup(group.name, filtered))
            }
        }
        groups = list
        if (selectedIndex >= groups.size) selectedIndex = 0
        @Suppress("NotifyDataSetChanged")
        sidebarAdapter.notifyDataSetChanged()
        applySelectedGroup()
    }

    /**
     * 面板将要显示时调用：重建分组并刷新网格。
     *
     * 以下四件事只能在这里对齐，故每次都重建（而不是"无变化就跳过"）：
     * - 「最近使用」可能在面板未显示时被写入；
     * - 过滤偏好（如「隐藏不支持的 emoji」）可能已改变；
     * - **所选的自备 catalog 可能已在设置里切换**；
     * - 字体配置可能已改变——[GridHolder.bind] 会按当前配置重设字号，
     *   而 `notifyDataSetChanged` 会让所有可见项重新绑定。
     */
    fun refresh() {
        rebuild()
    }

    private fun selectGroup(index: Int) {
        selectedIndex = index
        @Suppress("NotifyDataSetChanged")
        sidebarAdapter.notifyDataSetChanged()
        applySelectedGroup()
    }

    private fun applySelectedGroup() {
        val group = groups.getOrNull(selectedIndex)
        gridAdapter.items = group?.symbols ?: emptyList()
        if (group != null) {
            gridRecycler.scrollToPosition(0)
        }
    }

    fun scrollToTop() {
        gridRecycler.scrollToPosition(0)
        sidebarRecycler.scrollToPosition(selectedIndex.coerceAtLeast(0))
    }

    private fun commit(raw: String) {
        val text = policy.transform(raw)
        keyActionListener.onKeyAction(KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard)
        insertRecent(text)
    }

    private fun insertRecent(text: String) {
        // 单个数字（半角/全角）不进"最近使用"，否则面板一打开就被数字占满
        if (text.length == 1) {
            val code = text[0].code
            if (code in DigitRange || code in FullWidthDigitRange) return
        }
        recentlyUsed.insert(text)
    }
}
