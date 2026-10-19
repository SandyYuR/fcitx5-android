/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
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
        observeAdapter()
        refresh()
    }

    fun detach() {
        observedAdapter?.unregisterAdapterDataObserver(adapterObserver)
        observedAdapter = null
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
            outRect.top = 0
            outRect.bottom = dp(4f)
        } else {
            outRect.top = if (position == 0) dp(8f) else dp(2f)
            outRect.bottom = if (position == adapter.itemCount - 1) dp(12f) else dp(2f)
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
        recyclerView.post {
            observeAdapter()
            recyclerView.invalidateItemDecorations()
            for (index in 0 until recyclerView.childCount) {
                styleRow(recyclerView.getChildAt(index))
            }
        }
    }

    private fun styleRow(view: View) {
        val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) return

        val preference = adapter.getItem(position) ?: return
        if (preference is PreferenceCategory) {
            view.background = null
            styleCategoryTitle(view)
            return
        }

        val previous = if (position > 0) adapter.getItem(position - 1) else null
        val next = if (position + 1 < adapter.itemCount) adapter.getItem(position + 1) else null
        val isSearch = preference.key == SettingsSearchPreference.KEY
        val shape = rowShape(
            isSearch = isSearch,
            isFirst = position == 0 || previous is PreferenceCategory,
            isLast = position == adapter.itemCount - 1 || next is PreferenceCategory
        )
        val surface = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(
                color(MaterialR.attr.colorSurfaceBright, color(MaterialR.attr.colorSurface, Color.WHITE))
            )
        }
        val mask = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(Color.WHITE)
        }
        val ripple = color(android.R.attr.colorControlHighlight, Color.TRANSPARENT)
        view.background = RippleDrawable(ColorStateList.valueOf(ripple), surface, mask)
    }

    private fun styleCategoryTitle(view: View) {
        val title = view.findViewById<TextView>(android.R.id.title) ?: return
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.letterSpacing = 0.025f
        title.setTextColor(color(MaterialR.attr.colorOnSurfaceVariant, Color.DKGRAY))
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
