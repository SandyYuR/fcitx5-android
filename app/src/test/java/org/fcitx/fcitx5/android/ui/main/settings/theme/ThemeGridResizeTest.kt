/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class ThemeGridResizeTest {
    private fun grid() = ResponsiveThemeListView(RuntimeEnvironment.getApplication()).apply {
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 12
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = object : RecyclerView.ViewHolder(
                View(parent.context).apply { layoutParams = RecyclerView.LayoutParams(itemWidth, itemHeight) }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
    }
    private fun layout(grid: ResponsiveThemeListView, width: Int) {
        repeat(2) {
            grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
            grid.layout(0, 0, width, 700)
        }
    }
    private fun offsets(grid: ResponsiveThemeListView): Map<Int, Rect> = (0 until grid.childCount).associate { index ->
        val child = grid.getChildAt(index)
        grid.getChildAdapterPosition(child) to Rect().also {
            grid.getItemDecorationAt(0).getItemOffsets(it, child, grid, RecyclerView.State())
        }
    }
    @Test fun resizedGridUsesTheSameSpacingAsAFreshGridAtThatWidth() {
        val resized = grid()
        layout(resized, 360)
        for (width in listOf(800, 360)) {
            layout(resized, width)
            val fresh = grid().also { layout(it, width) }
            assertTrue(fresh.childCount > 0)
            assertEquals(offsets(fresh), offsets(resized))
        }
    }
}
