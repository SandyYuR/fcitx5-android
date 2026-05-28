/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class TokenizedClipboardAdapter(
    private val theme: Theme,
    private val onSelectionChanged: (selectedCount: Int, totalCount: Int) -> Unit
) : RecyclerView.Adapter<TokenizedClipboardAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"
    }

    class ViewHolder(val tokenUi: TokenizedClipboardTokenUi) : RecyclerView.ViewHolder(tokenUi.root)

    private var tokens: List<ClipboardToken> = emptyList()
    private val selectedIndices = linkedSetOf<Int>()
    private var dragSelecting = false
    private var dragSelectValue = false
    private var lastDragIndex = RecyclerView.NO_POSITION
    private var dragStartIndex = RecyclerView.NO_POSITION
    private var dragActivePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(TokenizedClipboardTokenUi(parent.context, theme)).apply {
            itemView.layoutParams = FlexboxLayoutManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(parent.context.dp(3), parent.context.dp(3), parent.context.dp(3), parent.context.dp(3))
            }
        }
    }

    override fun getItemCount(): Int = tokens.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bindItem(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.tokenUi.setSelected(position in selectedIndices)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindItem(holder: ViewHolder, position: Int) {
        val token = tokens[position]
        holder.tokenUi.setText(token.text)
        holder.tokenUi.setSelected(position in selectedIndices)
        holder.itemView.setOnClickListener {
            if (dragSelecting) return@setOnClickListener
            toggleSelection(position)
        }
        holder.itemView.setOnLongClickListener {
            if (dragSelecting) return@setOnLongClickListener true
            beginDragSelection(position)
            true
        }
    }

    fun submitTokens(newTokens: List<ClipboardToken>) {
        tokens = newTokens
        selectedIndices.clear()
        notifyDataSetChanged()
        dispatchSelectionChanged()
    }

    fun toggleSelectAll() {
        if (tokens.isEmpty()) return
        if (selectedIndices.size == tokens.size) {
            selectedIndices.clear()
        } else {
            selectedIndices.clear()
            selectedIndices.addAll(tokens.indices)
        }
        notifyDataSetChanged()
        dispatchSelectionChanged()
    }

    fun clearSelection() {
        if (selectedIndices.isEmpty()) return
        selectedIndices.clear()
        notifyDataSetChanged()
        dispatchSelectionChanged()
    }

    fun invertSelection() {
        if (tokens.isEmpty()) return
        val previous = selectedIndices.toSet()
        selectedIndices.clear()
        tokens.indices.forEach { index ->
            if (index !in previous) {
                selectedIndices.add(index)
            }
        }
        notifyDataSetChanged()
        dispatchSelectionChanged()
    }

    fun selectedTokens(): List<ClipboardToken> =
        selectedIndices.sorted().map { tokens[it] }

    private fun toggleSelection(index: Int) {
        if (index !in tokens.indices) return
        if (!selectedIndices.add(index)) {
            selectedIndices.remove(index)
        }
        notifySelectionChanged(index)
        dispatchSelectionChanged()
    }

    fun beginDragSelection(index: Int) {
        if (index !in tokens.indices) return
        dragSelecting = true
        dragSelectValue = index !in selectedIndices
        dragStartIndex = index
        lastDragIndex = RecyclerView.NO_POSITION
        updateDragSelection(index)
    }

    fun updateDragSelection(index: Int) {
        if (!dragSelecting || index !in tokens.indices || index == lastDragIndex) return
        lastDragIndex = index
        val from = minOf(dragStartIndex, index)
        val to = maxOf(dragStartIndex, index)
        var changed = false
        for (i in from..to) {
            changed = if (dragSelectValue) {
                selectedIndices.add(i) || changed
            } else {
                selectedIndices.remove(i) || changed
            }
        }
        if (changed) {
            notifyItemRangeChanged(from, to - from + 1, PAYLOAD_SELECTION)
            dispatchSelectionChanged()
        }
    }

    fun endDragSelection() {
        dragSelecting = false
        lastDragIndex = RecyclerView.NO_POSITION
        dragStartIndex = RecyclerView.NO_POSITION
        dragActivePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun dispatchSelectionChanged() {
        onSelectionChanged(selectedIndices.size, tokens.size)
    }

    fun handleRecyclerTouch(event: MotionEvent, rv: RecyclerView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragActivePointerId = event.getPointerId(0)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragSelecting) return false
                val pointerIndex = event.findPointerIndex(dragActivePointerId)
                if (pointerIndex < 0) return false
                val view = rv.findChildViewUnder(event.getX(pointerIndex), event.getY(pointerIndex)) ?: return true
                val index = rv.getChildAdapterPosition(view)
                if (index != RecyclerView.NO_POSITION) {
                    updateDragSelection(index)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragActivePointerId = MotionEvent.INVALID_POINTER_ID
                if (dragSelecting) {
                    endDragSelection()
                    return true
                }
            }
        }
        return false
    }

    private fun notifySelectionChanged(index: Int) {
        notifyItemChanged(index, PAYLOAD_SELECTION)
    }
}
