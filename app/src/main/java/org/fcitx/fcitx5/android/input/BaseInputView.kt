/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.tracing.trace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDisconnectedException
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.candidates.CandidateCharacterPopup
import org.fcitx.fcitx5.android.input.candidates.candidateCharacters
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.views.dsl.core.withTheme
import timber.log.Timber
import kotlin.math.max

abstract class BaseInputView(
    val service: FcitxInputMethodService,
    val fcitx: FcitxConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    /**
     * Update UI (from cached events in FcitxAPI) to match fcitx's state, before ready to receive real events
     */
    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: FcitxEvent<*>)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch {
            // A view can outlive its connection by a moment (service teardown, fcitx restart).
            // Since C31 that surfaces as FcitxDisconnectedException instead of a
            // CancellationException, so it has to be caught rather than left to crash.
            val events = try {
                fcitx.runImmediately { eventFlow }
            } catch (e: FcitxDisconnectedException) {
                Timber.d(e, "fcitx event flow unavailable for this view")
                return@launch
            }
            events.collect {
                // Phase 0 perf tracing: main-thread collection start/end per event.
                trace("collectFcitxEvent") {
                    handleFcitxEvent(it)
                }
            }
        }
    }

    var handleEvents = false
        set(value) {
            field = value
            if (field) {
                onStartHandleFcitxEvent()
                if (eventHandlerJob == null) {
                    setupFcitxEventHandler()
                }
            } else {
                eventHandlerJob?.cancel()
                eventHandlerJob = null
            }
        }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx) }
    }

    /**
     * 候选词上滑选字：把 reset + commitText 打包进同一个 fcitx 串行 job。
     *
     * 必须先 reset（清空预编辑/高亮）再 `commitText`，顺序不能反：直接上屏而不 reset
     * 的话，Rime 会话里残留的预编辑会在下一次按键时被再次提交，出现重复上屏。
     * 用 `postFcitxJob` 而不是 `launchOnReady`，保证与其它输入操作串行、不乱序。
     */
    private fun commitCandidateCharacter(character: String) {
        service.postFcitxJob {
            reset()
            withContext(Dispatchers.Main.immediate) {
                service.commitText(character)
            }
        }
    }

    private var candidateCharacterPopup: CandidateCharacterPopup? = null

    /**
     * 给候选词条目绑定「按住后滑动」手势。
     *
     * 按住（判定阈值同长按）后：
     * - 向上滑 → 弹出单字窗，滑到哪个字就高亮哪个，抬手提交该字；弹窗外抬手取消；
     * - 向下滑 → 呼出候选操作菜单（忘记词汇等）。
     * 按住不动直接抬手 → 回落到原有的长按菜单，行为与改动前一致。
     *
     * 之所以用按住后滑动而不是直接滑动：直接滑动要在按下时就夺走父容器的触摸拦截，
     * 展开候选列表就没法上下滚动翻页了。按住后才接管，未按住时的滑动完全留给父容器。
     *
     * [resolveIndex] 在触发菜单/提交时才求值，与既有 click / long-click 监听一致，
     * 避免 DiffUtil 不 rebind 时下标停在旧起点。
     */
    fun bindCandidateGesture(
        view: CustomGestureView,
        text: String,
        resolveIndex: () -> Int
    ) {
        val characters = text.candidateCharacters()
        var gestureCancelled = false
        var popup: CandidateCharacterPopup? = null
        // 本次按住是否已经选定方向（上滑弹字窗 / 下滑弹菜单）；选定后不再改向。
        var directionLocked = false

        view.holdSwipeEnabled = true
        view.swipeThresholdY = resources.displayMetrics.density * 20f
        // 仅用于感知 ACTION_CANCEL（手势被 RecyclerView 滚动等打断时关闭弹窗）；
        // 返回 false，不消费点击/长按事件。无障碍点击委托给 CustomGestureView 自身。
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureCancelled = true
                popup = null
                directionLocked = false
                dismissCandidateCharacterPopup()
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        view.onGestureListener = CustomGestureView.OnGestureListener { _, event ->
            when (event.type) {
                // 长按判定到期、进入「已按住」时才收到 Down：此刻开始由本视图接管触摸。
                CustomGestureView.GestureType.Down -> {
                    dismissCandidateCharacterPopup()
                    gestureCancelled = false
                    popup = null
                    directionLocked = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }

                CustomGestureView.GestureType.Move -> {
                    when {
                        // 方向已定：字窗跟随手指更新高亮
                        popup != null -> {
                            popup?.updateFocus(event.x, event.y)
                            true
                        }

                        directionLocked -> true

                        // 向上滑：弹单字窗
                        event.totalY < 0 && characters.isNotEmpty() -> {
                            directionLocked = true
                            popup = CandidateCharacterPopup(view, characters, theme).also {
                                candidateCharacterPopup = it
                                it.show()
                                it.updateFocus(event.x, event.y)
                            }
                            InputFeedbacks.hapticFeedback(view, longPress = true)
                            true
                        }

                        // 向下滑：呼出候选操作菜单（忘记词汇等）
                        event.totalY > 0 -> {
                            directionLocked = true
                            showCandidateActionMenu(resolveIndex(), text, view)
                            true
                        }

                        else -> false
                    }
                }

                CustomGestureView.GestureType.Up -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (gestureCancelled) {
                        gestureCancelled = false
                        return@OnGestureListener event.consumed
                    }
                    popup?.let {
                        it.updateFocus(event.x, event.y)
                        it.selectedCharacter()?.let(::commitCandidateCharacter)
                        dismissCandidateCharacterPopup()
                        popup = null
                        return@OnGestureListener true
                    }
                    // 下滑分支：菜单已弹出，消费掉抬手，避免再触发一次长按回落。
                    directionLocked || event.consumed
                }
            }
        }
    }

    fun unbindCandidateGesture(view: CustomGestureView) {
        dismissCandidateCharacterPopup()
        view.setOnTouchListener(null)
        view.onGestureListener = null
        view.holdSwipeEnabled = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun dismissCandidateCharacterPopup() {
        candidateCharacterPopup?.dismiss()
        candidateCharacterPopup = null
    }

    private var candidateActionMenu: PopupMenu? = null

    val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    fun showCandidateActionMenu(idx: Int, text: String, view: View) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        service.lifecycleScope.launch {
            val actions = fcitx.runOnReady { getCandidateActions(idx) }
            if (actions.isEmpty()) return@launch
            InputFeedbacks.hapticFeedback(view, longPress = true)
            candidateActionMenu = PopupMenu(themedContext, view).apply {
                menu.add(buildSpannedString {
                    bold {
                        color(
                            context.styledColorOrDefault(
                                android.R.attr.colorAccent,
                                theme.genericActiveForegroundColor
                            )
                        ) {
                            append(text)
                        }
                    }
                }).apply {
                    isEnabled = false
                }
                actions.forEach { action ->
                    menu.item(action.text) {
                        triggerCandidateAction(idx, action.id)
                    }
                }
                setOnDismissListener {
                    candidateActionMenu = null
                }
                show()
            }
        }
    }

    private val navbarBackground by ThemeManager.prefs.navbarBackground

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navbarBackground != ThemePrefs.NavbarBackground.Full) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        // use navigation bar insets when available
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        // in case navigation bar insets goes wrong (eg. on LineageOS 21+ with gesture navigation)
        // use mandatory system gesture insets
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            // check system gesture insets and fallback to navigation_bar_frame_height just in case
            val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, context.navbarFrameHeight())
            }
        }
        return insetsBottom
    }

    private val ignoreSystemWindowInsets by AppPrefs.getInstance().advanced.ignoreSystemWindowInsets

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ignoreSystemWindowInsets) {
            // suppress view's own onApplyWindowInsets
            setOnApplyWindowInsetsListener { _, insets -> insets }
        } else {
            // on API 35+, we must call requestApplyInsets() manually after replacing views,
            // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
            requestApplyInsets()
        }
    }

    override fun onDetachedFromWindow() {
        dismissCandidateCharacterPopup()
        handleEvents = false
        super.onDetachedFromWindow()
    }
}
