/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import android.widget.TextView
import androidx.annotation.Size
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.floating.CursorFollowingPosition
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesPosition
import org.fcitx.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.padding
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class CandidatesView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val ctx = context.withTheme(R.style.Theme_InputViewTheme)

    private val candidatesPrefs = AppPrefs.getInstance().candidates
    private val orientation by candidatesPrefs.orientation
    private val windowMinWidth by candidatesPrefs.windowMinWidth
    private val windowPadding by candidatesPrefs.windowPadding
    private val windowRadius by candidatesPrefs.windowRadius
    private val fontSize by candidatesPrefs.fontSize
    private val itemPaddingVertical by candidatesPrefs.itemPaddingVertical
    private val itemPaddingHorizontal by candidatesPrefs.itemPaddingHorizontal
    private val floatingMode by candidatesPrefs.mode
    private val highlightRadius by candidatesPrefs.candidateHighlightRadius

    private val floatingPosition: FloatingCandidatesPosition?
        get() = candidatesPrefs.windowPosition(floatingMode, isVirtualKeyboardVisible)

    /**
     * Gap between candidates window and screen edges/keyboard
     */
    private val candidatesGap: Float
        get() = dp(8).toFloat()

    /**
     * Minimum keyboard height to consider it visible.
     * If keyboard top is within this distance from screen bottom, keyboard is considered hidden.
     */
    private val keyboardVisibleThreshold: Float
        get() = dp(100).toFloat()

    private var inputPanel = FcitxEvent.InputPanelEvent.Data()
    private var paged = FcitxEvent.PagedCandidateEvent.Data.Empty

    /**
     * horizontal, bottom, top
     */
    private val anchorPosition = floatArrayOf(0f, 0f, 0f)
    private val parentSize = floatArrayOf(0f, 0f)
    
    /**
     * Keyboard boundaries: left, top, right, bottom
     * Used for positioning floating candidates when using virtual keyboard
     */
    private val keyboardBounds = floatArrayOf(0f, 0f, 0f, 0f)
    /**
     * Cursor Y positions for floating candidates positioning
     */
    private var cursorTop = 0f
    private var cursorBottom = 0f

    /**
     * Track whether virtual keyboard is visible
     */
    private val isVirtualKeyboardVisible: Boolean
        get() = service.inputDeviceManager.isVirtualKeyboard

    var onPositionChanged: (() -> Unit)? = null

    private var shouldUpdatePosition = false
    private val containerLocation = IntArray(2)

    /**
     * layout update may or may not cause [CandidatesView]'s size [onSizeChanged],
     * in either case, we should reposition it
     */
    private val layoutListener = OnGlobalLayoutListener {
        if (visibility == VISIBLE && floatingPosition == FloatingCandidatesPosition.FollowCursor) {
            // Keyboard bounds can change without a new cursor callback, e.g. on rotation.
            (parent as? View)?.let { container ->
                parentSize[0] = container.width.toFloat()
                parentSize[1] = container.height.toFloat()
                container.getLocationInWindow(containerLocation)
            }
            keyboardBounds[1] = service.inputView?.getInputFieldTopY() ?: 0f
        }
        shouldUpdatePosition = true
    }

    /**
     * [CandidatesView]'s position is calculated based on it's size,
     * so we need to recalculate the position after layout,
     * and before any actual drawing to avoid flicker
     */
    private val preDrawListener = OnPreDrawListener {
        if (shouldUpdatePosition) {
            updatePosition()
        }
        true
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    private val setupTextView: TextView.() -> Unit = {
        textSize = fontSize.toFloat()
        typeface = org.fcitx.fcitx5.android.input.font.FontProviders.resolveTypeface("cand_font", typeface)
        val v = dp(itemPaddingVertical)
        val h = dp(itemPaddingHorizontal)
        setPadding(h, v, h, v)
    }

    private val preeditUi = PreeditUi(ctx, theme, setupTextView,
        onStatusHintPresented = { id, frameTime -> fcitx.launchOnReady { it.statusHintPresented(id, frameTime) } })

    private val candidatesUi = PagedCandidatesUi(
        ctx, theme, setupTextView,
        onCandidateClick = { index -> paged.candidates.getOrNull(index)?.selectionTarget?.let(service::postCandidateSelection) },
        onCandidateAction = { index, text, view ->
            paged.candidates.getOrNull(index)?.selectionTarget?.let { target ->
                showCandidateActionMenu(target.index, text, view, global = target.global, expectedContext = target.context)
            }
        },
        onPrevPage = { fcitx.launchOnReady { it.offsetCandidatePage(-1) } },
        onNextPage = { fcitx.launchOnReady { it.offsetCandidatePage(1) } },
        highlightRadius = dp(highlightRadius).toFloat()
    )

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        handleFcitxEvent(FcitxEvent.InputPanelEvent(inputPanelData))
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.InputPanelEvent -> {
                inputPanel = it.data
                updateUi()
            }
            is FcitxEvent.PagedCandidateEvent -> {
                paged = it.data
                updateUi()
            }
            else -> {}
        }
    }

    private fun evaluateVisibility(): Boolean {
        return inputPanel.preedit.isNotEmpty() ||
                paged.candidates.isNotEmpty() ||
                inputPanel.auxUp.isNotEmpty() ||
                inputPanel.auxDown.isNotEmpty()
    }

    private fun updateUi() {
        preeditUi.update(inputPanel)
        preeditUi.root.visibility = if (preeditUi.visible) VISIBLE else GONE
        candidatesUi.update(paged, orientation)
        updatePreeditPosition(orientation == FloatingCandidatesOrientation.VerticalReversed)
        val wasVisible = visibility == VISIBLE
        if (evaluateVisibility()) {
            visibility = VISIBLE
        } else {
            // RecyclerView won't update its items when ancestor view is GONE
            visibility = INVISIBLE
        }
        if (wasVisible != (visibility == VISIBLE)) {
            onPositionChanged?.invoke()
        }
    }

    private fun updatePreeditPosition(reversed: Boolean) {
        preeditUi.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = if (reversed) ConstraintLayout.LayoutParams.UNSET else ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = if (reversed) candidatesUi.root.id else ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = if (reversed) ConstraintLayout.LayoutParams.PARENT_ID else ConstraintLayout.LayoutParams.UNSET
        }
        candidatesUi.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = if (reversed) ConstraintLayout.LayoutParams.PARENT_ID else ConstraintLayout.LayoutParams.UNSET
            topToBottom = if (reversed) ConstraintLayout.LayoutParams.UNSET else preeditUi.root.id
            bottomToTop = if (reversed) preeditUi.root.id else ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = if (reversed) ConstraintLayout.LayoutParams.UNSET else ConstraintLayout.LayoutParams.PARENT_ID
        }
    }

    private var bottomInsets = 0
    private var cursorViewportWidth = Int.MAX_VALUE
    private var cursorViewportHeight = Int.MAX_VALUE
    private val cursorViewportClip = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            limitMeasureSpec(widthMeasureSpec, cursorViewportWidth),
            limitMeasureSpec(heightMeasureSpec, cursorViewportHeight)
        )
    }

    private fun limitMeasureSpec(spec: Int, limit: Int): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        return when {
            limit == Int.MAX_VALUE -> spec
            mode == MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
            size > limit -> MeasureSpec.makeMeasureSpec(limit, mode)
            else -> spec
        }
    }

    private fun setCursorViewportSize(width: Int, height: Int) {
        if (cursorViewportWidth == width && cursorViewportHeight == height) return
        cursorViewportWidth = width
        cursorViewportHeight = height
        requestLayout()
    }

    private fun updatePosition() {
        if (visibility != VISIBLE) {
            // skip unnecessary updates
            return
        }
        val (parentWidth, parentHeight) = parentSize
        if (parentWidth <= 0 || parentHeight <= 0) {
            // panic, bail
            translationX = 0f
            translationY = 0f
            return
        }
        
        val w: Int = width
        val h: Int = height

        val position = floatingPosition
        if (position == FloatingCandidatesPosition.FollowCursor) {
            updateCursorFollowingPosition(parentWidth, parentHeight, w, h)
            shouldUpdatePosition = false
            onPositionChanged?.invoke()
            return
        }

        setCursorViewportSize(Int.MAX_VALUE, Int.MAX_VALUE)
        if (clipBounds != null) clipBounds = null

        val (tX, tY) = calculatePositionByCursorAnchor(
            position,
            parentWidth,
            parentHeight,
            w.toFloat(),
            h.toFloat()
        )
        
        if (tX.isNaN() || tY.isNaN()) {
            translationX = 0f
            translationY = 0f
            return
        }
        translationX = tX
        translationY = tY
        // update touchEventReceiverWindow's position after CandidatesView's
        touchEventReceiverWindow.showAt(tX.roundToInt(), tY.roundToInt(), w, h)
        shouldUpdatePosition = false
        onPositionChanged?.invoke()
    }

    private fun updateCursorFollowingPosition(
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Int,
        selfHeight: Int
    ) {
        val gap = candidatesGap
        val (offsetX, offsetY) = containerLocation
        val screenBottom = (parentHeight - bottomInsets).coerceAtLeast(0f)
        val bottomLimit = if (isVirtualKeyboardVisible)
            (keyboardBounds[1] - offsetY).coerceIn(0f, screenBottom)
        else screenBottom
        setCursorViewportSize(
            CursorFollowingPosition.maxSize(parentWidth, gap),
            CursorFollowingPosition.maxSize(bottomLimit, gap)
        )
        val (horizontal, bottom, top) = anchorPosition
        val placement = CursorFollowingPosition.calculate(
            width = parentWidth,
            bottomLimit = bottomLimit,
            selfWidth = selfWidth,
            selfHeight = selfHeight,
            horizontal = horizontal - offsetX,
            top = top - offsetY,
            bottom = bottom - offsetY,
            gap = gap,
            rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        )
        val x = placement.x.roundToInt()
        val y = placement.y.roundToInt()
        translationX = (x - left).toFloat()
        translationY = (y - this.top).toFloat()
        // Clamp drawing and touch together while the constrained layout is pending.
        if (placement.width < selfWidth || placement.height < selfHeight) {
            cursorViewportClip.set(0, 0, placement.width, placement.height)
            clipBounds = cursorViewportClip
        } else if (clipBounds != null) {
            clipBounds = null
        }
        if (placement.width > 0 && placement.height > 0) {
            touchEventReceiverWindow.showAt(x + offsetX, y + offsetY, placement.width, placement.height)
        } else {
            touchEventReceiverWindow.dismiss()
        }
    }

    private fun calculatePositionByCursorAnchor(
        position: FloatingCandidatesPosition?,
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Float,
        selfHeight: Float
    ): Pair<Float, Float> {
        val (horizontal, bottom, top) = anchorPosition
        val gap = candidatesGap

        if (position != null) {
            return calculateFixedPosition(
                position = position,
                parentWidth = parentWidth,
                parentHeight = parentHeight,
                selfWidth = selfWidth,
                selfHeight = selfHeight,
                cursorTop = cursorTop,
                cursorBottom = cursorBottom,
                gap = gap,
                isKeyboardVisible = isVirtualKeyboardVisible
            )
        }

        return calculatePositionForPhysicalKeyboard(
            parentWidth = parentWidth,
            parentHeight = parentHeight,
            selfWidth = selfWidth,
            selfHeight = selfHeight,
            horizontal = horizontal,
            bottom = bottom,
            top = top,
            gap = gap
        )
    }

    /**
     * Position a window configured to prefer one of the four corners.
     */
    private fun calculateFixedPosition(
        position: FloatingCandidatesPosition,
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Float,
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        gap: Float,
        isKeyboardVisible: Boolean
    ): Pair<Float, Float> {
        val keyboardTop = keyboardBounds[1].takeIf { it > 0f } ?: cursorBottom
        val bottomReference = if (isKeyboardVisible) keyboardTop else parentHeight - bottomInsets

        val tX = calculateHorizontalPosition(position, parentWidth, selfWidth, gap)
        var tY = calculateFixedVerticalPosition(
            position = position,
            selfHeight = selfHeight,
            cursorTop = cursorTop,
            cursorBottom = cursorBottom,
            bottomReference = bottomReference,
            gap = gap
        )

        // For Bottom positions with visible keyboard: ensure candidate window
        // does not extend below keyboard top (overlapping the keyboard).
        // For tall windows (e.g. vertical layout), let the top clip naturally
        // instead of forcing it to the top of the screen.
        if (isKeyboardVisible) {
            when (position) {
                FloatingCandidatesPosition.BottomLeft,
                FloatingCandidatesPosition.BottomRight -> {
                    val candidateBottom = tY + selfHeight
                    val maxAllowedBottom = bottomReference - gap
                    if (candidateBottom > maxAllowedBottom) {
                        // Window would overlap keyboard area.
                        // Align to keyboard top — tall windows will clip at the top.
                        tY = bottomReference - gap - selfHeight
                    }
                }
                else -> { /* Top positions don't need this constraint */ }
            }
        }

        return Pair(tX, tY)
    }

    /**
     * Calculate horizontal position for the preferred corner.
     */
    private fun calculateHorizontalPosition(
        position: FloatingCandidatesPosition,
        parentWidth: Float,
        selfWidth: Float,
        gap: Float
    ): Float {
        return when (position) {
            FloatingCandidatesPosition.TopRight,
            FloatingCandidatesPosition.BottomRight -> {
                (parentWidth - selfWidth - gap).coerceAtLeast(gap)
            }
            else -> gap
        }
    }

    /**
     * Calculate vertical position with hysteresis.
     * Requires 2x candidate height to switch position, preventing flicker.
     */
    private fun calculateFixedVerticalPosition(
        position: FloatingCandidatesPosition,
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        bottomReference: Float,
        gap: Float
    ): Float {
        val switchThreshold = selfHeight * 2f

        return when (position) {
            FloatingCandidatesPosition.Default,
            FloatingCandidatesPosition.TopLeft,
            FloatingCandidatesPosition.TopRight -> {
                val spaceAbove = cursorTop - gap
                if (spaceAbove < switchThreshold) {
                    val belowCursorY = cursorBottom + gap
                    val spaceBelow = bottomReference - (belowCursorY + selfHeight)
                    if (spaceBelow >= 0f) belowCursorY else gap
                } else {
                    gap
                }
            }
            FloatingCandidatesPosition.BottomLeft,
            FloatingCandidatesPosition.BottomRight -> {
                // Default placement: right above the keyboard top (bottomReference).
                // Only shift if the candidate window would overlap the cursor.
                // Don't coerce to gap here — tall windows (e.g. vertical layout)
                // should align to keyboard top and clip from the top, rather than
                // getting pushed to the top and covering the cursor entirely.
                val atKeyboardTop = bottomReference - gap - selfHeight
                
                // Check if placing at keyboard top would overlap cursor:
                // window [atKeyboardTop .. atKeyboardTop+selfHeight] vs cursor [cursorTop .. cursorBottom]
                val wouldOverlapCursor = cursorBottom > 0f && cursorTop > 0f &&
                        atKeyboardTop + selfHeight > cursorTop && atKeyboardTop < cursorBottom
                
                if (!wouldOverlapCursor) {
                    // Safe to place at keyboard top
                    atKeyboardTop
                } else if (cursorBottom <= bottomReference) {
                    // Cursor is above keyboard top and would overlap.
                    // Try to place above cursor.
                    val spaceAbove = cursorTop - gap
                    if (spaceAbove >= selfHeight) {
                        cursorTop - gap - selfHeight
                    } else {
                        // Not enough room above cursor — align to keyboard top
                        // (window will clip at top if too tall)
                        atKeyboardTop
                    }
                } else {
                    // Cursor extends below keyboard top (unusual for virtual keyboard).
                    // Try below cursor first, then above cursor, then keyboard top.
                    val belowCursorY = cursorBottom + gap
                    val spaceBelow = bottomReference - (belowCursorY + selfHeight)
                    if (spaceBelow < switchThreshold) {
                        val spaceAbove = cursorTop - gap
                        if (spaceAbove >= selfHeight) {
                            cursorTop - gap - selfHeight
                        } else {
                            atKeyboardTop
                        }
                    } else {
                        belowCursorY
                    }
                }
            }
            FloatingCandidatesPosition.FollowCursor -> bottomReference - gap - selfHeight
        }
    }

    @Deprecated("Use inline logic in calculateFixedVerticalPosition")
    private fun calculateTopPosition(
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        bottomReference: Float,
        gap: Float
    ): Float {
        val targetY = gap
        val candidatesBottom = targetY + selfHeight
        val wouldOverlap = candidatesBottom > cursorTop

        if (wouldOverlap) {
            val belowCursorY = cursorBottom + gap
            val spaceBelow = bottomReference - (belowCursorY + selfHeight)
            return if (spaceBelow >= 0f) belowCursorY else gap
        }
        return targetY
    }

    @Deprecated("Use inline logic in calculateFixedVerticalPosition")
    private fun calculateBottomPosition(
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        bottomReference: Float,
        gap: Float,
        parentHeight: Float
    ): Float {
        val targetY = bottomReference - gap - selfHeight
        val candidatesBottom = targetY + selfHeight
        val candidatesTop = targetY
        val wouldOverlap = candidatesBottom > cursorTop && candidatesTop < cursorBottom

        if (wouldOverlap) {
            val aboveCursorY = cursorTop - gap - selfHeight
            val spaceAbove = cursorTop - gap
            return if (spaceAbove >= selfHeight) aboveCursorY.coerceAtLeast(gap) else targetY.coerceAtLeast(gap)
        }
        return targetY.coerceAtLeast(gap)
    }

    /**
     * Calculate position for physical keyboard mode
     */
    private fun calculatePositionForPhysicalKeyboard(
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Float,
        selfHeight: Float,
        horizontal: Float,
        bottom: Float,
        top: Float,
        gap: Float
    ): Pair<Float, Float> {
        val tX: Float = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            val rtlOffset = parentWidth - horizontal
            if (rtlOffset + selfWidth > parentWidth) selfWidth - parentWidth else -rtlOffset
        } else {
            if (horizontal + selfWidth > parentWidth) parentWidth - selfWidth else horizontal
        }
        val bottomLimit = parentHeight - bottomInsets
        val bottomSpace = bottomLimit - bottom
        // move CandidatesView above cursor anchor, only when
        val tY: Float = if (
            bottom + selfHeight > bottomLimit   // bottom space is not enough
            && top > bottomSpace                // top space is larger than bottom
        ) top - selfHeight else bottom
        return Pair(tX, tY)
    }

    fun updateCursorAnchor(@Size(4) anchor: FloatArray, @Size(2) parent: FloatArray) {
        val (horizontal, bottom, _, top) = anchor
        val (parentWidth, parentHeight) = parent
        anchorPosition[0] = horizontal
        anchorPosition[1] = bottom
        anchorPosition[2] = top
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        updatePosition()
    }

    fun updateCursorAnchorForFloating(
        @Size(3) anchor: FloatArray,
        @Size(2) parent: FloatArray,
        keyboardTop: Float
    ) {
        val (horizontal, bottom, top) = anchor
        val (parentWidth, parentHeight) = parent
        anchorPosition[0] = horizontal
        anchorPosition[1] = bottom
        anchorPosition[2] = top
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight

        // When cursor anchor info is unavailable (both top and bottom are 0),
        // use position-aware fallback cursor values so the overlap-avoidance
        // logic can still make reasonable placement decisions.
        if (top == 0f && bottom == 0f) {
            when (floatingPosition) {
                FloatingCandidatesPosition.TopLeft,
                FloatingCandidatesPosition.TopRight -> {
                    // Assume cursor is near the top of the input area.
                    // A small non-zero value triggers "place below cursor"
                    // so the window does not cover the likely cursor position.
                    val estimatedCursorY = candidatesGap * 4f + dp(20).toFloat()
                    cursorTop = estimatedCursorY
                    cursorBottom = estimatedCursorY
                }
                else -> {
                    // Bottom positions: assume cursor is at keyboard top
                    cursorTop = keyboardTop
                    cursorBottom = keyboardTop
                }
            }
        } else {
            cursorTop = top
            cursorBottom = bottom
        }
        this.keyboardBounds[1] = keyboardTop

        updatePosition()
    }

    /**
     * Anchor candidates view to bottom-left corner, takes navbar bottom insets into consideration.
     * Should only be used when [CursorAnchorInfo][android.view.inputmethod.CursorAnchorInfo] is invalid
     */
    fun updateCursorAnchor(@Size(2) parent: FloatArray) {
        val (parentWidth, parentHeight) = parent
        val bottom = parentHeight - bottomInsets
        anchorPosition[0] = 0f
        anchorPosition[1] = bottom
        anchorPosition[2] = bottom
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        updatePosition()
    }

    init {
        // invisible by default
        visibility = INVISIBLE
        preeditUi.root.id = View.generateViewId()
        candidatesUi.root.id = View.generateViewId()

        minWidth = dp(windowMinWidth)
        padding = dp(windowPadding)
        background = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(windowRadius).toFloat()
        }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        add(preeditUi.root, lParams(wrapContent, wrapContent) {
            topOfParent()
            startOfParent()
        })
        add(candidatesUi.root, lParams(matchConstraints, wrapContent) {
            matchConstraintMinWidth = wrapContent
            below(preeditUi.root)
            centerHorizontally()
            bottomOfParent()
        })

        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(wrapContent, wrapContent)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            bottomInsets = getNavBarBottomInset(insets)
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shouldUpdatePosition = visibility == VISIBLE
    }

    override fun setVisibility(visibility: Int) {
        if (visibility != VISIBLE) {
            touchEventReceiverWindow.dismiss()
        }
        val wasVisible = this.visibility == VISIBLE
        super.setVisibility(visibility)
        // Only report a real change, as the evaluateVisibility path above already does.
        // The listener re-positions the aux bar, and this was invoked on every call — the
        // framework sets the same visibility repeatedly, so nearly every keystroke triggered
        // an aux bar re-layout for nothing (see E4).
        if (wasVisible != (visibility == VISIBLE)) {
            onPositionChanged?.invoke()
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        touchEventReceiverWindow.dismiss()
        super.onDetachedFromWindow()
    }
}
