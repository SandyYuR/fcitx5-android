/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

data class FontEntry(val name: String, var enabled: Boolean)

class FontFallbackAdapter(
    private val entries: MutableList<FontEntry>,
    private val onChanged: () -> Unit,
    private val onDelete: (String) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<FontFallbackAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val enableCheck: CheckBox = view.findViewById(R.id.enableCheck)
        val fontName: TextView = view.findViewById(R.id.fontName)
        val deleteFont: ImageButton = view.findViewById(R.id.deleteFont)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_font_entry, parent, false)
        return VH(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.fontName.text = entry.name
        // Setting state without firing the listener — otherwise rebinding during reorder would
        // re-invoke onChanged for every viewholder swap.
        holder.enableCheck.setOnCheckedChangeListener(null)
        holder.enableCheck.isChecked = entry.enabled
        holder.enableCheck.setOnCheckedChangeListener { _, isChecked ->
            // Re-read position from the holder — list edits below this row would make the captured
            // `position` stale.
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) {
                entries[current].enabled = isChecked
                onChanged()
            }
        }
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
        holder.deleteFont.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) {
                onDelete(entries[current].name)
            }
        }
    }

    override fun getItemCount(): Int = entries.size

    fun move(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(entries, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(entries, i, i - 1)
        }
        notifyItemMoved(from, to)
    }

    fun removeByName(name: String): Boolean {
        val idx = entries.indexOfFirst { it.name == name }
        if (idx < 0) return false
        entries.removeAt(idx)
        notifyItemRemoved(idx)
        return true
    }

    fun add(entry: FontEntry) {
        entries.add(entry)
        notifyItemInserted(entries.size - 1)
    }

    fun snapshot(): List<FontEntry> = entries.toList()

    companion object {
        fun makeTouchHelper(adapter: FontFallbackAdapter, onMoved: () -> Unit): ItemTouchHelper =
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun isLongPressDragEnabled(): Boolean = false

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    onMoved()
                }
            })
    }
}
