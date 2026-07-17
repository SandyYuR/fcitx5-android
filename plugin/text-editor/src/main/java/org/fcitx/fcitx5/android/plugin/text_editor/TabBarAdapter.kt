/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ItemEditorTabBinding

class TabBarAdapter(
    private val tabs: List<EditorTab>,
    private val activeIndex: () -> Int,
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit,
) : RecyclerView.Adapter<TabBarAdapter.TabVH>() {

    class TabVH(val binding: ItemEditorTabBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabVH {
        val binding = ItemEditorTabBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TabVH(binding)
    }

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: TabVH, position: Int) {
        val tab = tabs[position]
        val isActive = position == activeIndex()
        val binding = holder.binding

        binding.tabName.text = tab.displayName
        binding.tabDirtyDot.visibility = if (tab.isDirty) View.VISIBLE else View.GONE
        binding.tabDirtyDot.text = "●"

        val root = binding.root
        if (isActive) {
            root.alpha = 1f
            root.setBackgroundColor(resolveActiveColor(root))
        } else {
            root.alpha = 0.7f
            root.setBackgroundColor(Color.TRANSPARENT)
        }

        root.setOnClickListener { onTabClick(position) }
        binding.tabClose.setOnClickListener { onTabClose(position) }
    }

    private fun resolveActiveColor(view: View): Int {
        val typedValue = android.util.TypedValue()
        view.context.theme.resolveAttribute(
            android.R.attr.colorControlHighlight, typedValue, true
        )
        return if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
            typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
        ) {
            typedValue.data
        } else {
            0x1A000000
        }
    }
}
