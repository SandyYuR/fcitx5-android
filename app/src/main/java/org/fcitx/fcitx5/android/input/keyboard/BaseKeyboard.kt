/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.SparseIntArray
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.CallSuper
import androidx.tracing.trace
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.graphics.ColorUtils
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.AuxBarAction
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.SwipeAxis
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.utils.DeviceInfoCollector
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable

// Import Macro types
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import timber.log.Timber
import java.util.WeakHashMap
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

abstract class BaseKeyboard(
    context: Context,
    protected var theme: Theme,
    private val layoutProvider: () ->List<List<KeyDef>>,
    private val auxBarConfigProvider: () -> AuxBarConfig? = { null },
    private val auxBarKeysProvider: () -> List<KeyDef> = { emptyList() }
) : ConstraintLayout(context) {

    private val keyLayout: List<List<KeyDef>>
        get() = layoutProvider()
    private val auxBarConfig: AuxBarConfig?
        get() = auxBarConfigProvider()
    private val auxBarKeyDefs: List<KeyDef>
        get() = auxBarKeysProvider()
    var keyActionListener: KeyActionListener? = null
    var auxBarListener: ((List<AuxBarAction>, List<AuxBarAction>) -> Unit)? = null

    private val prefs = AppPrefs.getInstance()
    private val splitKeyboardManager = SplitKeyboardStateManager.getInstance()
    private val cachedMacroScancodes = SparseIntArray(64)
    private val shiftKeyCode = KeyEvent.KEYCODE_SHIFT_LEFT
    private val shiftScanCode by lazy { mapFcitxToScanCode("Shift_L", shiftKeyCode) }

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor

    /**
     * 空格键（[SpaceKey]/[MiniSpaceKey]）注册表：视图 + 它在布局里配置的划动动作。
     *
     * 空格键是唯一一个手势语义被设置开关接管的键：`spaceSwipeMoveCursor` 开启时横向/
     * 纵向划动移动光标，关闭时才让位给按键在布局编辑器里配置的划动动作。两种模式互斥，
     * 且**都不注册** [KeyDef.Behavior.Swipe]——通用 Swipe 绑定会把横向阈值改写成
     * `disabledSwipeThreshold`，那会直接废掉光标模式的移动（见 [createKeyView]）。
     */
    private data class SpaceKeyEntry(val keyView: KeyView, val swipeAction: MacroAction?)

    private val spaceKeys = mutableListOf<SpaceKeyEntry>()

    /**
     * 空格键是否参与划动手势。
     *
     * 开关开启 → 光标手势接管；关闭 → 只有配了划动动作才接管。两者都不成立时保持
     * `swipeEnabled = false`，这样手指在空格键上移动不会取消长按（语音输入的按住说话
     * 依赖长按判定，误取消会让录音半途中断）。
     */
    private fun spaceSwipeEnabledFor(swipeAction: MacroAction?): Boolean =
        resolveSpaceSwipeEnabled(spaceSwipeMoveCursor.getValue(), swipeAction)

    /**
     * 按当前开关设定空格键的两轴阈值。两种模式互斥，所以同一时刻只有一套阈值生效。
     *
     * 光标模式用小的选择阈值（逐格移动光标）；划动模式关掉横向、纵向用常规输入阈值
     * （与其它键的 [KeyDef.Behavior.Swipe] 一致）。
     */
    private fun applySpaceSwipeThresholds(view: KeyView) {
        if (spaceSwipeMoveCursor.getValue()) {
            view.swipeThresholdX = selectionSwipeThreshold
            view.swipeThresholdY = selectionSwipeThreshold * 1.5f
        } else {
            view.swipeThresholdX = disabledSwipeThreshold
            view.swipeThresholdY = inputSwipeThreshold
        }
    }

    /**
     * 登记/更新一个空格键条目。
     *
     * 按**视图身份**（`===`）定位并就地替换，不能用 `indexOf`/`contains`：`SpaceKeyEntry`
     * 是 data class，其 `equals` 会逐字段比较，两个内容相同的条目（同一布局里多个空格键
     * 配了同一动作）会互相匹配到对方的槽位，把动作写到错的视图上。
     */
    private fun registerSpaceKeyEntry(view: KeyView, swipeAction: MacroAction?) {
        for (i in spaceKeys.indices) {
            if (spaceKeys[i].keyView === view) {
                spaceKeys[i] = SpaceKeyEntry(view, swipeAction)
                return
            }
        }
        spaceKeys.add(SpaceKeyEntry(view, swipeAction))
    }

    /**
     * 摘掉某个视图的空格键记录。
     *
     * compose 重绑会换掉视图实例，旧实例必须跟着离开注册表：否则设置监听器会一直去
     * 修改已经脱离视图树的旧视图，且每次 compose 往返都会留下一条记录、无界增长。
     */
    private fun removeSpaceKeyEntry(view: KeyView) {
        spaceKeys.removeAll { it.keyView === view }
    }

    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        // 手势监听器运行时读取开关（见 createKeyView），因此切模式只需同步"是否参与
        // 手势"与阈值。已记录的基线也要一起更新，否则下一次重绑会从基线恢复出旧值。
        spaceKeys.forEach { entry ->
            entry.keyView.swipeEnabled = spaceSwipeEnabledFor(entry.swipeAction)
            applySpaceSwipeThresholds(entry.keyView)
            gestureBaselines[entry.keyView]?.let { baseline ->
                gestureBaselines[entry.keyView] = baseline.copy(
                    swipeEnabled = entry.keyView.swipeEnabled,
                    swipeThresholdX = entry.keyView.swipeThresholdX,
                    swipeThresholdY = entry.keyView.swipeThresholdY
                )
            }
        }
    }

    /**
     * 「空格键长按行为」偏好。与划动开关不同，它**不是**布尔开关，而是枚举；
     * 只有 [SpaceLongPressBehavior.Repeat] 这一个取值会改变键盘侧的行为。
     */
    private val spaceKeyLongPressBehavior = prefs.keyboard.spaceKeyLongPressBehavior

    private fun isSpaceRepeatEnabled(): Boolean =
        spaceKeyLongPressBehavior.getValue() == SpaceLongPressBehavior.Repeat

    /**
     * 按当前偏好给空格键绑定/解绑「长按重复输入空格」。
     *
     * **不走 [KeyDef.Behavior.Repeat]**：那要往 [SpaceKey] 的 behaviors 里加项，而 KeyDef
     * 只在布局加载时构造一次，缓存行的签名也不含这个维度，改设置就不会重建 → 开关失效。
     * 这里按视图绑定（与划动开关同一套做法），设置变化由 [spaceLongPressChangeListener]
     * 同步。
     *
     * 必须在 [applyBehaviorPopupBindings] **之后**调用：它会先把 `repeatEnabled` 与
     * `onRepeatListener` 清空。
     */
    private fun applySpaceRepeatBinding(view: KeyView) {
        if (!isSpaceRepeatEnabled()) {
            view.repeatEnabled = false
            view.onRepeatListener = null
            return
        }
        view.repeatEnabled = true
        view.onRepeatListener = { currentView ->
            // 与空格键的 Behavior.Press 发同一个 keysym，保证重复出来的就是普通空格。
            onAction(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space)))
            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(currentView)
        }
    }

    /** 只对空格键生效的重绑入口（供 compose 重绑等通用路径调用）。 */
    private fun refreshSpaceRepeatBinding(view: KeyView) {
        if (spaceKeys.any { it.keyView === view }) applySpaceRepeatBinding(view)
    }

    private val spaceLongPressChangeListener =
        ManagedPreference.OnChangeListener<SpaceLongPressBehavior> { _, _ ->
            spaceKeys.forEach { entry -> applySpaceRepeatBinding(entry.keyView) }
        }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    // 退格键"上滑清空"：长按进入连删后弹出提示条，手指滑进提示条区域即清空正文。
    private var backspaceClearPopup: PopupWindow? = null
    private var backspaceClearTriggered = false

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val bounds = Rect()
    private lateinit var keyRows: List<ConstraintLayout>
    private var keyboardWaterRippleView: KeyboardWaterRippleView? = null
    private var cachedWaterRippleColor: Int? = null
    private var rowHeightPercents: List<Float> = emptyList()
    private var horizontalGapScale = 1f
    private var composing = false

    // Edge zone views
    private var auxBarInnerContainer: ConstraintLayout? = null
    private var auxBarScrollableAdapter: AuxBarAdapter? = null
    private var auxBarPinnedAdapter: AuxBarAdapter? = null
    private var auxBarScrollableRv: RecyclerView? = null
    private var auxBarKeyAdapter: AuxBarKeyAdapter? = null
    private var mainGridContainer: ConstraintLayout? = null
    // 纵向（Left/Right）辅助栏的 scrollable 区容器：跟主键盘一样按 item 数量
    // 均分高度，主题上下间距由 item 自身 padding 表达。高度变化（浮动 resize）
    // 时由 layout 回调重算每个 item 高度，按钮之间不留空隙。
    private var auxBarScrollableContainer: LinearLayout? = null
    // 纵向辅助栏当前 scrollable 区里实际展示的 item view 列表，
    // 高度变化时逐个重设 LayoutParams 高度（权重需要容器先有确定高度，
    // 而浮动 resize 过程中容器高度连续变化，直接写 px 高度更跟手）。
    private val auxBarScrollableItemViews = mutableListOf<View>()
    // 与 [auxBarScrollableItemViews] 一一对应的自定义行高（KeyDef.rowHeightPercent）。
    // null = 该 item 没有自定义行高：tabs（音节选择器）整列都是 null，继续按数量均分。
    private val auxBarScrollableItemRowHeights = mutableListOf<Float?>()
    // scrollable 容器高度变化时重排 item 高；挂在容器自身上，随容器一起回收。
    private val auxBarScrollableContainerLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            relayoutVerticalAuxBarItems()
        }

    private data class GestureBaseline(
        val swipeEnabled: Boolean,
        val swipeRepeatEnabled: Boolean,
        val swipeThresholdX: Float,
        val swipeThresholdY: Float,
        val onGestureListener: OnGestureListener?
    )

    private data class ComposeAwareKey(
        val baseDef: KeyDef,
        var keyView: KeyView,
        var baseline: GestureBaseline
    )

    private val composeAwareKeys = mutableListOf<ComposeAwareKey>()

    /**
     * Gesture configuration each [KeyView] was *built* with, keyed by the view.
     *
     * Needed because [applyBehaviorPopupBindings] replaces `onGestureListener` with a wrapper
     * that delegates to the previous one. Recovering a "baseline" by reading the view back
     * therefore captures the wrapper, and every reuse of a cached row wrapped it again — an
     * unbounded listener chain, with every layer re-running the behavior checks on each move
     * event. Weak keys so cached-but-discarded rows do not pin their views.
     */
    private val gestureBaselines = WeakHashMap<KeyView, GestureBaseline>()

    private data class ReusableRows(
        val defs: List<List<KeyDef>>,
        val containers: List<ConstraintLayout>
    )

    // Rows built by a previous reload are cached per layout/style signature and reused on
    // the next reload with the same signature. Rebuilding the whole view tree on every key
    // press (macrokey "layer to" or shift-based language switching) is the dominant source
    // of input latency; reusing prebuilt rows skips the expensive view construction entirely.
    // NOTE: must be declared before the init block, which triggers the first reloadLayout().
    private val reusableRowsCache = object : LinkedHashMap<String, ReusableRows>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ReusableRows>): Boolean =
            size > MAX_CACHED_ROWS
    }
    private var lastRowsSignature: String? = null

    private companion object {
        const val MAX_CACHED_ROWS = 12
    }

    private var lastSplitLandscapeState = false

    // onSizeChanged 在**布局过程中**被调用：此时同步 reloadLayout（removeAllViews + 重新 addView）
    // 会在当前 measure/layout 途中改动视图树，刚加回来的行可能这一趟没被测量，留下 0 高度——
    // 浮动键盘来回拖宽、反复越过分体阈值时就表现为"按键全部挤到一行"，要等下一次宽度变化
    // 才恢复。改为把 reload 推迟到本趟布局之后（post），并用此标志合并连续的 onSizeChanged，
    // 避免拖动期间重复排队。
    private var pendingSplitReload = false

    // 分体用的行集合缓存：对奇数字符行复制中间键后的结果，按源行集合的实例身份记忆，
    // 使上层行缓存（按 defs 身份比较）仍能命中。详见 splitRowsForLayout。
    private var splitRowsSource: List<List<KeyDef>>? = null
    private var splitRowsCached: List<List<KeyDef>>? = null
    private var splitRowsDuplicateEnabled = false

    private class TouchTarget(val view: KeyView)

    /** Active pointer targets for the custom touch dispatch workaround. */
    private val touchTargets = hashMapOf<Int, TouchTarget>()

    /**
     * Compose-aware views whose recreation was deferred because a finger was on them.
     *
     * Flushed once no pointer is left; see [flushDeferredComposeRecreations].
     */
    private var pendingComposeRecreations: List<ComposeAwareKey>? = null

    /**
     * Find a key view by its type tag. Returns the first matching view or null if not found.
     */
    protected inline fun <reified T : View> findKeyViewById(tagId: Int): T? {
        return allViews.firstOrNull { it.tag == tagId && it is T } as? T
    }

    init {
        isMotionEventSplittingEnabled = true
        clipChildren = false
        clipToPadding = false
        reloadLayout()
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        spaceKeyLongPressBehavior.registerOnChangeListener(spaceLongPressChangeListener)
        // No listener registration on SplitKeyboardStateManager: it is an application-scoped
        // singleton that never notified anyone (notifyListeners had no callers), while every
        // registration pinned a keyboard view tree for the life of the process. Split state
        // is computed directly via shouldUseSplitKeyboard() in reloadLayout().
    }

    /**
     * Signature identifying the semantic keyboard content (layer, input method, sub mode, ...).
     * Every subclass must implement this so cached rows are never reused across different
     * layouts. For static layouts a constant identifying the layout is sufficient; layouts
     * that change at runtime must derive the signature from the resolved layout inputs.
     */
    protected abstract fun currentLayoutSignature(): String

    private fun currentRowsSignature(splitKeyboard: Boolean): String {
        val prefs = ThemeManager.prefs
        return buildString {
            append(currentLayoutSignature())
            append("|split:").append(splitKeyboard)
            // 分体中间键复制开关影响行结构，纳入签名以免切换后复用到旧的行集合。
            append("|splitDup:").append(
                splitKeyboard && AppPrefs.getInstance().keyboard.splitKeyboardDuplicateMiddleKey.getValue()
            )
            append("|orient:").append(resources.configuration.orientation)
            append("|composing:").append(composing)
            append("|gapScale:").append(horizontalGapScale)
            append("|border:").append(prefs.keyBorder.getValue()).append(prefs.keyBorderStroke.getValue())
            append("|ripple:").append(prefs.keyRippleEffect.getValue())
            append("|radius:").append(prefs.keyRadius.getValue())
            append("|oval:").append(prefs.specialKeyOvalShape.getValue())
            append("|hMargins:").append(prefs.keyHorizontalMargin.getValue())
                .append(",").append(prefs.keyHorizontalMarginLandscape.getValue())
            append("|vMargins:").append(prefs.keyVerticalMargin.getValue())
                .append(",").append(prefs.keyVerticalMarginLandscape.getValue())
            append("|expandKeys:").append(AppPrefs.getInstance().keyboard.expandKeypressArea.getValue())
            append("|splitGap:").append(splitKeyboardManager.getSplitGapPercent())
            append("|fontRefresh:").append(FontProviders.needsRefresh())
            // Rows are baked with the typefaces served at build time. Bump the signature
            // whenever the served font data changes so rows built while a font reload was
            // still in flight are rebuilt with the new fonts instead of being reused.
            append("|fontGen:").append(FontProviders.fontGeneration)
        }
    }

    /**
     * Re-register keyboard-level state for a reused row set. KeyViews keep the gesture
     * configuration they were built with, so only the registries rebuilt on every reload
     * (space keys, compose-aware keys) need to be repopulated.
     *
     * Validate every row before changing either registry. This keeps a stale cache entry
     * from leaving partially registered state behind when the caller falls back to a rebuild.
     */
    private fun registerReusableRowState(rows: List<List<KeyDef>>, containers: List<ConstraintLayout>): Boolean {
        val validatedRows = ArrayList<List<Pair<KeyDef, KeyView>>>(rows.size)
        rows.forEachIndexed { rowIndex, rowDefs ->
            val container = containers.getOrNull(rowIndex) ?: return false
            val keyViews = container.children.mapNotNull { it as? KeyView }.toList()
            if (keyViews.size != rowDefs.size) return false
            val validatedRow = ArrayList<Pair<KeyDef, KeyView>>(rowDefs.size)
            rowDefs.forEachIndexed { keyIndex, def ->
                val keyView = keyViews[keyIndex]
                val matchesDef = keyView.def === def.appearance ||
                    def.composeOverride?.appearance === keyView.def
                if (!matchesDef) return false
                validatedRow += def to keyView
            }
            validatedRows += validatedRow
        }

        validatedRows.forEach { row ->
            row.forEach { (def, keyView) ->
                if (def is SpaceKey || def is MiniSpaceKey) {
                    // The pref is read once, when the view is built, so a reused row carries
                    // the values from whenever it was first created: toggling "swipe space to
                    // move cursor" had no effect on any layout already in the cache until the
                    // app restarted. Re-register and re-apply both the flag and the thresholds,
                    // and keep the recorded baseline in step (see [gestureBaselines]).
                    val swipeAction = (def as? SpaceKey)?.swipe
                    registerSpaceKeyEntry(keyView, swipeAction)
                    keyView.swipeEnabled = spaceSwipeEnabledFor(swipeAction)
                    applySpaceSwipeThresholds(keyView)
                    // 复用行不会走 applyBehaviorPopupBindings，重复绑定要在这一起重放
                    // （与划动开关同一理由：视图保留着上次创建时的值）。
                    applySpaceRepeatBinding(keyView)
                    gestureBaselines[keyView]?.let { baseline ->
                        gestureBaselines[keyView] = baseline.copy(
                            swipeEnabled = keyView.swipeEnabled,
                            swipeThresholdX = keyView.swipeThresholdX,
                            swipeThresholdY = keyView.swipeThresholdY
                        )
                    }
                }
                if (def.composeOverride != null) {
                    // Use the baseline recorded when this view was built. Deriving it from the
                    // view here would capture the *wrapped* gesture listener installed by the
                    // last applyBehaviorPopupBindings, so each reuse nested another layer and
                    // the chain grew without bound.
                    val baseline = gestureBaselines[keyView] ?: GestureBaseline(
                        swipeEnabled = keyView.swipeEnabled,
                        swipeRepeatEnabled = keyView.swipeRepeatEnabled,
                        swipeThresholdX = keyView.swipeThresholdX,
                        swipeThresholdY = keyView.swipeThresholdY,
                        onGestureListener = keyView.onGestureListener
                    )
                    composeAwareKeys += ComposeAwareKey(def, keyView, baseline)
                }
            }
        }
        return true
    }

    protected open fun reloadLayout() {
        dismissBackspaceClearPopup()
        val startedAt = SystemClock.elapsedRealtime()
        // Detach ripple-occluder listeners from views of the outgoing tree before it is
        // discarded or cached; the ripple view itself is recreated on every reload.
        keyboardWaterRippleView?.setOccluders(emptyList())
        // Cached rows are nested in mainGridContainer rather than directly in this view.
        // Removing the main grid does not clear the rows' parent, so detach them explicitly
        // before a cached row is added to the new grid.
        reusableRowsCache.values
            .flatMap { it.containers }
            .distinct()
            .forEach { row ->
                (row.parent as? ViewGroup)?.removeView(row)
            }
        removeAllViews()
        auxBarInnerContainer = null
        auxBarScrollableAdapter = null
        auxBarPinnedAdapter = null
        auxBarScrollableRv = null
        auxBarScrollableContainer = null
        auxBarScrollableItemViews.clear()
        auxBarScrollableItemRowHeights.clear()
        auxBarKeyAdapter = null
        mainGridContainer = null
        cachedWaterRippleColor = null
        keyboardWaterRippleView = KeyboardWaterRippleView(context).also { rippleView ->
            add(rippleView, lParams(matchParent, matchParent))
        }
        spaceKeys.clear()
        // Drop queued recreations *before* releasing touch targets: that release flushes the
        // queue, and the rows about to be rebuilt (or re-registered) supersede it anyway —
        // those ComposeAwareKey entries point at views that may leave the tree.
        pendingComposeRecreations = null
        releaseAllTouchTargets()
        composeAwareKeys.clear()

        val splitKeyboard = splitKeyboardManager.shouldUseSplitKeyboard(width)
        lastSplitLandscapeState = splitKeyboard
        val rows = keyLayout
        // 分体时对奇数字符行复制中间键，让左右两半对称可触达（见 splitRowsForLayout）。
        // 非分体用原始行；返回实例按源身份记忆，故行缓存命中率不受影响。
        val effectiveRows = if (splitKeyboard) splitRowsForLayout(rows) else rows
        rowHeightPercents = resolveRowHeightPercents(effectiveRows)

        val rowsSignature = currentRowsSignature(splitKeyboard)
        val cachedRows = reusableRowsCache[rowsSignature]
        // Reuse only when the cached rows were built from the exact same KeyDef instances;
        // providers that re-create defs on every call (e.g. the builtin fallback layout)
        // then rebuild instead of silently re-registering mismatched state.
        keyRows = if (cachedRows != null && cachedRows.defs === effectiveRows &&
            registerReusableRowState(effectiveRows, cachedRows.containers)
        ) {
            cachedRows.containers
        } else {
            val built = effectiveRows.map { row ->
                val keyViews = row.map(::createKeyView).apply {
                    // Batch apply fontset mappings for all key labels.
                    forEach(::applyConfiguredFonts)
                }
                if (splitKeyboard) {
                    buildSplitRow(row, keyViews)
                } else {
                    buildRegularRow(row, keyViews)
                }
            }
            reusableRowsCache[rowsSignature] = ReusableRows(effectiveRows, built)
            built
        }
        lastRowsSignature = rowsSignature
        // Text scale is applied to KeyViews after they are built, so it is not part of the
        // row signature. Reusing a cached row set therefore brings back whatever scale those
        // views were last given: after changing the key text size, switching layouts and back
        // showed the old size until the next style refresh. Re-apply the current scale here.
        reapplyTextScale()

        val auxBarConfig = auxBarConfig
        if (auxBarConfig != null && auxBarConfig.position != AuxBarPosition.AbovePreedit) {
            val isVertical = auxBarConfig.position == AuxBarPosition.Left || auxBarConfig.position == AuxBarPosition.Right
            val keyPrefs = ThemeManager.prefs
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val vMargin = dp(
                (if (isLandscape) keyPrefs.keyVerticalMarginLandscape else keyPrefs.keyVerticalMargin).getValue()
            )
            val hMargin = dp(
                (if (isLandscape) keyPrefs.keyHorizontalMarginLandscape else keyPrefs.keyHorizontalMargin).getValue()
            )
            val auxBarInnerLayout = constraintLayout()
            // 横向（Top/Bottom）辅助栏：item 高 MATCH_PARENT 自动跟随容器，
            // 继续沿用 RecyclerView 方案。
            val scrollableRv = recyclerView {
                layoutManager = LinearLayoutManager(context).apply {
                    orientation = LinearLayoutManager.HORIZONTAL
                }
                overScrollMode = OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }
            // Use the dominant text size in the current layout instead of the first
            // textual key, which may be a small special key.
            val keyTextSize = rows.asSequence()
                .flatMap { it.asSequence() }
                .mapNotNull { (it.appearance as? KeyDef.Appearance.Text)?.textSize }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: 16f
            val scrollableAdapter = AuxBarAdapter(theme, vMargin, hMargin, auxBarConfig.position, keyTextSize) { id ->
                keyActionListener?.onKeyAction(KeyAction.AuxBarTrigger(id), KeyActionListener.Source.Keyboard)
            }
            scrollableRv.adapter = scrollableAdapter
            val keyAdapter = AuxBarKeyAdapter(
                vertical = isVertical,
                keyViewFactory = { def ->
                    createKeyView(def, registerComposeAware = false).also(::applyConfiguredFonts)
                }
            )
            val pinnedRv = recyclerView {
                layoutManager = LinearLayoutManager(context).apply {
                    orientation = if (isVertical) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL
                }
                isNestedScrollingEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }
            val pinnedAdapter = AuxBarAdapter(theme, vMargin, hMargin, auxBarConfig.position, keyTextSize) { id ->
                keyActionListener?.onKeyAction(KeyAction.AuxBarTrigger(id), KeyActionListener.Source.Keyboard)
            }
            pinnedRv.adapter = pinnedAdapter

            // 纵向（Left/Right）辅助栏跟主键盘一样按行排布：LinearLayout 容器顶底
            // 撑满，按 item 数量均分 scrollable 区高度（pinned 固定在底部），主题
            // 上下间距由 item 自身 padding 表达。这样缩小高度时只压缩按键本体，
            // 按钮之间不会留空隙；容器高度变化时由 layout 回调逐个重设 item 高。
            if (isVertical) {
                val scrollableContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addOnLayoutChangeListener(auxBarScrollableContainerLayoutListener)
                }
                auxBarScrollableContainer = scrollableContainer
                auxBarScrollableItemViews.clear()
                auxBarScrollableItemRowHeights.clear()
                auxBarInnerLayout.add(scrollableContainer, lParams {
                    topOfParent()
                    bottomOfParent()
                    centerHorizontally()
                    above(pinnedRv)
                })
                auxBarInnerLayout.add(pinnedRv, lParams(height = wrapContent) {
                    bottomOfParent()
                    centerHorizontally()
                    topMargin = vMargin
                })
            } else {
                auxBarInnerLayout.add(scrollableRv, lParams {
                    topOfParent()
                    bottomOfParent()
                    leftOfParent()
                    rightToLeftOf(pinnedRv)
                })
                auxBarInnerLayout.add(pinnedRv, lParams(width = wrapContent) {
                    topOfParent()
                    bottomOfParent()
                    rightOfParent()
                    leftMargin = hMargin
                })
            }

            val mainGrid = constraintLayout()
            keyRows.forEachIndexed { index, row ->
                mainGrid.add(row, lParams {
                    height = 0
                    verticalWeight = rowHeightPercents.getOrElse(index) { 1f }
                    if (index == 0) topOfParent()
                    else below(keyRows[index - 1])
                    if (index == keyRows.size - 1) bottomOfParent()
                    else above(keyRows[index + 1])
                    centerHorizontally()
                })
            }

            val sizeFraction = auxBarConfig.sizePercent / 100f
            when (auxBarConfig.position) {
                AuxBarPosition.Left -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentWidth = sizeFraction
                        topOfParent(); bottomOfParent(); leftOfParent()
                    })
                    add(mainGrid, lParams {
                        topOfParent(); bottomOfParent(); rightOfParent()
                        leftToRightOf(auxBarInnerLayout)
                    })
                }
                AuxBarPosition.Right -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentWidth = sizeFraction
                        topOfParent(); bottomOfParent(); rightOfParent()
                    })
                    add(mainGrid, lParams {
                        topOfParent(); bottomOfParent(); leftOfParent()
                        rightToLeftOf(auxBarInnerLayout)
                    })
                }
                AuxBarPosition.Top -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentHeight = sizeFraction
                        topOfParent(); leftOfParent(); rightOfParent()
                    })
                    add(mainGrid, lParams {
                        bottomOfParent(); leftOfParent(); rightOfParent()
                        below(auxBarInnerLayout)
                    })
                }
                AuxBarPosition.Bottom -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentHeight = sizeFraction
                        bottomOfParent(); leftOfParent(); rightOfParent()
                    })
                    add(mainGrid, lParams {
                        topOfParent(); leftOfParent(); rightOfParent()
                        above(auxBarInnerLayout)
                    })
                }
            }
            auxBarInnerContainer = auxBarInnerLayout
            auxBarScrollableAdapter = scrollableAdapter
            auxBarPinnedAdapter = pinnedAdapter
            auxBarScrollableRv = scrollableRv
            auxBarKeyAdapter = keyAdapter
            mainGridContainer = mainGrid
            // Keep aux bar usable before first candidate update (e.g. in preview or idle startup):
            // if no tabs are available, show configured fallback keys immediately.
            applyAuxBarContent(emptyList(), emptyList())
            if (!isVertical) {
                post {
                    auxBarScrollableAdapter?.let { adapter ->
                        auxBarScrollableRv?.let { rv -> adapter.applyConfiguredFonts(rv) }
                    }
                    auxBarPinnedAdapter?.let { adapter ->
                        auxBarScrollableRv?.let { rv -> adapter.applyConfiguredFonts(rv) }
                    }
                }
            }
        } else {
            keyRows.forEachIndexed { index, row ->
                add(row, lParams {
                    height = 0
                    verticalWeight = rowHeightPercents.getOrElse(index) { 1f }
                    if (index == 0) topOfParent()
                    else below(keyRows[index - 1])
                    if (index == keyRows.size - 1) bottomOfParent()
                    else above(keyRows[index + 1])
                    centerHorizontally()
                })
            }
        }

        keyboardWaterRippleView?.setOccluders(
            keyRows.flatMap { row -> row.children.mapNotNull { it as? KeyView }.toList() }
        )
        Log.i(
            "FcitxColdStart",
            "${javaClass.simpleName}.reloadLayout rows=${keyRows.size} " +
                "duration=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
    }

    fun updateAuxBarActions(actions: List<AuxBarAction>) {
        val scrollable = actions.takeWhile { !it.isSeparator }
        val pinned = actions.drop(scrollable.size + 1).filter { !it.isSeparator }
        val cfg = auxBarConfig
        if (cfg?.position == AuxBarPosition.AbovePreedit) {
            auxBarListener?.invoke(scrollable, pinned)
            return
        }
        if (cfg != null) {
            auxBarListener?.invoke(emptyList(), emptyList())
            applyAuxBarContent(scrollable, pinned)
        }
    }

    private fun applyAuxBarContent(scrollable: List<AuxBarAction>, pinned: List<AuxBarAction>) {
        val cfg = auxBarConfig ?: return
        val customKeys = auxBarKeyDefs
        val hasTabs = scrollable.isNotEmpty() || pinned.isNotEmpty()
        if (cfg.position == AuxBarPosition.Left || cfg.position == AuxBarPosition.Right) {
            applyVerticalAuxBarContent(scrollable, pinned, customKeys)
            return
        }
        val rv = auxBarScrollableRv
        if (rv?.adapter != auxBarScrollableAdapter) {
            rv?.adapter = auxBarScrollableAdapter
        }
        auxBarScrollableAdapter?.updateActions(scrollable)
        auxBarPinnedAdapter?.updateActions(pinned)
    }

    /**
     * 纵向（Left/Right）辅助栏内容：默认跟主键盘一样按 item 数量均分 scrollable 区
     * 高度。主题上下间距由 item 自身 padding 表达（[AuxBarAdapter] 纵向分支），
     * 所以缩小高度时只压缩按键本体，按钮之间不留空隙。
     *
     * tabs（[AuxBarAction]）和无 tabs 时的自定义按键（[KeyDef]）共用同一套排布：
     * 有 tabs 显示 tabs，无 tabs 显示配置的自定义按键（数量按键盘行数截断）。
     * 区别只在高度来源——tabs（音节选择器）没有行高配置，保持按数量均分；
     * 自定义按键则带上各自的 `rowHeightPercent`，由 [relayoutVerticalAuxBarItems]
     * 按行高比例分配（未配置行高的按键平分剩余比例）。
     */
    private fun applyVerticalAuxBarContent(
        scrollable: List<AuxBarAction>,
        pinned: List<AuxBarAction>,
        customKeys: List<KeyDef>
    ) {
        val container = auxBarScrollableContainer ?: return
        val scrollableAdapter = auxBarScrollableAdapter ?: return
        val hasTabs = scrollable.isNotEmpty() || pinned.isNotEmpty()
        container.removeAllViews()
        auxBarScrollableItemViews.clear()
        auxBarScrollableItemRowHeights.clear()
        if (hasTabs) {
            for (action in scrollable) {
                val item = scrollableAdapter.createItemView(container)
                scrollableAdapter.bindItemView(item, action)
                container.addView(item)
                auxBarScrollableItemViews.add(item)
                auxBarScrollableItemRowHeights.add(null)
            }
            auxBarPinnedAdapter?.updateActions(pinned)
        } else {
            val visibleCustomKeys = customKeys.take(keyRows.size)
            val keyAdapter = auxBarKeyAdapter
            if (visibleCustomKeys.isNotEmpty() && keyAdapter != null) {
                for (key in visibleCustomKeys) {
                    val item = keyAdapter.createItemView(container, key)
                    container.addView(item)
                    auxBarScrollableItemViews.add(item)
                    auxBarScrollableItemRowHeights.add(key.rowHeightPercent?.takeIf { it in 1f..100f })
                }
            }
            auxBarPinnedAdapter?.updateActions(emptyList())
        }
        relayoutVerticalAuxBarItems()
    }

    /**
     * 按容器当前高度排布每个 item 的高度。容器高度变化（浮动 resize、pinned
     * 区出现/消失）时由 layout 回调触发，直接写 px 高度，比权重方案更跟手。
     *
     * tabs（音节选择器）没有行高配置：保持按 item 数量整数均分，不动。
     * 自定义按键带 `rowHeightPercent` 时按比例分配，未配置的平分剩余比例
     * （与主键盘 [resolveRowHeightPercents] 同一套归一化规则）；最后一项用
     * 剩余高度补齐，避免取整误差在底部留下空隙。
     */
    private fun relayoutVerticalAuxBarItems() {
        val container = auxBarScrollableContainer ?: return
        val count = auxBarScrollableItemViews.size
        if (count == 0) return
        val height = container.height
        if (height <= 0) {
            container.post { relayoutVerticalAuxBarItems() }
            return
        }
        val customRowHeights = hasCustomVerticalAuxBarRowHeights(count)
        val heights = resolveVerticalAuxBarItemHeights(height, count, customRowHeights)
        auxBarScrollableItemViews.forEachIndexed { index, item ->
            val itemHeight = heights[index]
            // tabs（音节选择器）均分不足 1px 时沿用旧行为：不写 LayoutParams。
            // 自定义行高时按分配结果写死高度，含被压成 0 的项（权重 0 不占高度）。
            if (itemHeight <= 0 && !customRowHeights) return@forEachIndexed
            val lp = item.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            if (lp.height != itemHeight || lp.width != LinearLayout.LayoutParams.MATCH_PARENT) {
                lp.height = itemHeight
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                item.layoutParams = lp
            }
        }
    }

    private fun hasCustomVerticalAuxBarRowHeights(count: Int): Boolean =
        auxBarScrollableItemRowHeights.size == count &&
            auxBarScrollableItemRowHeights.any { it != null }

    private fun resolveVerticalAuxBarItemHeights(
        containerHeight: Int,
        count: Int,
        hasCustomRowHeight: Boolean
    ): IntArray {
        if (!hasCustomRowHeight) {
            val itemHeight = containerHeight / count
            return IntArray(count) { itemHeight }
        }
        val percents = resolveHeightPercents(auxBarScrollableItemRowHeights)
        val heights = IntArray(count)
        var used = 0
        for (index in 0 until count) {
            val itemHeight = if (index == count - 1) {
                containerHeight - used
            } else {
                (containerHeight * percents[index] / 100f).roundToInt()
            }
            heights[index] = itemHeight.coerceAtLeast(0)
            used += heights[index]
        }
        return heights
    }

    fun auxBarPosition(): AuxBarPosition? = auxBarConfig?.position

    private fun resolveRowHeightPercents(rows: List<List<KeyDef>>): List<Float> =
        resolveHeightPercents(
            rows.map { row ->
                row.mapNotNull { it.rowHeightPercent }
                    .maxOrNull()
                    ?.takeIf { it in 1f..100f }
            }
        )

    /**
     * 把每个 item 的行高百分比归一化到总和 100：未配置（null）的项平分剩余
     * 比例；没有任何有效比例时退回 [defaultRowHeightPercent] 的等分值。
     */
    private fun resolveHeightPercents(percents: List<Float?>): List<Float> {
        if (percents.isEmpty()) return emptyList()

        val definedSum = percents.filterNotNull().sum()
        val undefinedCount = percents.count { it == null }

        val distributed = if (undefinedCount == 0) {
            percents.map { it ?: 0f }
        } else {
            val remaining = (100f - definedSum).coerceAtLeast(0f)
            val avg = remaining / undefinedCount
            percents.map { it ?: avg }
        }

        val sum = distributed.sum()
        if (sum <= 0f) {
            val fallback = defaultRowHeightPercent(percents.size)
            return List(percents.size) { fallback }
        }

        return distributed.map { it * 100f / sum }
    }

    protected open fun defaultRowHeightPercent(rowCount: Int): Float {
        if (rowCount <= 0) return 25f
        return (100f / rowCount.toFloat()).coerceAtLeast(1f)
    }

    open fun keyboardHeightScaleFactor(): Float {
        if (rowHeightPercents.isEmpty()) return 1f
        return (rowHeightPercents.sum() / 100f).coerceAtLeast(0.1f)
    }

    open fun preferredKeyboardHeightPercentOverride(): Int? = null

    private fun splitGapPercent(): Float {
        return splitKeyboardManager.getSplitGapPercent()
    }

    private fun resolveRowWidths(row: List<KeyDef>): List<Float> {
        if (row.isEmpty()) return emptyList()
        val fixedSum = row.sumOf { def ->
            val width = def.appearance.percentWidth
            if (width > 0f) width.toDouble() else 0.0
        }.toFloat()
        val flexCount = row.count { it.appearance.percentWidth <= 0f }
        val remaining = (1f - fixedSum).coerceAtLeast(0f)
        val flexWidth = if (flexCount > 0) remaining / flexCount else 0f
        val widths = row.map { def ->
            val width = def.appearance.percentWidth
            if (width > 0f) width else flexWidth
        }
        val sum = widths.sum()
        return if (sum > 0f) {
            widths.map { it / sum }
        } else {
            val equal = 1f / widths.size
            widths.map { equal }
        }
    }

    private fun chooseSplitIndex(row: List<KeyDef>, normalizedWidths: List<Float>): Int {
        if (row.size <= 1) return 0
        val candidates = (0 until row.lastIndex)
        var prefix = 0f
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        candidates.forEach { i ->
            prefix += normalizedWidths[i]
            val distance = kotlin.math.abs(prefix - 0.5f)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }

        val spaceIndices = row.mapIndexedNotNull { index, def ->
            if (def is SpaceKey || def is MiniSpaceKey) index else null
        }.filter { it in 1 until row.lastIndex }
        if (spaceIndices.isNotEmpty()) {
            var running = 0f
            val prefixByBoundary = HashMap<Int, Float>(row.size)
            for (i in 0 until row.lastIndex) {
                running += normalizedWidths[i]
                prefixByBoundary[i] = running
            }
            spaceIndices.forEach { spaceIndex ->
                val aroundSpaceCandidates = listOf(spaceIndex - 1, spaceIndex)
                    .filter { it in 0 until row.lastIndex }
                aroundSpaceCandidates.forEach { index ->
                    val p = prefixByBoundary[index] ?: return@forEach
                    val d = kotlin.math.abs(p - 0.5f)
                    if (d <= bestDistance + 0.06f) {
                        bestDistance = d
                        bestIndex = index
                    }
                }
            }
        }
        return bestIndex
    }

    // ===== 分体键盘：奇数字符行复制中间键 =====
    // QWERTY 的 asdfghjkl(9 键)、zxcvbnm(7 键) 等字母行按键数为奇数，无法对半分体——
    // 中间那枚（g / v）只能落到某一侧，另一只手够不到，影响盲打。做法：分体时把**几何
    // 中间**的字符键复制一份，左右两半各保留一枚，两侧对称、都能触达（两枚输入同一字符）。
    //
    // 识别方式刻意**不写死 g / v**，而是「按键数为奇数 + 行内无空格桥接键 + 中间键能产出
    // 字符」。好处：Dvorak/Colemak/其它语言/自定义布局同样适用，换布局或改键都不失效；
    // 偶数行（数字行、QWERTY 顶行 10 键）本就能对半分，不受影响；含空格的行走 bridge
    // 分支、中缝由空格承担，也跳过。
    //
    // 「能产出字符」覆盖两类键：
    // - AlphabetKey：内置与 JSON 自定义布局的字母键（见 LayoutJsonUtils.createKeyDef）。
    // - MacroKey：当 tap 宏是**纯字符/文本输出**（仅 Text/Tap 步，无 layer_switch、edit、
    //   app_action、shortcut 等有状态/修饰步）时才算——这样"全 MacroKey 自定义布局"里只敲
    //   字符的键也能复制，而换层、退格之类的功能宏键不会被误复制到两侧。

    /**
     * 分体用的行集合：对满足条件的奇数字符行复制中间键。按 [source] 实例身份记忆并返回
     * 稳定实例，使 reloadLayout 的行缓存（按 `defs` 身份比较）仍能命中、不被破坏。
     * 整层都无需复制时直接返回 [source] 本身。
     */
    private fun splitRowsForLayout(source: List<List<KeyDef>>): List<List<KeyDef>> {
        val dupEnabled = AppPrefs.getInstance().keyboard.splitKeyboardDuplicateMiddleKey.getValue()
        if (splitRowsSource === source && splitRowsCached != null &&
            splitRowsDuplicateEnabled == dupEnabled
        ) {
            return splitRowsCached!!
        }
        val transformed = if (dupEnabled) source.map { duplicateMiddleKeyForSplit(it) } else source
        val result = if (transformed.indices.all { transformed[it] === source[it] }) source else transformed
        splitRowsSource = source
        splitRowsCached = result
        splitRowsDuplicateEnabled = dupEnabled
        return result
    }

    /** 奇数字符行：在几何中间键之后再插入它自身一份，使左右两半对称。否则原样返回。 */
    private fun duplicateMiddleKeyForSplit(row: List<KeyDef>): List<KeyDef> {
        if (row.size < 3 || row.size % 2 == 0) return row
        if (row.any { it is SpaceKey || it is MiniSpaceKey }) return row
        val midIndex = row.size / 2
        val mid = row[midIndex]
        if (!isSplitDuplicatableKey(mid)) return row
        return buildList(row.size + 1) {
            addAll(row.subList(0, midIndex + 1))
            add(mid)
            addAll(row.subList(midIndex + 1, row.size))
        }
    }

    /** 该键在分体时是否可安全地两侧各放一枚（产出同一字符、与位置/状态无关）。 */
    private fun isSplitDuplicatableKey(def: KeyDef): Boolean = when (def) {
        is AlphabetKey -> true
        is MacroKey -> def.tap.isPureCharacterOutput()
        else -> false
    }

    /** 宏是否为纯字符/文本输出：非空且所有步都是 Text 或 Tap（无换层/编辑/应用动作/快捷键）。 */
    private fun MacroAction.isPureCharacterOutput(): Boolean =
        steps.isNotEmpty() && steps.all { it is MacroStep.Text || it is MacroStep.Tap }

    private fun buildSplitRow(row: List<KeyDef>, keyViews: List<KeyView>): ConstraintLayout = constraintLayout {
        clipChildren = false
        clipToPadding = false
        keyViews.forEach { keyView ->
            keyView.onWaterRippleRequest = { view, _, _ ->
                val cx = (view.parent as? View)?.x.orZero() + view.x + view.width * 0.5f
                val cy = (view.parent as? View)?.y.orZero() + view.y + view.height * 0.5f
                val radius = waterRippleRadiusPx(view)
                keyboardWaterRippleView?.startRipple(cx, cy, waterRippleColor(), radius)
            }
        }
        if (row.isEmpty()) return@constraintLayout
        val gap = splitGapPercent()
        val normalizedWidths = resolveRowWidths(row)

        val flexCount = row.count { it.appearance.percentWidth <= 0f }
        val bridgeIndex = row.indexOfFirst { it is SpaceKey || it is MiniSpaceKey }
            .takeIf { it in 1 until row.lastIndex && flexCount <= 1 }
        if (bridgeIndex != null) {
            val minSideReach = 0.06f
            val bridgeMinWidth = (gap + minSideReach * 2f).coerceAtMost(0.75f)
            val bridgeMaxWidth = 0.75f
            val splitScale = (1f - gap).coerceIn(0.40f, 0.95f)

            val leftIndices = 0 until bridgeIndex
            val rightIndices = (bridgeIndex + 1)..keyViews.lastIndex

            val nonBridgeIndices = row.indices.filter { it != bridgeIndex }
            val preferredNormalWidths = nonBridgeIndices.mapNotNull { index ->
                val width = row[index].appearance.percentWidth
                if (width > 0f && row[index] !is SpaceKey && row[index] !is MiniSpaceKey) width else null
            }
            val fallbackWidths = nonBridgeIndices.mapNotNull { index ->
                val width = row[index].appearance.percentWidth
                if (width > 0f) width else null
            }
            val referenceNormalWidth = when {
                preferredNormalWidths.isNotEmpty() -> preferredNormalWidths.sum() / preferredNormalWidths.size
                fallbackWidths.isNotEmpty() -> fallbackWidths.sum() / fallbackWidths.size
                else -> 1f / row.size.coerceAtLeast(1)
            }

            val desiredNonBridgeWidths = mutableMapOf<Int, Float>()
            nonBridgeIndices.forEach { index ->
                val width = row[index].appearance.percentWidth
                val baseWidth = if (width > 0f) width else referenceNormalWidth
                desiredNonBridgeWidths[index] = baseWidth * splitScale
            }

            val assignedNonBridgeWidths = desiredNonBridgeWidths.toMutableMap()
            fun sumAssigned(): Float = assignedNonBridgeWidths.values.sum().coerceIn(0f, 1f)

            val flexIndices = nonBridgeIndices.filter { row[it].appearance.percentWidth <= 0f }
            val fixedIndices = nonBridgeIndices.filter { row[it].appearance.percentWidth > 0f }

            var overflowForMinBridge = (sumAssigned() + bridgeMinWidth - 1f).coerceAtLeast(0f)
            if (overflowForMinBridge > 0f && flexIndices.isNotEmpty()) {
                val flexSum = flexIndices.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (flexSum > 0f) {
                    val reduce = overflowForMinBridge.coerceAtMost(flexSum)
                    flexIndices.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current - reduce * (current / flexSum)
                    }
                    overflowForMinBridge = (sumAssigned() + bridgeMinWidth - 1f).coerceAtLeast(0f)
                }
            }
            if (overflowForMinBridge > 0f && fixedIndices.isNotEmpty()) {
                val fixedSum = fixedIndices.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (fixedSum > 0f) {
                    fixedIndices.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current - overflowForMinBridge * (current / fixedSum)
                    }
                }
            }

            var bridgeWidth = (1f - sumAssigned()).coerceAtLeast(bridgeMinWidth)
            if (bridgeWidth > bridgeMaxWidth) {
                val extra = bridgeWidth - bridgeMaxWidth
                val growTargets = if (flexIndices.isNotEmpty()) flexIndices else fixedIndices
                val targetSum = growTargets.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (targetSum > 0f) {
                    growTargets.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current + extra * (current / targetSum)
                    }
                }
                bridgeWidth = bridgeMaxWidth
            }

            keyViews.forEachIndexed { index, view ->
                add(view, lParams {
                    centerVertically()
                    matchConstraintPercentWidth = if (index == bridgeIndex) {
                        bridgeWidth
                    } else {
                        assignedNonBridgeWidths[index] ?: 0f
                    }
                    if (index == 0) {
                        leftOfParent()
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == keyViews.lastIndex) {
                        rightOfParent()
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                })
            }
            return@constraintLayout
        }

        val splitIndex = chooseSplitIndex(row, normalizedWidths)
        val sideCapacity = ((1f - gap) / 2f).coerceAtLeast(0.05f)

        val leftIndices = 0..splitIndex
        val rightIndices = (splitIndex + 1)..keyViews.lastIndex

        fun adjustedSideWidths(indices: IntRange): Map<Int, Float> {
            if (indices.isEmpty()) return emptyMap()
            val base = indices.associateWith { normalizedWidths[it] }
            val flexible = indices.filter { row[it].appearance.percentWidth <= 0f }
            val fixed = indices.filter { row[it].appearance.percentWidth > 0f }

            val fixedSum = fixed.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
            val flexSum = flexible.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
            val total = (fixedSum + flexSum).coerceAtLeast(0.0001f)

            // Side without flexible keys: just scale proportionally to side capacity.
            if (flexible.isEmpty()) {
                val ratio = sideCapacity / total
                return base.mapValues { (_, w) -> w * ratio }
            }

            // Keep at least part of side capacity for flexible keys (e.g. Space)
            // to avoid "space key too tiny" when gap is large.
            val minFlexShare = (0.30f + (gap - 0.20f) * 0.80f).coerceIn(0.30f, 0.55f)
            val targetFlex = maxOf(
                sideCapacity * minFlexShare,
                (sideCapacity - fixedSum).coerceAtLeast(0f)
            ).coerceAtMost(sideCapacity)
            val targetFixed = (sideCapacity - targetFlex).coerceAtLeast(0f)

            val fixedScale = if (fixedSum > 0f) targetFixed / fixedSum else 0f
            val result = mutableMapOf<Int, Float>()
            fixed.forEach { idx ->
                result[idx] = (base[idx] ?: 0f) * fixedScale
            }
            val flexScale = if (flexSum > 0f) targetFlex / flexSum else 0f
            flexible.forEach { idx ->
                result[idx] = (base[idx] ?: 0f) * flexScale
            }
            return result
        }

        val leftAdjusted = adjustedSideWidths(leftIndices)
        val rightAdjusted = adjustedSideWidths(rightIndices)

        val leftGuide = Guideline(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                orientation = LayoutParams.VERTICAL
                guidePercent = (0.5f - gap / 2f).coerceIn(0.2f, 0.8f)
            }
        }
        val rightGuide = Guideline(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                orientation = LayoutParams.VERTICAL
                guidePercent = (0.5f + gap / 2f).coerceIn(0.2f, 0.8f)
            }
        }
        addView(leftGuide)
        addView(rightGuide)

        keyViews.forEachIndexed { index, view ->
            add(view, lParams {
                centerVertically()
                val isLeftGroup = index <= splitIndex
                matchConstraintPercentWidth = if (isLeftGroup) {
                    leftAdjusted[index] ?: 0f
                } else {
                    rightAdjusted[index] ?: 0f
                }
                if (isLeftGroup) {
                    if (index == 0) {
                        leftOfParent()
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == splitIndex) {
                        rightToLeft = leftGuide.id
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                } else {
                    if (index == splitIndex + 1) {
                        leftToRight = rightGuide.id
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == keyViews.lastIndex) {
                        rightOfParent()
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                }
            })
        }
    }

    private fun buildRegularRow(row: List<KeyDef>, keyViews: List<KeyView>): ConstraintLayout = constraintLayout Row@{
        clipChildren = false
        clipToPadding = false
        keyViews.forEach { keyView ->
            keyView.onWaterRippleRequest = { view, _, _ ->
                val cx = (view.parent as? View)?.x.orZero() + view.x + view.width * 0.5f
                val cy = (view.parent as? View)?.y.orZero() + view.y + view.height * 0.5f
                val radius = waterRippleRadiusPx(view)
                keyboardWaterRippleView?.startRipple(cx, cy, waterRippleColor(), radius)
            }
        }
        var totalWidth = 0f
        keyViews.forEachIndexed { index, view ->
            add(view, lParams {
                centerVertically()
                if (index == 0) {
                    leftOfParent()
                    horizontalChainStyle = LayoutParams.CHAIN_PACKED
                } else {
                    leftToRightOf(keyViews[index - 1])
                }
                if (index == keyViews.size - 1) {
                    rightOfParent()
                    // for RTL
                    horizontalChainStyle = LayoutParams.CHAIN_PACKED
                } else {
                    rightToLeftOf(keyViews[index + 1])
                }
                val def = row[index]
                matchConstraintPercentWidth = def.appearance.percentWidth
            })
            row[index].appearance.percentWidth.let {
                // 0f means fill remaining space, thus does not need expanding
                totalWidth += if (it != 0f) it else 1f
            }
        }
        if (expandKeypressArea && totalWidth < 1f) {
            val free = (1f - totalWidth) / 2f
            keyViews.first().apply {
                updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth += free
                }
                layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
            }
            keyViews.last().apply {
                updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth += free
                }
                layoutMarginRight = free / (row.last().appearance.percentWidth + free)
            }
        }
    }

    private fun Float?.orZero(): Float = this ?: 0f

    private fun waterRippleRadiusPx(view: View): Float {
        val base = minOf(width, height).takeIf { it > 0 }?.toFloat() ?: dp(120).toFloat()
        val minFromKey = max(view.width, view.height) * 1.0f
        return max(base * 0.20f, minFromKey)
    }

    private fun waterRippleColor(): Int {
        cachedWaterRippleColor?.let { return it }
        theme.waterRippleColor?.let {
            cachedWaterRippleColor = it
            return it
        }
        val shadow = theme.keyShadowColor
        val background = ColorUtils.setAlphaComponent(theme.keyboardColor, 255)
        val contrast = ColorUtils.calculateContrast(shadow, background)
        val computed = if (contrast < 1.35) {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.72f)
        } else {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.28f)
        }
        cachedWaterRippleColor = computed
        return computed
    }

    private var currentTextScale = 1.0f

    fun setTextScale(scale: Float) {
        val scaleChanged = currentTextScale != scale
        currentTextScale = scale
        keyRows.forEach { row ->
            row.children.forEach { child ->
                (child as? KeyView)?.let { keyView ->
                    keyView.setTextScale(scale)
                    if (!scaleChanged) {
                        keyView.requestLayout()
                        keyView.invalidate()
                    }
                }
            }
        }
    }

    fun reapplyTextScale() {
        setTextScale(currentTextScale)
    }

    fun refreshStyle() {
        reloadLayout()
        reapplyTextScale()
        onStyleRefreshFinished()
        requestLayout()
        updateBounds()
    }

    /**
     * Drop all cached row sets. Call before a reload that must rebuild rows even though
     * the layout/style signature did not change (e.g. font set refresh whose flag was
     * already consumed).
     */
    fun clearReusableRowsCache() {
        reusableRowsCache.clear()
        lastRowsSignature = null
        splitRowsSource = null
        splitRowsCached = null
    }

    /**
     * Lightweight style refresh, updates colors without rebuilding layout
     */
    fun refreshStyleLight() {
        onStyleRefreshFinished()
        invalidate()
    }

    fun refreshIconTheme() {
        if (!::keyRows.isInitialized) return
        keyRows.forEach { row ->
            row.children.forEach { child ->
                (child as? KeyView)?.let { keyView ->
                    when (keyView) {
                        is ImageAltTextKeyView -> keyView.reapplyIconThemeOverride()
                        is ImageTextKeyView -> keyView.reapplyIconThemeOverride()
                        is ImageKeyView -> keyView.reapplyIconThemeOverride()
                    }
                }
            }
        }
    }

    fun setHorizontalGapScale(scale: Float) {
        val target = scale.coerceIn(0.5f, 1f)
        if (kotlin.math.abs(horizontalGapScale - target) < 0.01f) return
        horizontalGapScale = target
        refreshStyle()
    }

    protected open fun onStyleRefreshFinished() {
        // do nothing by default
    }

    /**
     * Update theme without rebuilding views.
     * Iterates all KeyViews and updates their theme properties.
     */
    fun updateTheme(newTheme: Theme) {
        theme = newTheme
        cachedWaterRippleColor = null

        if (::keyRows.isInitialized) {
            keyRows.forEach { row ->
                row.children.forEach { child ->
                    (child as? KeyView)?.updateTheme(newTheme)
                }
            }
        }

        onThemeUpdate(newTheme)
    }

    /**
     * Called after theme update for keyboard-specific updates
     */
    protected open fun onThemeUpdate(newTheme: Theme) {
        // default: no-op
    }

    private fun createKeyView(
        def: KeyDef,
        registerComposeAware: Boolean = true,
        appearanceOverride: KeyDef.Appearance? = null
    ): KeyView {
        val (activeDef, resolvedAppearance) = resolveComposeActiveDef(def)
        val activeAppearance = appearanceOverride ?: resolvedAppearance
        return when (activeAppearance) {
            is KeyDef.Appearance.ToolbarText -> ToolbarTextKeyView(context, theme, activeAppearance, horizontalGapScale)
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, activeAppearance, horizontalGapScale)
            is KeyDef.Appearance.ImageAltText -> ImageAltTextKeyView(context, theme, activeAppearance, horizontalGapScale, def.iconSlot)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, activeAppearance, horizontalGapScale, def.iconSlot)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, activeAppearance, horizontalGapScale)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, activeAppearance, horizontalGapScale, def.iconSlot)
        }.apply {
            setTextScale(currentTextScale)
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey || def is MiniSpaceKey) {
                // 仅 SpaceKey 支持在布局编辑器里配置划动动作；MiniSpaceKey 保持纯光标语义。
                val swipeAction = (def as? SpaceKey)?.swipe
                registerSpaceKeyEntry(this, swipeAction)
                swipeEnabled = spaceSwipeEnabledFor(swipeAction)
                swipeRepeatEnabled = true
                applySpaceSwipeThresholds(this)
                // Track the locked swipe direction to avoid conflicting gestures
                var swipeDirectionLocked: SwipeAxis? = null
                onGestureListener = OnGestureListener { view, event ->
                    // 每次事件都重读开关：设置里切换后无需重建键盘即可生效，且复用的
                    // 缓存行也不会拿着旧模式的判断继续跑。
                    val cursorMode = spaceSwipeMoveCursor.getValue()
                    when (event.type) {
                        GestureType.Move -> {
                            if (!cursorMode) {
                                // 划动模式：位移一律不消费，等抬手时按总位移方向判定一次。
                                // 这里返回 false 也保证 gestureConsumed 不被置位，抬手时
                                // 才能看到真实的 totalX/totalY。
                                false
                            } else {
                                val countX = event.countX
                                val countY = event.countY

                                // Lock direction on first swipe
                                if (swipeDirectionLocked == null && (countX != 0 || countY != 0)) {
                                    swipeDirectionLocked = if (kotlin.math.abs(countX) >= kotlin.math.abs(countY)) {
                                        SwipeAxis.X
                                    } else {
                                        SwipeAxis.Y
                                    }
                                }

                                val handled = when (swipeDirectionLocked) {
                                    SwipeAxis.X -> {
                                        if (countX != 0) {
                                            val sym =
                                                if (countX > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                            val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                            repeat(countX.absoluteValue) {
                                                onAction(action)
                                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    SwipeAxis.Y -> {
                                        if (countY != 0) {
                                            val sym =
                                                if (countY > 0) FcitxKeyMapping.FcitxKey_Down else FcitxKeyMapping.FcitxKey_Up
                                            val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                            repeat(countY.absoluteValue) {
                                                onAction(action)
                                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    null -> false
                                }
                                handled
                            }
                        }
                        GestureType.Up -> {
                            // Reset direction lock on finger up
                            swipeDirectionLocked = null
                            onAction(KeyAction.VoiceInputHoldEnd)
                            // 划动模式：抬手时按总位移方向触发用户配置的动作，语义与其它
                            // 键的 Behavior.Swipe 一致（一次划动只触发一次）。光标模式不触发。
                            if (
                                !cursorMode &&
                                swipeAction != null &&
                                !event.consumed &&
                                shouldTriggerSymbolBySwipe(view, event.totalY)
                            ) {
                                onAction(swipeAction)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = if (def.swipe != null) inputSwipeThreshold else disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Down -> {
                            // 转发"退格键被按下"给退格边界保护：长按删空抬手后极短时间内
                            // 又开始的新按压是抬手抖动/回弹，不应删掉正文（见
                            // BackspaceBoundaryGuard.onBackspacePressStarted）。
                            getService()?.backspaceBoundaryGuard?.onBackspacePressStarted()
                            false
                        }
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            dismissBackspaceClearPopup()
                            // 本次按住期间是否刚执行过清空。用完立即复位：移植版本只在
                            // 下一次长按弹出提示条时才复位，标志会一直留着，导致此后
                            // 普通点击退格的抬手也不再发 DeleteSelectionAction ——
                            // 那正是退格保护序列（BackspaceBoundaryGuard）的结束信号。
                            val clearedThisPress = backspaceClearTriggered
                            backspaceClearTriggered = false
                            if (
                                def.swipe != null &&
                                kotlin.math.abs(event.totalY) > kotlin.math.abs(event.totalX) &&
                                shouldTriggerSymbolBySwipe(view, event.totalY)
                            ) {
                                onAction(def.swipe)
                                true
                            } else {
                                // 清空已经执行过时不再补一次滑删：否则抬手会被当成
                                // 一次普通滑动退格，多删一个字/一段选区。
                                if (!clearedThisPress) {
                                    onAction(KeyAction.DeleteSelectionAction(event.totalX))
                                }
                                false
                            }
                        }
                    }
                }
            }
            val baseline = GestureBaseline(
                swipeEnabled = swipeEnabled,
                swipeRepeatEnabled = swipeRepeatEnabled,
                swipeThresholdX = swipeThresholdX,
                swipeThresholdY = swipeThresholdY,
                onGestureListener = onGestureListener
            )
            // Record the pre-wrapping configuration before applyBehaviorPopupBindings installs
            // its wrapper, so a later row reuse can recover the real baseline.
            gestureBaselines[this] = baseline
            applyAppearance(this, activeAppearance)
            // 空格键的「长按重复输入」在 applyBehaviorPopupBindings 内部按视图重绑
            // （它会先清空 repeat 回调，所以由它自己收尾最稳），这里不再单独调用。
            applyBehaviorPopupBindings(this, baseline, activeDef.behaviors, activeDef.popup)
            if (def is BackspaceKey) {
                // 长按退格进入连删时弹出"上滑清空"提示条；手指滑进提示条区域即清空。
                // 必须在 applyBehaviorPopupBindings 之后绑定：它会先把这两个回调清空。
                this.onRepeatStartListener = { v ->
                    showBackspaceClearPopup(v)
                }
                this.onRepeatMoveListener = { v, x, y ->
                    if (!backspaceClearTriggered && checkBackspaceClearPopupHit(v, x, y)) {
                        backspaceClearTriggered = true
                        InputFeedbacks.hapticFeedback(v, true)
                        if (composing) {
                            // 合成中：上滑只清掉正在打的码（发 Escape，等同引擎取消当前
                            // 编码区），不动已上屏正文；非合成时才清空整个输入框。
                            onAction(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Escape)))
                        } else {
                            executeClearAll()
                        }
                        dismissBackspaceClearPopup()
                    }
                }
            }
            if (registerComposeAware && def.composeOverride != null) {
                composeAwareKeys += ComposeAwareKey(def, this, baseline)
            }
        }
    }

    open fun onCompositionStateChanged(composing: Boolean) {
        if (this.composing == composing) return
        this.composing = composing
        // The attached rows are about to be mutated in place (compose-aware views get
        // recreated). Evict the cache entry holding them so a later reload can never
        // reuse rows that were modified under a different composition state.
        lastRowsSignature?.let { reusableRowsCache.remove(it) }
        lastRowsSignature = null
        data class PendingUpdate(
            val item: ComposeAwareKey,
            val activeDef: KeyDef,
            val activeAppearance: KeyDef.Appearance,
            val needsRecreate: Boolean
        )

        val updates = composeAwareKeys.map { item ->
            val (activeDef, activeAppearance) = resolveComposeActiveDef(item.baseDef)
            val needsRecreate = shouldRecreateComposeAwareView(item.keyView.def, item.keyView, activeAppearance)
            PendingUpdate(item, activeDef, activeAppearance, needsRecreate)
        }

        // First, rebind behaviors on existing views so taps during transition already use new actions.
        updates.forEach { u ->
            applyBehaviorPopupBindings(u.item.keyView, u.item.baseline, u.activeDef.behaviors, u.activeDef.popup)
        }

        // Never swap out a view that currently has a finger on it: the old instance owns the
        // in-flight gesture (repeat job, long-press job, popup), and replacing it mid-press
        // aborts backspace auto-repeat and leaves the popup orphaned. Defer those to the next
        // UP/CANCEL, which calls flushDeferredComposeRecreations().
        val recreateList = updates.filter { it.needsRecreate && !isKeyHeld(it.item.keyView) }
        val deferred = updates.filter { it.needsRecreate && isKeyHeld(it.item.keyView) }
        if (recreateList.isNotEmpty()) {
            val parents = recreateList
                .mapNotNull { it.item.keyView.parent as? ViewGroup }
                .distinct()
            parents.forEach { it.suppressLayout(true) }
            try {
                recreateList.forEach { u ->
                    recreateComposeAwareKeyView(u.item, u.activeDef, u.activeAppearance)
                }
            } finally {
                parents.forEach { it.suppressLayout(false) }
            }
            // Update occluders so water ripple masking uses the recreated views
            keyboardWaterRippleView?.setOccluders(
                keyRows.flatMap { row -> row.children.mapNotNull { it as? KeyView }.toList() }
            )
        }

        updates.forEach { u ->
            applyAppearance(u.item.keyView, u.activeAppearance)
            applyBehaviorPopupBindings(u.item.keyView, u.item.baseline, u.activeDef.behaviors, u.activeDef.popup)
        }

        // Behaviors were already rebound above, so the held key acts on the new composition
        // state; only the view swap waits.
        pendingComposeRecreations = deferred.map { it.item }.takeIf { it.isNotEmpty() }
    }

    /**
     * True while [view] is under a finger.
     *
     * Checks both the custom dispatch map (populated only under the vivo keypress workaround)
     * and [View.isPressed], which the ordinary touch path maintains.
     */
    private fun isKeyHeld(view: KeyView): Boolean =
        touchTargets.values.any { it.view === view } || view.isPressed

    /**
     * Recreate compose-aware views whose swap was postponed because the key was being pressed
     * (see [onCompositionStateChanged]).
     */
    private fun flushDeferredComposeRecreations() {
        val pending = pendingComposeRecreations ?: return
        // Another finger may still be on one of them (this runs per pointer-up); keep waiting
        // for those rather than swapping a view out mid-gesture again. Views that have left the
        // tree are dropped rather than kept waiting: recreateComposeAwareKeyView needs a parent
        // anyway, and an isPressed left stuck on a detached view would queue it forever.
        val live = pending.filter { it.keyView.parent != null }
        val (ready, stillHeld) = live.partition { !isKeyHeld(it.keyView) }
        pendingComposeRecreations = stillHeld.takeIf { it.isNotEmpty() }
        if (ready.isEmpty()) return
        val parents = ready.mapNotNull { it.keyView.parent as? ViewGroup }.distinct()
        parents.forEach { it.suppressLayout(true) }
        try {
            ready.forEach { item ->
                val (activeDef, activeAppearance) = resolveComposeActiveDef(item.baseDef)
                if (shouldRecreateComposeAwareView(item.keyView.def, item.keyView, activeAppearance)) {
                    recreateComposeAwareKeyView(item, activeDef, activeAppearance)
                }
            }
        } finally {
            parents.forEach { it.suppressLayout(false) }
        }
        if (::keyRows.isInitialized) {
            keyboardWaterRippleView?.setOccluders(
                keyRows.flatMap { row -> row.children.mapNotNull { it as? KeyView }.toList() }
            )
        }
    }

    private fun shouldRecreateComposeAwareView(
        oldAppearance: KeyDef.Appearance,
        currentView: KeyView,
        newAppearance: KeyDef.Appearance
    ): Boolean {
        if (!isAppearanceCompatible(currentView, newAppearance)) return true

        val textMetricsChanged = when {
            oldAppearance is KeyDef.Appearance.Text && newAppearance is KeyDef.Appearance.Text -> {
                oldAppearance.textStyle != newAppearance.textStyle ||
                    oldAppearance.textSize != newAppearance.textSize
            }
            oldAppearance is KeyDef.Appearance.Text || newAppearance is KeyDef.Appearance.Text -> true
            else -> false
        }

        // Recreate only when style-affecting attributes changed. For text/action-only changes,
        // keep the same view instance to avoid touch race and expensive hierarchy churn.
        return oldAppearance.variant != newAppearance.variant ||
            oldAppearance.border != newAppearance.border ||
            oldAppearance.margin != newAppearance.margin ||
            oldAppearance.percentWidth != newAppearance.percentWidth ||
            oldAppearance.viewId != newAppearance.viewId ||
            textMetricsChanged ||
            oldAppearance.textColor != newAppearance.textColor ||
            oldAppearance.textColorMonet != newAppearance.textColorMonet ||
            oldAppearance.altTextColor != newAppearance.altTextColor ||
            oldAppearance.altTextColorMonet != newAppearance.altTextColorMonet ||
            oldAppearance.backgroundColor != newAppearance.backgroundColor ||
            oldAppearance.backgroundColorMonet != newAppearance.backgroundColorMonet ||
            oldAppearance.shadowColor != newAppearance.shadowColor ||
            oldAppearance.shadowColorMonet != newAppearance.shadowColorMonet
    }

    private fun isAppearanceCompatible(view: KeyView, appearance: KeyDef.Appearance): Boolean {
        return when (view) {
            is ToolbarTextKeyView -> appearance is KeyDef.Appearance.ToolbarText
            is AltTextKeyView -> appearance is KeyDef.Appearance.AltText
            is ImageAltTextKeyView -> appearance is KeyDef.Appearance.ImageAltText
            is ImageTextKeyView -> appearance is KeyDef.Appearance.ImageText
            is ImageKeyView -> appearance is KeyDef.Appearance.Image
            is TextKeyView -> appearance is KeyDef.Appearance.Text &&
                    appearance !is KeyDef.Appearance.AltText &&
                    appearance !is KeyDef.Appearance.ImageText &&
                    appearance !is KeyDef.Appearance.ToolbarText
            else -> false
        }
    }

    private fun recreateComposeAwareKeyView(
        item: ComposeAwareKey,
        def: KeyDef,
        appearance: KeyDef.Appearance
    ) {
        val oldView = item.keyView
        val parent = oldView.parent as? ConstraintLayout ?: return
        val index = parent.indexOfChild(oldView)
        if (index < 0) return
        val oldLayoutParams = oldView.layoutParams
        val copiedLayoutParams = when (oldLayoutParams) {
            is ConstraintLayout.LayoutParams -> ConstraintLayout.LayoutParams(oldLayoutParams)
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(oldLayoutParams)
            else -> oldLayoutParams
        }
        val newView = createKeyView(def, registerComposeAware = false, appearanceOverride = appearance)
        applyConfiguredFonts(newView)
        // Keep the same identity so sibling constraints (leftToRight/rightToLeft) remain valid.
        newView.id = oldView.id
        newView.tag = oldView.tag
        // Preserve water ripple request callback on recreated view
        newView.onWaterRippleRequest = { view, _, _ ->
            val cx = (view.parent as? View)?.x.orZero() + view.x + view.width * 0.5f
            val cy = (view.parent as? View)?.y.orZero() + view.y + view.height * 0.5f
            val radius = waterRippleRadiusPx(view)
            keyboardWaterRippleView?.startRipple(cx, cy, waterRippleColor(), radius)
        }
        parent.removeViewAt(index)
        if (copiedLayoutParams != null) {
            parent.addView(newView, index, copiedLayoutParams)
        } else {
            parent.addView(newView, index)
        }
        item.keyView = newView
        // createKeyView 已把 newView 登记进 spaceKeys（若它是空格键）；旧实例必须出表，
        // 否则设置监听器会继续改脱离视图树的旧视图，且每次重绑都留一条记录。
        removeSpaceKeyEntry(oldView)
        // createKeyView already recorded the pre-wrapping baseline for newView; prefer it over
        // reading the view back, which by now may hold a wrapped gesture listener.
        item.baseline = gestureBaselines[newView] ?: GestureBaseline(
            swipeEnabled = newView.swipeEnabled,
            swipeRepeatEnabled = newView.swipeRepeatEnabled,
            swipeThresholdX = newView.swipeThresholdX,
            swipeThresholdY = newView.swipeThresholdY,
            onGestureListener = newView.onGestureListener
        )
        applyAppearance(newView, appearance)
    }

    private fun applyConfiguredFonts(keyView: KeyView) {
        // Check AltTextKeyView before TextKeyView since AltTextKeyView is a subclass of TextKeyView.
        when (keyView) {
            // ToolbarTextKeyView keeps the canonical fixed dp size and skips text-scale
            // settings (fixed height toolbar rows must never be shrunk to invisible), but its
            // label still follows the user's configured key typeface like every other key.
            is ToolbarTextKeyView -> keyView.label.setFontTypeFace("key_main_font")
            is AltTextKeyView -> {
                keyView.mainText.setFontTypeFace("key_main_font")
                keyView.altText.setFontTypeFace("key_alt_font")
            }
            is ImageAltTextKeyView -> keyView.altText.setFontTypeFace("key_alt_font")
            is TextKeyView -> keyView.mainText.setFontTypeFace("key_main_font")
        }
    }

    private fun resolveComposeActiveDef(baseDef: KeyDef): Pair<KeyDef, KeyDef.Appearance> {
        if (!composing) return baseDef to baseDef.appearance
        val overrideDef = baseDef.composeOverride ?: return baseDef to baseDef.appearance
        val appearance = if (overrideDef.independentColor) {
            overrideDef.appearance.withIdentityFrom(baseDef.appearance)
        } else {
            overrideDef.appearance.withColorsFrom(baseDef.appearance)
        }.withTextMetricsFrom(baseDef.appearance)
        return overrideDef to appearance
    }

    private fun KeyDef.Appearance.withTextMetricsFrom(source: KeyDef.Appearance): KeyDef.Appearance {
        val sourceText = source as? KeyDef.Appearance.Text ?: return this
        return when (this) {
            // ToolbarText must never be rebuilt as plain Text: that would drop it back into
            // the text-scale pipeline and can shrink toolbar digits to invisibility.
            is KeyDef.Appearance.ToolbarText -> this
            is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
                displayText = displayText,
                altText = altText,
                character = character,
                textSize = sourceText.textSize,
                textStyle = sourceText.textStyle,
                percentWidth = percentWidth,
                variant = variant,
                border = border,
                margin = margin,
                viewId = viewId,
                textColor = textColor,
                textColorMonet = textColorMonet,
                altTextColor = altTextColor,
                altTextColorMonet = altTextColorMonet,
                backgroundColor = backgroundColor,
                backgroundColorMonet = backgroundColorMonet,
                shadowColor = shadowColor,
                shadowColorMonet = shadowColorMonet,
                keepDisplayTextCase = keepDisplayTextCase
            )
            is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
                displayText = displayText,
                textSize = sourceText.textSize,
                textStyle = sourceText.textStyle,
                src = src,
                percentWidth = percentWidth,
                variant = variant,
                border = border,
                margin = margin,
                viewId = viewId,
                textColor = textColor,
                textColorMonet = textColorMonet,
                altTextColor = altTextColor,
                altTextColorMonet = altTextColorMonet,
                backgroundColor = backgroundColor,
                backgroundColorMonet = backgroundColorMonet,
                shadowColor = shadowColor,
                shadowColorMonet = shadowColorMonet
            )
            is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
                displayText = displayText,
                textSize = sourceText.textSize,
                textStyle = sourceText.textStyle,
                percentWidth = percentWidth,
                variant = variant,
                border = border,
                margin = margin,
                viewId = viewId,
                soundEffect = soundEffect,
                textColor = textColor,
                textColorMonet = textColorMonet,
                altTextColor = altTextColor,
                altTextColorMonet = altTextColorMonet,
                backgroundColor = backgroundColor,
                backgroundColorMonet = backgroundColorMonet,
                shadowColor = shadowColor,
                shadowColorMonet = shadowColorMonet
            )
            else -> this
        }
    }

    private fun KeyDef.Appearance.withIdentityFrom(source: KeyDef.Appearance): KeyDef.Appearance = when (this) {
        // See withTextMetricsFrom: never flatten ToolbarText into plain Text.
        is KeyDef.Appearance.ToolbarText -> this
        is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
            displayText = displayText,
            altText = altText,
            character = character,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet,
            keepDisplayTextCase = keepDisplayTextCase
        )
        is KeyDef.Appearance.ImageAltText -> KeyDef.Appearance.ImageAltText(
            src = src,
            altText = altText,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
        is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
        is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
        is KeyDef.Appearance.Image -> KeyDef.Appearance.Image(
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    }

    private fun KeyDef.Appearance.withColorsFrom(source: KeyDef.Appearance): KeyDef.Appearance = when (this) {
        // See withTextMetricsFrom: never flatten ToolbarText into plain Text.
        is KeyDef.Appearance.ToolbarText -> this
        is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
            displayText = displayText,
            altText = altText,
            character = character,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet,
            keepDisplayTextCase = keepDisplayTextCase
        )
        is KeyDef.Appearance.ImageAltText -> KeyDef.Appearance.ImageAltText(
            src = src,
            altText = altText,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
        is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
        is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
        is KeyDef.Appearance.Image -> KeyDef.Appearance.Image(
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
    }

    private fun applyAppearance(view: KeyView, appearance: KeyDef.Appearance) {
        when (view) {
            is AltTextKeyView -> if (appearance is KeyDef.Appearance.AltText) {
                view.mainText.text = appearance.displayText
                view.altText.text = appearance.altText
                view.refreshAltTextLayout()
            }
            is ImageAltTextKeyView -> if (appearance is KeyDef.Appearance.ImageAltText) {
                view.img.setImageResource(appearance.src)
                view.reapplyIconThemeOverride()
                view.altText.text = appearance.altText
                view.refreshAltTextLayout()
            }
            is ImageTextKeyView -> if (appearance is KeyDef.Appearance.ImageText) {
                view.mainText.text = appearance.displayText
                view.img.setImageResource(appearance.src)
                view.reapplyIconThemeOverride()
            }
            is ToolbarTextKeyView -> if (appearance is KeyDef.Appearance.ToolbarText) {
                view.label.text = appearance.displayText
            }
            is TextKeyView -> if (appearance is KeyDef.Appearance.Text) {
                view.mainText.text = appearance.displayText
            }
            is ImageKeyView -> if (appearance is KeyDef.Appearance.Image) {
                view.img.setImageResource(appearance.src)
                view.reapplyIconThemeOverride()
            }
        }
    }

    private fun applyBehaviorPopupBindings(
        view: KeyView,
        baseline: GestureBaseline,
        behaviors: Set<KeyDef.Behavior>,
        popup: Array<KeyDef.Popup>?
    ) {
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        view.repeatEnabled = false
        view.onRepeatListener = null
        view.onRepeatStartListener = null
        view.onRepeatMoveListener = null
        view.doubleTapEnabled = false
        view.onDoubleTapListener = null
        view.swipeEnabled = baseline.swipeEnabled
        view.swipeRepeatEnabled = baseline.swipeRepeatEnabled
        view.swipeThresholdX = baseline.swipeThresholdX
        view.swipeThresholdY = baseline.swipeThresholdY
        view.onGestureListener = baseline.onGestureListener

        val interactive = behaviors.isNotEmpty() || !popup.isNullOrEmpty()
        view.isEnabled = interactive
        view.isClickable = interactive
        if (!interactive) return

        // 空格键的「长按重复输入空格」由设置驱动，不写在 KeyDef 的 behaviors 里
        // （那样缓存行会带着旧值，改设置不生效）。方法开头刚清空过 repeat 回调，
        // 这里统一重绑：创建、compose 重绑、行复用之外的路径都经过本方法。
        refreshSpaceRepeatBinding(view)

        var hasLongPressBehavior = false
        behaviors.forEach {
            when (it) {
                is KeyDef.Behavior.Press -> {
                    view.setOnClickListener { _ ->
                        onAction(it.action)
                    }
                }
                is KeyDef.Behavior.LongPress -> {
                    hasLongPressBehavior = true
                    view.setOnLongClickListener { _ ->
                        onAction(it.action)
                        true
                    }
                }
                is KeyDef.Behavior.Repeat -> {
                    view.repeatEnabled = true
                    view.onRepeatListener = { currentView ->
                        onAction(it.action)
                        // 退格保护段内被吞掉的重复退格不再触发按键反馈：长按删空编码进入
                        // 保护后，后续重复退格不删正文，也不应继续震动（见
                        // isBackspaceRepeatConsumed 与 BackspaceBoundaryGuard）。
                        if (hapticOnRepeat && !isBackspaceRepeatConsumed(it.action)) {
                            InputFeedbacks.hapticFeedback(currentView)
                        }
                    }
                }
                is KeyDef.Behavior.Swipe -> {
                    view.swipeEnabled = true
                    view.swipeThresholdX = disabledSwipeThreshold
                    view.swipeThresholdY = inputSwipeThreshold
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        when (event.type) {
                            GestureType.Up -> {
                                if (!event.consumed && shouldTriggerSymbolBySwipe(currentView, event.totalY)) {
                                    onAction(it.action)
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        } || oldOnGestureListener.onGesture(currentView, event)
                    }
                }
                is KeyDef.Behavior.DoubleTap -> {
                    view.doubleTapEnabled = true
                    view.onDoubleTapListener = { _ ->
                        onAction(it.action)
                    }
                }
            }
        }
        val hasLongPressKeyboard = popup?.any { it is KeyDef.Popup.LongPressKeyboard } == true
        popup?.forEach {
            when (it) {
                is KeyDef.Popup.Menu -> {
                    if (!hasLongPressKeyboard && !hasLongPressBehavior) {
                        view.setOnLongClickListener { currentView ->
                            currentView as KeyView
                            dismissAllPopups()
                            onPopupAction(PopupAction.ShowMenuAction(currentView.id, it, currentView.bounds))
                            false
                        }
                        val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                        view.swipeEnabled = true
                        view.onGestureListener = OnGestureListener { currentView, event ->
                            currentView as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(currentView.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(currentView.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(currentView, event)
                        }
                    }
                }
                is KeyDef.Popup.LongPressKeyboard -> {
                    view.setOnLongClickListener { currentView ->
                        currentView as KeyView
                        dismissAllPopups()
                        onPopupAction(PopupAction.ShowLongPressKeyboardAction(currentView.id, it, currentView.bounds))
                        false
                    }
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.swipeEnabled = true
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        currentView as KeyView
                        when (event.type) {
                            GestureType.Move -> {
                                onPopupChangeFocus(currentView.id, event.x, event.y)
                            }
                            GestureType.Up -> {
                                onPopupTrigger(currentView.id)
                            }
                            else -> false
                        } || oldOnGestureListener.onGesture(currentView, event)
                    }
                }
                is KeyDef.Popup.Keyboard -> {
                    if (!hasLongPressKeyboard && !hasLongPressBehavior) {
                        view.setOnLongClickListener { currentView ->
                            currentView as KeyView
                            dismissAllPopups()
                            onPopupAction(PopupAction.ShowKeyboardAction(currentView.id, it, currentView.bounds))
                            false
                        }
                        val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                        view.swipeEnabled = true
                        view.onGestureListener = OnGestureListener { currentView, event ->
                            currentView as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(currentView.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(currentView.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(currentView, event)
                        }
                    }
                }
                is KeyDef.Popup.AltPreview -> {
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        currentView as KeyView
                        if (popupOnKeyPress) {
                            when (event.type) {
                                GestureType.Down -> onPopupAction(
                                    PopupAction.PreviewAction(currentView.id, it.content, currentView.bounds)
                                )
                                GestureType.Move -> {
                                    val triggered = shouldTriggerSymbolBySwipe(currentView, event.totalY)
                                    val text = if (triggered) it.alternative else it.content
                                    onPopupAction(
                                        PopupAction.PreviewUpdateAction(currentView.id, text)
                                    )
                                }
                                GestureType.Up -> {
                                    onPopupAction(PopupAction.DismissAction(currentView.id))
                                    dismissAllPopups()
                                }
                            }
                        }
                        oldOnGestureListener.onGesture(currentView, event)
                    }
                }
                is KeyDef.Popup.Preview -> {
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        currentView as KeyView
                        if (popupOnKeyPress) {
                            when (event.type) {
                                GestureType.Down -> onPopupAction(
                                    PopupAction.PreviewAction(currentView.id, it.content, currentView.bounds)
                                )
                                GestureType.Up -> {
                                    onPopupAction(PopupAction.DismissAction(currentView.id))
                                    dismissAllPopups()
                                }
                                else -> {}
                            }
                        }
                        oldOnGestureListener.onGesture(currentView, event)
                    }
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Re-evaluate split keyboard when view width changes.
        // 推迟到本趟布局结束后再 reload：onSizeChanged 期间改视图树会让新行漏测量而塌成一行
        // （浮动键盘来回拖宽反复越过阈值时必现）。post 保证在稳定态重建，随后的 requestLayout
        // 触发一趟干净布局；pendingSplitReload 合并连续回调，拖动期间不重复排队。
        if (w != oldw) {
            val shouldSplit = splitKeyboardManager.shouldUseSplitKeyboard(w)
            if (shouldSplit != lastSplitLandscapeState && !pendingSplitReload) {
                pendingSplitReload = true
                post {
                    pendingSplitReload = false
                    if (!isAttachedToWindow) return@post
                    // 拖动可能已把宽度改到别处，按最新宽度重判，避免基于过期决定重建。
                    val latestShouldSplit = splitKeyboardManager.shouldUseSplitKeyboard(width)
                    if (latestShouldSplit != lastSplitLandscapeState) {
                        reloadLayout()
                        reapplyTextScale()
                        onStyleRefreshFinished()
                        requestLayout()
                    }
                }
            }
        }

        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
    }

    fun updateBounds() {
        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
        if (::keyRows.isInitialized) {
            keyRows.forEach { row ->
                row.children.forEach {
                    (it as? KeyView)?.invalidateCachedBounds()
                }
            }
        }
    }

    fun keyBoundsInKeyboard(): List<Rect> {
        if (!::keyRows.isInitialized) return emptyList()
        updateBounds()
        val result = ArrayList<Rect>(48)
        keyRows.forEach { row ->
            row.children.forEach { child ->
                val key = child as? KeyView ?: return@forEach
                key.updateBounds()
                val rect = Rect(key.bounds)
                rect.offset(-bounds.left, -bounds.top)
                // Exclude key outer margins so only actual key area gets blur
                // and keep inter-key gaps/side-bottom paddings unblurred.
                if (key.hMargin > 0 || key.vMargin > 0) {
                    rect.inset(key.hMargin, key.vMargin)
                }
                if (rect.width() <= 0 || rect.height() <= 0) return@forEach
                result.add(rect)
            }
        }
        return result
    }

    private fun findTargetChild(x: Float, y: Float): View? {
        updateBounds()
        val x1 = x.roundToInt() + bounds.left
        val y1 = y.roundToInt() + bounds.top
        return keyRows.asSequence().flatMap { it.children }.find {
            if (it !is KeyView) false else it.isEnabled && it.bounds.contains(x1, y1)
        }
    }

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [TouchTarget]
     * for custom touch event dispatching
     */
    private fun releaseAllTouchTargets() {
        touchTargets.forEach {
            val keyView = it.value.view
            keyView.cancelGestures()
            onPopupAction(PopupAction.DismissAction(keyView.id))
        }
        touchTargets.clear()
        flushDeferredComposeRecreations()
    }

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? {
        updateBounds()
        val x = event.getX(pointerIndex).roundToInt() + bounds.left
        val y = event.getY(pointerIndex).roundToInt() + bounds.top
        val key = keyRows.asSequence()
            .flatMap { it.children }
            .filterIsInstance<KeyView>()
            .find { it.isEnabled && it.bounds.contains(x, y) }
            ?: return null
        return TouchTarget(key)
    }

    private fun dispatchMotionEventToTarget(
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
        target: TouchTarget
    ) {
        val childLocationInWindow = intArrayOf(0, 0).also {
            target.view.getLocationInWindow(it)
        }
        val childX = event.getX(pointerIndex) + bounds.left - childLocationInWindow[0]
        val childY = event.getY(pointerIndex) + bounds.top - childLocationInWindow[1]
        val e = MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
        try {
            target.view.dispatchTouchEvent(e)
        } finally {
            // obtain() takes an event from a shared pool; return it. With the vivo
            // workaround this runs once per pressed finger per ACTION_MOVE.
            e.recycle()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept only key presses for the workaround. Auxiliary bar RecyclerViews
        // must receive their own touch stream for scrolling and clicks.
        return if (
            vivoKeypressWorkaround &&
            ev.actionMasked == MotionEvent.ACTION_DOWN &&
            findTargetChild(ev.x, ev.y) != null
        ) {
            true
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        // The ordinary (non-vivo) path never populates touchTargets, so this is where a
        // deferred compose recreation gets its chance to run once the gesture is over.
        // POINTER_UP counts too: motion event splitting is on, so with two fingers down the
        // first release arrives here as ACTION_POINTER_UP and only the last one as ACTION_UP —
        // the key released first would otherwise wait for its neighbour.
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> flushDeferredComposeRecreations()
        }
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (vivoKeypressWorkaround) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    releaseAllTouchTargets()
                    val pid = event.getPointerId(0)
                    val target = findTouchTarget(event, 0) ?: return false
                    touchTargets[pid] = target
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, 0, target)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = findTouchTarget(event, i) ?: return true
                    touchTargets[pid] = target
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, i, target)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pid = event.getPointerId(i)
                        val target = touchTargets[pid] ?: continue
                        dispatchMotionEventToTarget(event, MotionEvent.ACTION_MOVE, i, target)
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTargets[pid] ?: return true
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, i, target)
                    touchTargets.remove(pid)
                    if (touchTargets.isEmpty()) flushDeferredComposeRecreations()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val pid = event.getPointerId(0)
                    val target = touchTargets[pid]
                    if (target == null) {
                        releaseAllTouchTargets()
                        return true
                    }
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, 0, target)
                    touchTargets.remove(pid)
                    if (touchTargets.isEmpty()) flushDeferredComposeRecreations()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    releaseAllTouchTargets()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        // Phase 0 perf tracing: marks the UI-thread moment a key action (typically
        // fired on ACTION_UP) enters the fcitx pipeline, for UP->candidates-visible
        // latency measurement in Perfetto.
        trace("KeyView ACTION_UP dispatch") {
            when (action) {
                is MacroAction -> executeMacro(preprocessMacroAction(action, source))
                else -> keyActionListener?.onKeyAction(action, source)
            }
        }
    }

    private fun shouldTriggerSymbolBySwipe(view: View, totalY: Int): Boolean {
        val altTextView = view as? SwipeHintAwareKeyView
        return altTextView?.shouldTriggerAltBySwipe(totalY, swipeSymbolDirection)
            ?: swipeSymbolDirection.checkY(totalY)
    }

    protected open fun preprocessMacroAction(
        action: MacroAction,
        source: KeyActionListener.Source
    ): MacroAction = action

    /**
     * Execute a Macro action
     * @param macroAction the MacroAction to execute
     */
    private fun executeMacro(macroAction: MacroAction) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            Timber.v("executeMacro: steps=%d", macroAction.steps.size)

            // General macro execution
            for ((index, step) in macroAction.steps.withIndex()) {
                Timber.v("executeMacro: executing step %d type=%s", index, step::class.java.simpleName)
                when (step) {
                    is MacroStep.Down -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    // Fcitx keys follow the Fcitx path:
                                    // - Mappable keys are sent by simulating physical keyboard events
                                    // - Unmappable keys fall back to Fcitx sendKey to avoid silent drops
                                    Timber.v("executeMacro: Down Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyDown(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyDown(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Up -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    Timber.v("executeMacro: Up Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyUp(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyUp(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Tap -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    Timber.v("executeMacro: Tap Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyTap(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyTap(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Text -> {
                        Timber.v("executeMacro: Text length=%d", step.text.length)
                        commitText(step.text)
                    }
                    is MacroStep.Edit -> {
                        Timber.v("executeMacro: Edit action=%s", step.action)
                        executeEditAction(step.action)
                    }
                    is MacroStep.AppAction -> {
                        Timber.v("executeMacro: App action id=%s", step.id)
                        executeAppAction(step.id)
                    }
                    is MacroStep.Shortcut -> {
                        Timber.v("executeMacro: Shortcut modifiers=%d key=%s", step.modifiers.size, step.key)
                        executeShortcut(step.modifiers, step.key)
                    }
                    is MacroStep.LayerSwitch -> {
                        Timber.v("executeMacro: %s target=%s", step.mode, step.target)
                        keyActionListener?.onKeyAction(
                            KeyAction.LayerSwitchAction(
                                step.mode,
                                step.target
                            ),
                            KeyActionListener.Source.Keyboard
                        )
                    }
                }
            }
            if (macroAction.hasOneShotLayerConsumingStep()) {
                keyActionListener?.onKeyAction(
                    KeyAction.MacroConsumedAction,
                    KeyActionListener.Source.Keyboard
                )
            }
        }
    }

    private fun MacroAction.hasOneShotLayerConsumingStep(): Boolean {
        return steps.any { step ->
            when (step) {
                is MacroStep.Down -> step.keys.isNotEmpty()
                is MacroStep.Up -> step.keys.isNotEmpty()
                is MacroStep.Tap -> step.keys.isNotEmpty()
                is MacroStep.Text -> step.text.isNotEmpty()
                is MacroStep.Edit -> step.action.isNotBlank()
                is MacroStep.AppAction -> step.id.isNotBlank()
                is MacroStep.Shortcut -> true
                is MacroStep.LayerSwitch -> false
            }
        }
    }

    /**
     * Execute shortcut as syntactic sugar:
     * modifiers down + key tap + modifiers up.
     */
    private suspend fun executeShortcut(modifiers: List<KeyRef>, key: KeyRef) {
        val modifierDownSteps = mutableListOf<KeyRef>()
        val modifierUpSteps = mutableListOf<KeyRef>()
        val normalizedKey = when (key) {
            is KeyRef.Fcitx -> {
                if (key.code.length == 1 && key.code[0].isLetter()) {
                    key.copy(code = key.code.lowercase())
                } else {
                    key
                }
            }
            is KeyRef.Android -> key
        }
        
        for (mod in modifiers) {
            val isSupportedModifier = when ((mod as? KeyRef.Fcitx)?.code) {
                "Ctrl_L", "Ctrl_R",
                "Alt_L", "Alt_R",
                "Shift_L", "Shift_R",
                "Meta_L", "Meta_R",
                "Super_L", "Super_R",
                "Hyper_L", "Hyper_R",
                "Mode_switch",
                "ISO_Level3_Shift",
                "ISO_Level5_Shift" -> true
                else -> false
            }
            if (isSupportedModifier || mod is KeyRef.Android) {
                modifierDownSteps.add(mod)
                modifierUpSteps.add(mod)
            }
        }

        // Generic down/tap/up flow
        // Press all modifiers down
        for (mod in modifierDownSteps) {
            when (mod) {
                is KeyRef.Fcitx -> sendFcitxKeyDown(mod.code)
                is KeyRef.Android -> sendAndroidKeyDown(mod.code)
            }
            delay(50)
        }
        
        // key tap
        when (normalizedKey) {
            is KeyRef.Fcitx -> sendFcitxKeyTap(normalizedKey.code)
            is KeyRef.Android -> sendAndroidKeyTap(normalizedKey.code)
        }
        delay(50)
        
        // Release all modifiers (reverse order)
        for (mod in modifierUpSteps.asReversed()) {
            when (mod) {
                is KeyRef.Fcitx -> sendFcitxKeyUp(mod.code)
                is KeyRef.Android -> sendAndroidKeyUp(mod.code)
            }
            delay(50)
        }
    }

    /**
     * Execute edit actions (copy/cut/paste/selectAll/undo/redo)
     */
    private suspend fun executeEditAction(action: String) {
        val service = getService() ?: return
        val ic = service.currentInputConnection ?: return

        when (action.lowercase()) {
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "selectall", "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "undo" -> ic.performContextMenuAction(android.R.id.undo)
            "redo" -> ic.performContextMenuAction(android.R.id.redo)
        }
    }

    private fun executeAppAction(actionId: String) {
        getService()?.inputView?.executeButtonAction(actionId)
    }

    /**
     * 弹出"上滑清空"提示条。
     *
     * 由退格键长按进入连删（[CustomGestureView.onRepeatStartListener]）时调用，
     * 提示条显示在退格键正上方，手指滑进其区域即触发清空。
     */
    private fun showBackspaceClearPopup(anchorView: View) {
        dismissBackspaceClearPopup()
        backspaceClearTriggered = false
        val popupWidth = anchorView.width * 2
        val popupHeight = dp(40)
        val textView = TextView(context).apply {
            // 合成中上滑只清编码区（发 Escape），提示条显示 "Esc"；非合成时清空整个
            // 输入框，沿用原"上滑清空"文案。
            text = if (composing) "Esc" else context.getString(R.string.backspace_swipe_to_clear)
            gravity = gravityCenter
            setTextColor(theme.keyTextColor)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(theme.altKeyBackgroundColor)
                cornerRadius = dp(8f)
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val popup = PopupWindow(textView, popupWidth, popupHeight, false).apply {
            isTouchable = false
            isOutsideTouchable = false
            animationStyle = 0
        }
        val xOffset = -(anchorView.width / 2)
        val yOffset = -(popupHeight + anchorView.height)
        popup.showAsDropDown(anchorView, xOffset, yOffset)
        backspaceClearPopup = popup
    }

    private fun dismissBackspaceClearPopup() {
        backspaceClearPopup?.dismiss()
        backspaceClearPopup = null
    }

    /**
     * 手指是否落在提示条区域内。
     *
     * 坐标是相对退格键的：提示条横向以键中心对齐、宽度为键宽的两倍，纵向位于键上方
     * 一个提示条高度的范围内。
     */
    private fun checkBackspaceClearPopupHit(anchorView: View, touchX: Float, touchY: Float): Boolean {
        backspaceClearPopup ?: return false
        val popupWidth = anchorView.width * 2
        val popupHeight = dp(40)
        val popupLeft = -(anchorView.width / 2).toFloat()
        val popupRight = popupLeft + popupWidth
        return touchX in popupLeft..popupRight && touchY in -(popupHeight.toFloat())..0f
    }

    /**
     * 清空当前编辑器里的全部文字。
     *
     * 走 `InputConnection` 的"全选 + 覆写空串"，与引擎无关，因此对 Rime 之外的
     * 任何编辑器一致生效（移植自 boomker 的实现，见其提交 5dcdf17a）。
     *
     * 尾部对退格保护的复位是**本仓库特有**的必要处理：本条路径在抬手时会跳过
     * `DeleteSelectionAction`（见 [createKeyView] 的 Up 分支），而那个动作正是退格保护
     * 序列的结束信号。不复位的话，一旦清空发生在受保护的按住序列内
     * （[BackspaceBoundaryGuard] 的 protecting 态），保护会一直残留到空闲超时，
     * 清空后紧接着的退格会被白吞掉。`onCommit()` 同时抑制随后的空 composing 事件
     * 被误判成"删空"。
     */
    private fun executeClearAll() {
        val service = getService() ?: return
        val ic = service.currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        ic.endBatchEdit()
        service.backspaceBoundaryGuard.onCommit()
    }

    /**
     * Check whether it is a function key (F1-F12)
     */
    private fun isFunctionKey(code: String): Boolean {
        return code.matches(Regex("^F(1[0-2]|[1-9])$"))
    }

    /**
     * Map Fcitx key name to Android key code
     */
    private fun mapFcitxToAndroidKey(code: String): Int {
        return when (code) {
            "Ctrl_L" -> android.view.KeyEvent.KEYCODE_CTRL_LEFT
            "Ctrl_R" -> android.view.KeyEvent.KEYCODE_CTRL_RIGHT
            "Shift_L" -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
            "Shift_R" -> android.view.KeyEvent.KEYCODE_SHIFT_RIGHT
            "Alt_L" -> android.view.KeyEvent.KEYCODE_ALT_LEFT
            "Alt_R" -> android.view.KeyEvent.KEYCODE_ALT_RIGHT
            "Meta_L" -> android.view.KeyEvent.KEYCODE_META_LEFT
            "Meta_R" -> android.view.KeyEvent.KEYCODE_META_RIGHT
            "Enter" -> android.view.KeyEvent.KEYCODE_ENTER
            "Tab" -> android.view.KeyEvent.KEYCODE_TAB
            "Escape" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "Space" -> android.view.KeyEvent.KEYCODE_SPACE
            "Delete" -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
            "BackSpace" -> android.view.KeyEvent.KEYCODE_DEL
            "Home" -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            "End" -> android.view.KeyEvent.KEYCODE_MOVE_END
            "Page_Up" -> android.view.KeyEvent.KEYCODE_PAGE_UP
            "Page_Down" -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
            "Left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "Right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "Up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "Down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "F1" -> android.view.KeyEvent.KEYCODE_F1
            "F2" -> android.view.KeyEvent.KEYCODE_F2
            "F3" -> android.view.KeyEvent.KEYCODE_F3
            "F4" -> android.view.KeyEvent.KEYCODE_F4
            "F5" -> android.view.KeyEvent.KEYCODE_F5
            "F6" -> android.view.KeyEvent.KEYCODE_F6
            "F7" -> android.view.KeyEvent.KEYCODE_F7
            "F8" -> android.view.KeyEvent.KEYCODE_F8
            "F9" -> android.view.KeyEvent.KEYCODE_F9
            "F10" -> android.view.KeyEvent.KEYCODE_F10
            "F11" -> android.view.KeyEvent.KEYCODE_F11
            "F12" -> android.view.KeyEvent.KEYCODE_F12
            "A" -> android.view.KeyEvent.KEYCODE_A
            "B" -> android.view.KeyEvent.KEYCODE_B
            "C" -> android.view.KeyEvent.KEYCODE_C
            "D" -> android.view.KeyEvent.KEYCODE_D
            "E" -> android.view.KeyEvent.KEYCODE_E
            "F" -> android.view.KeyEvent.KEYCODE_F
            "G" -> android.view.KeyEvent.KEYCODE_G
            "H" -> android.view.KeyEvent.KEYCODE_H
            "I" -> android.view.KeyEvent.KEYCODE_I
            "J" -> android.view.KeyEvent.KEYCODE_J
            "K" -> android.view.KeyEvent.KEYCODE_K
            "L" -> android.view.KeyEvent.KEYCODE_L
            "M" -> android.view.KeyEvent.KEYCODE_M
            "N" -> android.view.KeyEvent.KEYCODE_N
            "O" -> android.view.KeyEvent.KEYCODE_O
            "P" -> android.view.KeyEvent.KEYCODE_P
            "Q" -> android.view.KeyEvent.KEYCODE_Q
            "R" -> android.view.KeyEvent.KEYCODE_R
            "S" -> android.view.KeyEvent.KEYCODE_S
            "T" -> android.view.KeyEvent.KEYCODE_T
            "U" -> android.view.KeyEvent.KEYCODE_U
            "V" -> android.view.KeyEvent.KEYCODE_V
            "W" -> android.view.KeyEvent.KEYCODE_W
            "X" -> android.view.KeyEvent.KEYCODE_X
            "Y" -> android.view.KeyEvent.KEYCODE_Y
            "Z" -> android.view.KeyEvent.KEYCODE_Z
            "0" -> android.view.KeyEvent.KEYCODE_0
            "1" -> android.view.KeyEvent.KEYCODE_1
            "2" -> android.view.KeyEvent.KEYCODE_2
            "3" -> android.view.KeyEvent.KEYCODE_3
            "4" -> android.view.KeyEvent.KEYCODE_4
            "5" -> android.view.KeyEvent.KEYCODE_5
            "6" -> android.view.KeyEvent.KEYCODE_6
            "7" -> android.view.KeyEvent.KEYCODE_7
            "8" -> android.view.KeyEvent.KEYCODE_8
            "9" -> android.view.KeyEvent.KEYCODE_9
            // Shift+number symbols on standard layouts.
            "exclam", "Exclam" -> android.view.KeyEvent.KEYCODE_1
            "at", "At" -> android.view.KeyEvent.KEYCODE_2
            "numbersign", "Numbersign" -> android.view.KeyEvent.KEYCODE_3
            "dollar", "Dollar" -> android.view.KeyEvent.KEYCODE_4
            "percent", "Percent" -> android.view.KeyEvent.KEYCODE_5
            "asciicircum", "Asciicircum" -> android.view.KeyEvent.KEYCODE_6
            "ampersand", "Ampersand" -> android.view.KeyEvent.KEYCODE_7
            "asterisk", "Asterisk", "multiply", "Multiply" -> android.view.KeyEvent.KEYCODE_8
            // Parentheses are produced from Shift+9/Shift+0 on standard layouts.
            "parenleft", "Parenleft" -> android.view.KeyEvent.KEYCODE_9
            "parenright", "Parenright" -> android.view.KeyEvent.KEYCODE_0
            // Symbol keys (only Android-supported keycodes)
            "minus", "Minus" -> android.view.KeyEvent.KEYCODE_MINUS
            "underscore", "Underscore" -> android.view.KeyEvent.KEYCODE_MINUS
            "equal", "Equal" -> android.view.KeyEvent.KEYCODE_EQUALS
            "plus", "Plus", "Add" -> android.view.KeyEvent.KEYCODE_EQUALS
            "bracketleft", "Bracketleft", "Bracket_L" -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            "braceleft", "Braceleft" -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            "bracketright", "Bracketright", "Bracket_R" -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            "braceright", "Braceright" -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            "backslash", "Backslash" -> android.view.KeyEvent.KEYCODE_BACKSLASH
            "bar", "Bar" -> android.view.KeyEvent.KEYCODE_BACKSLASH
            "semicolon", "Semicolon" -> android.view.KeyEvent.KEYCODE_SEMICOLON
            "colon", "Colon" -> android.view.KeyEvent.KEYCODE_SEMICOLON
            "apostrophe", "Apostrophe" -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            "quotedbl", "Quotedbl" -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            "comma", "Comma", "less", "Less", "Separator" -> android.view.KeyEvent.KEYCODE_COMMA
            "period", "Period", "greater", "Greater" -> android.view.KeyEvent.KEYCODE_PERIOD
            "slash", "Slash", "question", "Question", "Divide" -> android.view.KeyEvent.KEYCODE_SLASH
            "grave", "Grave" -> android.view.KeyEvent.KEYCODE_GRAVE
            // KEYCODE_GRAVE + Shift can produce '~', so map asciitilde aliases here too.
            "asciitilde", "Asciitilde", "tilde", "Tilde" -> android.view.KeyEvent.KEYCODE_GRAVE
            // Special keys
            "Print" -> android.view.KeyEvent.KEYCODE_SYSRQ
            "Menu" -> android.view.KeyEvent.KEYCODE_MENU
            "Scroll_Lock" -> android.view.KeyEvent.KEYCODE_SCROLL_LOCK
            "Pause" -> android.view.KeyEvent.KEYCODE_BREAK
            "Break" -> android.view.KeyEvent.KEYCODE_BREAK
            "Insert" -> android.view.KeyEvent.KEYCODE_INSERT
            "Caps_Lock" -> android.view.KeyEvent.KEYCODE_CAPS_LOCK
            "Num_Lock" -> android.view.KeyEvent.KEYCODE_NUM_LOCK
            "Super_L" -> android.view.KeyEvent.KEYCODE_META_LEFT
            "Super_R" -> android.view.KeyEvent.KEYCODE_META_RIGHT
            // Numpad keys
            "KP_Space" -> android.view.KeyEvent.KEYCODE_SPACE
            "KP_Tab" -> android.view.KeyEvent.KEYCODE_TAB
            "KP_0" -> android.view.KeyEvent.KEYCODE_NUMPAD_0
            "KP_1" -> android.view.KeyEvent.KEYCODE_NUMPAD_1
            "KP_2" -> android.view.KeyEvent.KEYCODE_NUMPAD_2
            "KP_3" -> android.view.KeyEvent.KEYCODE_NUMPAD_3
            "KP_4" -> android.view.KeyEvent.KEYCODE_NUMPAD_4
            "KP_5" -> android.view.KeyEvent.KEYCODE_NUMPAD_5
            "KP_6" -> android.view.KeyEvent.KEYCODE_NUMPAD_6
            "KP_7" -> android.view.KeyEvent.KEYCODE_NUMPAD_7
            "KP_8" -> android.view.KeyEvent.KEYCODE_NUMPAD_8
            "KP_9" -> android.view.KeyEvent.KEYCODE_NUMPAD_9
            "KP_Enter" -> android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            "KP_Add" -> android.view.KeyEvent.KEYCODE_NUMPAD_ADD
            "KP_Subtract" -> android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            "KP_Multiply" -> android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            "KP_Divide" -> android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE
            "KP_Decimal" -> android.view.KeyEvent.KEYCODE_NUMPAD_DOT
            "KP_Equal" -> android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS
            "KP_Separator" -> android.view.KeyEvent.KEYCODE_NUMPAD_COMMA
            else -> {
                // For other keys, try converting to lowercase letters
                val lower = code.lowercase()
                if (lower.length == 1 && lower[0] in 'a'..'z') {
                    android.view.KeyEvent.KEYCODE_A + (lower[0] - 'a')
                } else {
                    // Unknown key: default to Fcitx
                    -1
                }
            }
        }
    }

    private fun mapSpecialFcitxToAndroidKey(code: String): Int? {
        return when (code) {
            "Shift_L" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "Shift_R" -> KeyEvent.KEYCODE_SHIFT_RIGHT
            "Ctrl_L" -> KeyEvent.KEYCODE_CTRL_LEFT
            "Ctrl_R" -> KeyEvent.KEYCODE_CTRL_RIGHT
            "Alt_L" -> KeyEvent.KEYCODE_ALT_LEFT
            "Alt_R" -> KeyEvent.KEYCODE_ALT_RIGHT
            "Meta_L" -> KeyEvent.KEYCODE_META_LEFT
            "Meta_R" -> KeyEvent.KEYCODE_META_RIGHT
            "Super_L" -> KeyEvent.KEYCODE_META_LEFT
            "Super_R" -> KeyEvent.KEYCODE_META_RIGHT
            "Hyper_L" -> KeyEvent.KEYCODE_FUNCTION
            "Hyper_R" -> KeyEvent.KEYCODE_FUNCTION
            "Mode_switch" -> KeyEvent.KEYCODE_ALT_RIGHT
            "ISO_Level3_Shift" -> KeyEvent.KEYCODE_ALT_RIGHT
            "ISO_Level5_Shift" -> KeyEvent.KEYCODE_ALT_RIGHT
            else -> null
        }
    }

    private fun shouldShiftSymbol(code: String): Boolean {
        return when (code.lowercase()) {
            "underscore", "plus", "braceleft", "braceright", "bar",
            "colon", "quotedbl", "less", "greater", "question",
            "exclam", "at", "numbersign", "dollar", "percent",
            "asciicircum", "ampersand", "asterisk", "multiply", "parenleft", "parenright",
            "add",
            "asciitilde", "tilde" -> true
            else -> false
        }
    }

    private fun mapFcitxToScanCode(code: String, keyCode: Int): Int {
        val known = when (code) {
            "Shift_L" -> 42
            "Shift_R" -> 54
            "Ctrl_L" -> 29
            "Ctrl_R" -> 97
            "Alt_L" -> 56
            "Alt_R" -> 100
            "Meta_L" -> 125
            "Meta_R" -> 126
            "F1" -> 59
            "F2" -> 60
            "F3" -> 61
            "F4" -> 62
            "F5" -> 63
            "F6" -> 64
            "F7" -> 65
            "F8" -> 66
            "F9" -> 67
            "F10" -> 68
            "F11" -> 87
            "F12" -> 88
            else -> null
        }
        if (known != null) {
            return known
        }
        return if (keyCode >= 0) {
            // Use try-catch to handle potential exceptions from generated code
            try {
                val uncached = Int.MIN_VALUE
                val cached = cachedMacroScancodes.get(keyCode, uncached)
                val scanCode = if (cached != uncached) cached else {
                    ScancodeMapping.keyCodeToScancode(keyCode).also {
                        cachedMacroScancodes.put(keyCode, it)
                    }
                }
                if (scanCode != 0) scanCode else 0
            } catch (e: Exception) {
                Timber.w("keyCodeToScancode failed for keyCode=$keyCode: ${e.message}")
                0
            }
        } else 0
    }

    /**
     * Send Fcitx key tap event (down + up)
     */
    private suspend fun sendFcitxKeyTap(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        // Every key is held for the same interval, modifiers included. Modifiers used to be held
        // longer, on the theory that Rime needs it to recognize a standalone modifier tap; it does
        // not. librime's AsciiComposer tells a lone modifier from a "modifier + key" combo by event
        // order, not elapsed time, and only imposes an upper bound (release within 500ms). The hold
        // exists only so the down/up pair looks like a real keystroke to apps that receive the
        // forwarded events. Same value as FcitxInputMethodService.STANDALONE_MODIFIER_HOLD_MS.
        val keyHoldDelayMs = 50L

        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            }
            // Key down
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            delay(keyHoldDelayMs)
            // Key up
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP, fromMacro = true)
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_UP, fromMacro = true)
            }
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = false),
                KeyActionListener.Source.Keyboard
            )
            delay(keyHoldDelayMs)
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = true),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Send Fcitx key down event
     */
    private fun sendFcitxKeyDown(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            }
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = false),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Send Fcitx key up event
     */
    private fun sendFcitxKeyUp(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP, fromMacro = true)
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_UP, fromMacro = true)
            }
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = true),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Get FcitxInputMethodService instance
     */
    private fun getService(): org.fcitx.fcitx5.android.input.FcitxInputMethodService? {
        // Try obtaining directly from context
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is org.fcitx.fcitx5.android.input.FcitxInputMethodService) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return context as? org.fcitx.fcitx5.android.input.FcitxInputMethodService
    }

    protected fun isSimulatedCapsLockOn(): Boolean {
        return getService()?.isSimulatedCapsLockOn() == true
    }

    /**
     * 长按重复的这次退格是否会被退格边界保护吞掉——是则不触发按键反馈（震动）。
     * 仅对指向 BackSpace 的重复动作生效，其它键（含滑动移动光标）不受影响。
     */
    private fun isBackspaceRepeatConsumed(action: KeyAction): Boolean {
        val isBackspace = action is KeyAction.SymAction &&
            action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace
        if (!isBackspace) return false
        return getService()?.backspaceBoundaryGuard?.isConsumingBackspaces() == true
    }

    /**
     * Send Android key down event
     */
    private fun sendAndroidKeyDown(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val event = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(event)
    }

    /**
     * Send Android key up event
     */
    private fun sendAndroidKeyUp(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val event = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(event)
    }

    /**
     * Send Android key tap event (down + up)
     */
    private fun sendAndroidKeyTap(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val downEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN,
            keyCode
        )
        val upEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(downEvent)
        service.currentInputConnection?.sendKeyEvent(upEvent)
    }

    /**
     * Commit text
     */
    private fun commitText(text: String) {
        keyActionListener?.onKeyAction(KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        if (action is PopupAction.PreviewAction || action is PopupAction.ShowKeyboardAction ||
            action is PopupAction.ShowLongPressKeyboardAction || action is PopupAction.ShowMenuAction
        ) {
            // Clear the other popups but keep any belonging to a key still held by another
            // finger. Dismissing that one discards its pending selection, so with two-finger
            // typing the character chosen from a popup keyboard was never committed.
            dismissAllPopups(exceptViewId = heldPopupViewIdOtherThan(action.viewId))
        }
        popupActionListener?.onPopupAction(action)
    }

    private fun dismissAllPopups(exceptViewId: Int? = null) {
        popupActionListener?.onPopupAction(PopupAction.DismissAllAction(exceptViewId = exceptViewId))
    }

    /**
     * Id of a key that is currently pressed and is not [viewId], if any.
     *
     * Used to spare that key's popup when another key shows one. Only one id is returned
     * because a popup keyboard can only be driven by one pointer at a time.
     *
     * Uses [View.isPressed] rather than [touchTargets], because the latter is only populated
     * under the vivo keypress workaround while this race happens on every device.
     */
    private fun heldPopupViewIdOtherThan(viewId: Int): Int? {
        touchTargets.values.firstOrNull { it.view.id != viewId }?.let { return it.view.id }
        if (!::keyRows.isInitialized) return null
        keyRows.forEach { row ->
            row.children.forEach { child ->
                if (child is KeyView && child.isPressed && child.id != viewId) return child.id
            }
        }
        return null
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    open fun onAttach() {
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onReturnDrawableOverride(drawable: Drawable?) {
        // do nothing by default - override in subclasses that render return key icons
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        // do nothing by default
    }

    open fun onDetach() {
        // 键盘摘除（隐藏/切换）时释放提示条，否则 PopupWindow 会作为独立窗口留在
        // 屏幕上，且持有已脱树的锚点视图。
        dismissBackspaceClearPopup()
        releaseAllTouchTargets()
    }

}

class AuxBarAdapter(
    private val theme: Theme,
    private val vMargin: Int,
    private val hMargin: Int,
    private val position: AuxBarPosition,
    private val keyTextSize: Float,
    private val onTrigger: (Int) -> Unit
) : RecyclerView.Adapter<AuxBarAdapter.ViewHolder>() {

    private var actions = listOf<AuxBarAction>()

    private val keyBorder: Boolean by lazy { ThemeManager.prefs.keyBorder.getValue() }
    private val keyBorderStroke: Boolean by lazy { ThemeManager.prefs.keyBorderStroke.getValue() }
    private var radius: Float = 0f

    fun updateActions(newActions: List<AuxBarAction>) {
        if (newActions == actions) return
        actions = newActions
        notifyDataSetChanged()
    }

    fun applyConfiguredFonts(rv: RecyclerView) {
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? ViewHolder ?: continue
            holder.label.setFontTypeFace("key_main_font")
        }
    }

    override fun getItemCount() = actions.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        radius = ctx.dp(ThemeManager.prefs.keyRadius.getValue().toFloat())
        val isHorizontal = position == AuxBarPosition.Top || position == AuxBarPosition.Bottom
        val view = CustomGestureView(ctx).apply {
            val extraHPad = if (isHorizontal) ctx.dp(12) else 0
            setPadding(hMargin + extraHPad, vMargin, hMargin + extraHPad, vMargin)
            layoutParams = ViewGroup.LayoutParams(
                if (isHorizontal) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (isHorizontal) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val label = AutoScaleTextView(ctx).apply {
                scaleMode = AutoScaleTextView.Mode.Proportional
                setTextSize(TypedValue.COMPLEX_UNIT_SP, FontProviders.getFontSize("key_main_font", keyTextSize))
                typeface = Typeface.DEFAULT
                fontKey = "key_main_font"
                isSingleLine = true
                gravity = gravityCenter
                setTextColor(theme.keyTextColor)
            }
            val childLp = ViewGroup.LayoutParams(
                if (isHorizontal) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(label, childLp)
            tag = label
        }
        applyThemeStyling(view)
        return ViewHolder(view)
    }

    /**
     * 纵向（Left/Right）辅助栏不再走 RecyclerView：纵向 LinearLayoutManager
     * 按累计 item 高排布，容器变矮、item 变小后多余空间沉底，按钮之间留空隙。
     * 这里直接建一个跟主键盘按键同结构的 item view（padding 表达主题上下间距，
     * 本体填满调用方分配的行高），由调用方按 item 数量均分容器高度。
     */
    fun createItemView(parent: ViewGroup): View {
        val ctx = parent.context
        radius = ctx.dp(ThemeManager.prefs.keyRadius.getValue().toFloat())
        val view = CustomGestureView(ctx).apply {
            setPadding(hMargin, vMargin, hMargin, vMargin)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val label = AutoScaleTextView(ctx).apply {
                scaleMode = AutoScaleTextView.Mode.Proportional
                setTextSize(TypedValue.COMPLEX_UNIT_SP, FontProviders.getFontSize("key_main_font", keyTextSize))
                typeface = Typeface.DEFAULT
                fontKey = "key_main_font"
                isSingleLine = true
                gravity = gravityCenter
                setTextColor(theme.keyTextColor)
            }
            addView(
                label,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            tag = label
        }
        applyThemeStyling(view)
        return view
    }

    fun bindItemView(item: View, action: AuxBarAction) {
        val label = item.tag as? AutoScaleTextView ?: return
        label.text = action.text
        label.setFontTypeFace("key_main_font")
        item.setOnClickListener { onTrigger(action.id) }
    }

    private fun applyThemeStyling(view: CustomGestureView) {
        val ctx = view.context
        val drawHMargin = hMargin
        val drawVMargin = vMargin
        val bkgColor = theme.keyBackgroundColor
        val shadowColor = theme.keyShadowColor
        if (keyBorder && keyBorderStroke) {
            view.background = borderedKeyBackgroundDrawable(
                bkgColor, shadowColor, radius, ctx.dp(1), drawHMargin, drawVMargin
            )
            view.foreground = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    borderedKeyBackgroundDrawable(
                        0, theme.keyPressHighlightColor,
                        radius, ctx.dp(2), drawHMargin, drawVMargin
                    )
                )
            }
        } else if (keyBorder) {
            view.background = shadowedKeyBackgroundDrawable(
                bkgColor, shadowColor, radius, ctx.dp(1), drawHMargin, drawVMargin
            )
            view.foreground = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    insetRadiusDrawable(drawHMargin, drawVMargin, radius, theme.keyPressHighlightColor)
                )
            }
        } else {
            // Match borderless key rendering in KeyView: no persistent key background,
            // only pressed-state highlight over the keyboard/bar surface.
            view.background = null
            view.foreground = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    InsetDrawable(ColorDrawable(theme.keyPressHighlightColor), drawHMargin, drawVMargin, drawHMargin, drawVMargin)
                )
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions.getOrNull(position) ?: return
        holder.bind(action, onTrigger)
    }

    class ViewHolder(val view: CustomGestureView) : RecyclerView.ViewHolder(view) {
        internal val label: AutoScaleTextView get() = view.tag as AutoScaleTextView

        fun bind(action: AuxBarAction, onTrigger: (Int) -> Unit) {
            label.text = action.text
            label.setFontTypeFace("key_main_font")
            view.setOnClickListener { onTrigger(action.id) }
        }
    }
}

/**
 * Adapter for the auxiliary bar that renders user-configured keys (used when the
 * layout has an in-keyboard aux bar position but there are no tabs to display).
 */
class AuxBarKeyAdapter(
    private val vertical: Boolean,
    private val keyViewFactory: (KeyDef) -> KeyView
) : RecyclerView.Adapter<AuxBarKeyAdapter.ViewHolder>() {

    private var keys = listOf<KeyDef>()
    private var keySignatures = listOf<String>()
    private var minItemHeightPx: Int = -1
    private var attachedRecyclerView: RecyclerView? = null
    private var lastHorizontalWidth: Int = -1
    private val horizontalLayoutChangeListener =
        View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (vertical) return@OnLayoutChangeListener
            val width = right - left
            val oldWidth = oldRight - oldLeft
            if (width > 0 && width != oldWidth && width != lastHorizontalWidth) {
                lastHorizontalWidth = width
                notifyDataSetChanged()
            }
        }

    fun setMinItemHeight(px: Int) {
        if (px > 0 && px != minItemHeightPx) {
            minItemHeightPx = px
            notifyDataSetChanged()
        }
    }

    fun updateKeys(newKeys: List<KeyDef>) {
        val newSignatures = newKeys.map(::buildKeySignature)
        if (newSignatures == keySignatures) return
        keys = newKeys
        keySignatures = newSignatures
        notifyDataSetChanged()
    }

    override fun getItemCount() = keys.size

    override fun getItemId(position: Int): Long {
        val sig = keySignatures.getOrNull(position) ?: return RecyclerView.NO_ID
        return sig.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val view = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                if (!vertical) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (!vertical) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (vertical) {
                minimumHeight = if (minItemHeightPx > 0) minItemHeightPx else ctx.dp(52)
            }
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = keys.getOrNull(position) ?: return
        val signature = keySignatures.getOrNull(position) ?: buildKeySignature(key)
        if (!vertical) {
            val rv = attachedRecyclerView ?: (holder.itemView.parent as? RecyclerView)
            val usableWidth = rv?.let { (it.width - it.paddingLeft - it.paddingRight).coerceAtLeast(0) } ?: 0
            if (usableWidth > 0 && itemCount > 0) {
                val lp = holder.itemView.layoutParams as? RecyclerView.LayoutParams
                    ?: RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                lp.width = usableWidth / itemCount
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                lp.rightMargin = 0
                holder.itemView.layoutParams = lp
            }
        }
        holder.bind(key, signature)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        if (!vertical) {
            recyclerView.addOnLayoutChangeListener(horizontalLayoutChangeListener)
            lastHorizontalWidth = recyclerView.width
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (!vertical) {
            recyclerView.removeOnLayoutChangeListener(horizontalLayoutChangeListener)
            lastHorizontalWidth = -1
        }
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(private val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        private var boundSignature: String? = null
        private var keyView: KeyView? = null

        fun bind(key: KeyDef, signature: String) {
            var current = keyView
            if (current == null || boundSignature != signature) {
                container.removeAllViews()
                current = keyViewFactory(key).also { created ->
                    created.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
                    container.addView(
                        created,
                        ViewGroup.LayoutParams(
                            if (!vertical) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                            if (!vertical) ViewGroup.LayoutParams.MATCH_PARENT else
                                if (minItemHeightPx > 0) minItemHeightPx else container.context.dp(52)
                        )
                    )
                }
                keyView = current
                boundSignature = signature
            }
            if (vertical) {
                current.layoutParams = (current.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )).apply {
                    height = if (minItemHeightPx > 0) minItemHeightPx else container.context.dp(52)
                }
            } else {
                current.layoutParams = (current.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }
    }

    /**
     * 纵向（Left/Right）无 tabs 时的自定义按键：直接建一个 KeyView，
     * 由调用方按均分高度排布（跟有 tabs 的纵向 item 一样，高度由调用方分配）。
     */
    fun createItemView(parent: ViewGroup, key: KeyDef): View {
        val container = FrameLayout(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val keyView = keyViewFactory(key).also { created ->
            created.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
            container.addView(
                created,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        container.tag = keyView
        return container
    }

    private fun buildKeySignature(key: KeyDef): String {
        val app = key.appearance
        val popupSig = key.popup?.joinToString("|") { it::class.java.name }.orEmpty()
        val behaviorSig = key.behaviors
            .sortedBy { it::class.java.name + it.hashCode() }
            .joinToString("|") { it::class.java.name + ":" + it.hashCode() }
        return buildString(128) {
            append(key::class.java.name)
            append('#').append(app::class.java.name)
            append('#').append(app.variant.name)
            append('#').append(app.border.name)
            append('#').append(app.margin)
            append('#').append(app.percentWidth)
            append('#').append(app.viewId)
            append('#').append(app.textColor)
            append('#').append(app.textColorMonet)
            append('#').append(app.altTextColor)
            append('#').append(app.altTextColorMonet)
            append('#').append(app.backgroundColor)
            append('#').append(app.backgroundColorMonet)
            append('#').append(app.shadowColor)
            append('#').append(app.shadowColorMonet)
            append('#').append(behaviorSig)
            append('#').append(popupSig)
        }
    }

    init {
        setHasStableIds(true)
    }
}
