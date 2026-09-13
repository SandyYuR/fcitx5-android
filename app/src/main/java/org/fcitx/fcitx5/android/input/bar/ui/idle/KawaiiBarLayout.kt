/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import splitties.dimensions.dp

/**
 * A custom layout manager for Kawaii Bar that supports:
 * 1. Even distribution (SPACE_AROUND-like) when buttons have enough space
 * 2. Horizontal scrolling when buttons are compressed below minimum width (40dp)
 */
class KawaiiBarLayout(context: Context) : FlexboxLayoutManager(context, RecyclerView.HORIZONTAL) {

    // Minimum button width: 40dp to match icon size
    val minButtonWidth: Int = context.dp(40)
    private val buttonSpacing: Int = context.dp(4) // 2dp margin on each side

    var isEvenDistributionMode = false
        private set

    init {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.FLEX_START
        flexWrap = FlexWrap.NOWRAP
    }

    /**
     * Calculate if buttons should be evenly distributed or scrollable
     * @param childCount Number of buttons
     * @param parentWidth Available width in parent container
     * @return true if buttons can be evenly distributed, false if scrolling is needed
     */
    fun shouldDistributeEvenly(childCount: Int, parentWidth: Int): Boolean {
        if (childCount == 0) return true

        // Calculate minimum required width for all buttons (including margins)
        // Each button has 2dp margin on each side = 4dp total per button
        val minRequiredWidth = childCount * (minButtonWidth + buttonSpacing)

        // If minimum required width is less than parent width, we can distribute evenly
        // Otherwise, enable scrolling
        return minRequiredWidth <= parentWidth
    }

    /**
     * Configure the layout for even distribution mode
     */
    fun setEvenDistributionMode() {
        justifyContent = JustifyContent.SPACE_AROUND
        isEvenDistributionMode = true
    }

    /**
     * Configure the layout for scroll mode
     */
    fun setScrollMode() {
        justifyContent = JustifyContent.FLEX_START
        isEvenDistributionMode = false
    }

    /**
     * Calculate the ideal button width for even distribution
     * @param childCount Number of buttons
     * @param parentWidth Available width in parent container
     * @return Ideal width for each button in pixels (may be less than minButtonWidth)
     */
    fun calculateEvenDistributedWidth(childCount: Int, parentWidth: Int): Int {
        if (childCount == 0) return 0

        // Each button has marginStart=2dp and marginEnd=2dp
        // Total horizontal space per button = button width + 4dp (2dp on each side)
        // Total margin space = 4dp * childCount
        val totalMarginSpace = buttonSpacing * childCount

        // Available width for buttons
        val availableWidth = parentWidth - totalMarginSpace

        // Divide evenly among buttons
        return availableWidth / childCount
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        super.onLayoutChildren(recycler, state)
    }
}

/**
 * A custom RecyclerView for Kawaii Bar buttons with smart layout behavior
 */
class KawaiiBarRecyclerView(context: Context) : RecyclerView(context) {

    private val kawaiiBarLayout = KawaiiBarLayout(context)

    init {
        layoutManager = kawaiiBarLayout
        // Disable nested scrolling to prevent conflicts with parent touch handling
        isNestedScrollingEnabled = false
        // Ensure RecyclerView can scroll horizontally
        overScrollMode = View.OVER_SCROLL_NEVER
        // No item animations, as for every other FlexboxLayoutManager-backed list in this
        // project (PagedCandidatesUi, ExpandedCandidateLayout, HorizontalCandidateComponent,
        // ClipboardWindow, ButtonsAdjustingWindow all do the same). FlexboxLayoutManager does
        // not support predictive item animations, so an animated change of a laid-out bar
        // translates buttons towards positions the animator computed from bad pre-layout info.
        itemAnimator = null
    }

    private var pendingRebind = false
    private var pendingScrollReset = false

    @SuppressLint("NotifyDataSetChanged")
    private val updateLayoutRunnable = Runnable {
        val currentAdapter = adapter ?: return@Runnable
        val currentCount = currentAdapter.itemCount
        val parentWidth = width
        if (currentCount == 0 || parentWidth <= 0) return@Runnable

        val alwaysRebind = pendingRebind
        val resetScroll = pendingScrollReset
        pendingRebind = false
        pendingScrollReset = false

        val idealWidth = kawaiiBarLayout.calculateEvenDistributedWidth(currentCount, parentWidth)
        val shouldDistribute = idealWidth >= kawaiiBarLayout.minButtonWidth
        val modeChanged = shouldDistribute != kawaiiBarLayout.isEvenDistributionMode

        if (shouldDistribute) {
            kawaiiBarLayout.setEvenDistributionMode()
            isHorizontalScrollBarEnabled = false
        } else {
            kawaiiBarLayout.setScrollMode()
            isHorizontalScrollBarEnabled = true
        }
        if (modeChanged || alwaysRebind) {
            // FlexboxLayoutManager retains flex lines and the scroll anchor across an IME window
            // hide/show. A same-sized window does not call onSizeChanged, which can otherwise
            // leave every recycled child outside this center strip while the fixed edge buttons
            // remain visible. A full rebind plus an anchor reset gives the list one authoritative
            // layout whenever its size or window visibility becomes usable again.
            stopScroll()
            if (modeChanged || resetScroll) scrollToPosition(0)
            kawaiiBarLayout.requestLayout()
            currentAdapter.notifyDataSetChanged()
            requestLayout()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Width affects every item's calculated width even when the distribution mode is unchanged.
        updateLayoutMode(alwaysRebind = true, resetScroll = true)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            // InputMethodService commonly reuses this view after its window surface was hidden.
            updateLayoutMode(alwaysRebind = true, resetScroll = true)
        }
    }

    /**
     * Decide between even distribution and scroll mode and coalesce refresh requests per frame.
     *
     * The adapter must not call notify methods from onBindViewHolder. All invalidation lives here,
     * outside RecyclerView's layout/bind pass.
     *
     * @param alwaysRebind rebind even when the distribution mode is unchanged.
     * @param resetScroll discard a possibly stale Flexbox scroll anchor before relayout.
     */
    internal fun updateLayoutMode(
        alwaysRebind: Boolean = false,
        resetScroll: Boolean = false
    ) {
        val currentAdapter = adapter ?: return
        if (currentAdapter.itemCount == 0) return
        pendingRebind = pendingRebind || alwaysRebind
        pendingScrollReset = pendingScrollReset || resetScroll
        removeCallbacks(updateLayoutRunnable)
        post(updateLayoutRunnable)
    }

    /**
     * Check if the layout is in even distribution mode
     */
    fun isEvenDistributionMode(): Boolean {
        return kawaiiBarLayout.isEvenDistributionMode
    }
}
