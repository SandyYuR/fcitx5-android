/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.preference.PreferenceViewHolder
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsSearchPreference

/** Applies the tonal, segmented-list treatment used by the Material 3 settings references. */
class ExpressivePreferenceListStyle(
    private val recyclerView: RecyclerView
) : RecyclerView.ItemDecoration(), RecyclerView.OnChildAttachStateChangeListener {

    private val context = recyclerView.context
    private var observedAdapter: RecyclerView.Adapter<*>? = null
    private var refreshScheduled = false
    private val refreshRows = Runnable {
        refreshScheduled = false
        observeAdapter()
        recyclerView.invalidateItemDecorations()
        for (index in 0 until recyclerView.childCount) styleRow(recyclerView.getChildAt(index))
    }

    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = refresh()

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refresh()

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refresh()

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refresh()

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = refresh()
    }

    fun attach() {
        recyclerView.setBackgroundColor(color(MaterialR.attr.colorSurfaceContainer, Color.WHITE))
        recyclerView.addItemDecoration(this)
        recyclerView.addOnChildAttachStateChangeListener(this)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        observeAdapter()
        refresh()
    }

    fun detach() {
        observedAdapter?.unregisterAdapterDataObserver(adapterObserver)
        observedAdapter = null
        recyclerView.removeCallbacks(refreshRows)
        refreshScheduled = false
        recyclerView.removeOnChildAttachStateChangeListener(this)
        recyclerView.removeItemDecoration(this)
    }

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) return

        outRect.left = dp(16f)
        outRect.right = dp(16f)
        if (adapter.getItem(position) is PreferenceCategory) {
            outRect.top = dp(if (position == 0) 8f else 20f)
            outRect.bottom = dp(8f)
        } else {
            val search = adapter.getItem(position)?.key == SettingsSearchPreference.KEY
            outRect.top = dp(if (position == 0 || search) 8f else 1f)
            outRect.bottom = dp(if (position == adapter.itemCount - 1) 20f else if (search) 0f else 1f)
        }
    }

    override fun onChildViewAttachedToWindow(view: View) {
        styleRow(view)
    }

    override fun onChildViewDetachedFromWindow(view: View) = Unit

    private fun observeAdapter() {
        val adapter = recyclerView.adapter
        if (adapter === observedAdapter) return
        observedAdapter?.unregisterAdapterDataObserver(adapterObserver)
        observedAdapter = adapter
        adapter?.registerAdapterDataObserver(adapterObserver)
    }

    private fun refresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        recyclerView.post(refreshRows)
    }

    private fun styleRow(view: View) {
        val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) return

        val preference = adapter.getItem(position) ?: return
        (recyclerView.getChildViewHolder(view) as? PreferenceViewHolder)?.apply {
            isDividerAllowedAbove = false
            isDividerAllowedBelow = false
        }
        if (preference is PreferenceCategory) {
            view.background = null
            view.clipToOutline = false
            styleCategoryTitle(view)
            return
        }

        val previous = if (position > 0) adapter.getItem(position - 1) else null
        val next = if (position + 1 < adapter.itemCount) adapter.getItem(position + 1) else null
        val isSearch = preference.key == SettingsSearchPreference.KEY
        val shape = rowShape(
            isSearch = isSearch,
            isFirst = previous == null || previous is PreferenceCategory ||
                previous.key == SettingsSearchPreference.KEY || previous.parent !== preference.parent,
            isLast = next == null || next is PreferenceCategory || next.parent !== preference.parent
        )
        val surface = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(
                color(
                    if (isSearch) MaterialR.attr.colorSurfaceContainerHigh else MaterialR.attr.colorSurfaceBright,
                    color(MaterialR.attr.colorSurface, Color.WHITE)
                )
            )
        }
        val mask = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(Color.WHITE)
        }
        val ripple = ColorUtils.setAlphaComponent(color(MaterialR.attr.colorOnSurface, Color.BLACK), 20)
        view.background = if (isSearch || !preference.isSelectable) surface else {
            RippleDrawable(ColorStateList.valueOf(ripple), surface, mask)
        }
        view.clipToOutline = true
    }

    private fun styleCategoryTitle(view: View) {
        val title = view.findViewById<TextView>(android.R.id.title) ?: return
        title.setTextColor(color(androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY))
    }

    private fun rowShape(isSearch: Boolean, isFirst: Boolean, isLast: Boolean): ShapeAppearanceModel {
        val small = dp(4f).toFloat()
        val outer = dp(if (isSearch) 28f else 20f).toFloat()
        return ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, small)
            .apply {
                if (isSearch) {
                    setAllCornerSizes(outer)
                } else {
                    if (isFirst) {
                        setTopLeftCornerSize(outer)
                        setTopRightCornerSize(outer)
                    }
                    if (isLast) {
                        setBottomLeftCornerSize(outer)
                        setBottomRightCornerSize(outer)
                    }
                }
            }
            .build()
    }

    private fun color(attribute: Int, fallback: Int): Int =
        MaterialColors.getColor(context, attribute, fallback)

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
